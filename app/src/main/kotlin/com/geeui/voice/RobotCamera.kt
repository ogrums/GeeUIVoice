package com.geeui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.geeui.voice.engine.pickJpegSize
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One JPEG from the robot camera, on demand, with plain Camera2 (the Letianpai SDK and
 * AIDL expose no camera API; `rhj.controller.takephoto` only starts the stock photo app).
 * Opens the camera, lets auto-exposure settle for a few frames, keeps the last one, closes.
 * Returns null when there is no permission, no camera, the camera is busy (face ident), or
 * after [timeoutMs]. Nothing is written to disk.
 */
class RobotCamera(
    private val context: Context,
    private val maxWidth: Int = 1280,
    private val warmupFrames: Int = 8,
    private val timeoutMs: Long = 4_000,
) {
    @Volatile var lastError: String = ""
        private set

    fun snapshot(): ByteArray? {
        lastError = ""
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            lastError = "permission caméra"
            return null
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = pickCamera(manager) ?: run {
            lastError = "pas de caméra"
            return null
        }
        val size = pickSize(manager, id) ?: run {
            lastError = "pas de JPEG"
            return null
        }
        val thread = HandlerThread("rux-camera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        val result = AtomicReference<ByteArray?>(null)
        val done = CountDownLatch(1)
        val device = AtomicReference<CameraDevice?>(null)
        var frames = 0
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                frames++
                if (frames >= warmupFrames && result.get() == null) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    result.set(bytes)
                    done.countDown()
                }
            } finally {
                image.close()
            }
        }, handler)
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device.set(camera)
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        set(CaptureRequest.JPEG_QUALITY, 85.toByte())
                                    }.build()
                                    // Repeating so auto-exposure and white balance settle before the kept frame.
                                    session.setRepeatingRequest(request, null, handler)
                                } catch (e: Exception) {
                                    lastError = "capture ${e.javaClass.simpleName}"
                                    done.countDown()
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                lastError = "session refusée"
                                done.countDown()
                            }
                        }, handler)
                    } catch (e: Exception) {
                        lastError = "session ${e.javaClass.simpleName}"
                        done.countDown()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    lastError = "caméra déconnectée"
                    camera.close()
                    done.countDown()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    lastError = "caméra occupée ou en erreur ($error)"
                    camera.close()
                    done.countDown()
                }
            }, handler)
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS) && lastError.isEmpty()) lastError = "délai dépassé"
        } catch (e: Exception) {
            lastError = "ouverture ${e.javaClass.simpleName}"
        } finally {
            try {
                device.get()?.close()
            } catch (_: Exception) {
            }
            reader.close()
            thread.quitSafely()
        }
        return result.get()
    }

    /** Back camera if any (looks where the robot faces), else the first one. */
    private fun pickCamera(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    /** Largest JPEG size not wider than [maxWidth]: enough to read a page, small enough to send fast. */
    private fun pickSize(manager: CameraManager, id: String): Size? {
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        return pickJpegSize(sizes.map { it.width to it.height }, maxWidth)?.let { Size(it.first, it.second) }
    }
}
