package com.geeui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size

/**
 * Grabs the camera luminance, center-crops a square and scales it to 48×48.
 * No preview: the robot screen stays on the face video.
 */
class FaceWatch(
    private val context: Context,
    private val onGray: (FloatArray) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    fun start() {
        if (thread != null) return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            VoiceHud.diag = "tflite face: caméra non autorisée"
            return
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull()
        if (id == null) {
            VoiceHud.diag = "tflite face: pas de caméra"
            return
        }
        val bg = HandlerThread("geeui-face")
        bg.start()
        thread = bg
        val h = Handler(bg.looper)
        handler = h
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    listen(camera, h)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    VoiceHud.diag = "tflite face: caméra $error"
                    camera.close()
                }
            }, h)
        } catch (e: SecurityException) {
            VoiceHud.diag = "tflite face: caméra non autorisée"
            stop()
        }
    }

    fun stop() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        session = null
        try {
            device?.close()
        } catch (_: Exception) {
        }
        device = null
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun listen(camera: CameraDevice, h: Handler) {
        val size = streamSize(camera.id) ?: Size(320, 240)
        val images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = images
        images.setOnImageAvailableListener({ src ->
            val image = src.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                onGray(gray48(image))
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, h)
        camera.createCaptureSession(listOf(images.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(capture: CameraCaptureSession) {
                session = capture
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                request.addTarget(images.surface)
                try {
                    capture.setRepeatingRequest(request.build(), null, h)
                } catch (_: Exception) {
                    VoiceHud.diag = "tflite face: flux refusé"
                }
            }

            override fun onConfigureFailed(capture: CameraCaptureSession) {
                VoiceHud.diag = "tflite face: flux refusé"
            }
        }, h)
    }

    private fun streamSize(id: String): Size? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val map = manager.getCameraCharacteristics(id)
            .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return null
        return map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.minByOrNull { kotlin.math.abs(it.width * it.height - 320 * 240) }
    }

    private fun gray48(image: Image): FloatArray {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val side = minOf(w, h)
        val x0 = (w - side) / 2
        val y0 = (h - side) / 2
        val out = FloatArray(48 * 48)
        for (y in 0 until 48) {
            val sy = y0 + y * side / 48
            for (x in 0 until 48) {
                val sx = x0 + x * side / 48
                val at = sy * rowStride + sx * pixelStride
                val luma = if (at in 0 until buf.limit()) buf.get(at).toInt() and 0xff else 0
                out[y * 48 + x] = luma / 255f
            }
        }
        return out
    }
}
