package com.geeui.voice

import android.content.Context
import com.geeui.voiceemo.Affect
import com.geeui.voiceemo.Emotion
import com.geeui.voiceemo.LogMel
import com.geeui.voiceemo.ScoreAffect
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads a float TFLite file dropped next to the app, or bundled under assets.
 * Voice: [1, 128, 64] log-mel. Face: [1, 48, 48, 1] gray.
 * Missing file leaves the affect unknown, so the keyword list still decides.
 */
class TfliteSense(private val context: Context) {
    var status: String = "tflite idle"
        private set

    private val voiceModel = model("emotion_voice.tflite")
    private val faceModel = model("emotion_face.tflite")

    fun voice(pcm: ShortArray): Affect {
        val interpreter = voiceModel
        if (interpreter == null) {
            status = "tflite voice: modèle absent"
            return Affect.UNKNOWN
        }
        return run(interpreter, voiceBuffer(LogMel.features(pcm)), ScoreAffect.VOICE, "voice")
    }

    fun face(gray: FloatArray): Affect {
        val interpreter = faceModel
        if (interpreter == null) {
            status = "tflite face: modèle absent"
            return Affect.UNKNOWN
        }
        val buf = ByteBuffer.allocateDirect(gray.size * 4).order(ByteOrder.nativeOrder())
        for (v in gray) buf.putFloat(v)
        buf.rewind()
        return run(interpreter, buf, ScoreAffect.FACE, "face")
    }

    fun close() {
        voiceModel?.close()
        faceModel?.close()
    }

    fun voiceReady(): Boolean = voiceModel != null

    fun faceReady(): Boolean = faceModel != null

    private fun run(interpreter: Interpreter, input: ByteBuffer, labels: List<Emotion>, kind: String): Affect {
        val count = interpreter.getOutputTensor(0).shape().last().coerceAtLeast(1)
        val output = Array(1) { FloatArray(count) }
        return try {
            synchronized(interpreter) { interpreter.run(input, output) }
            val affect = ScoreAffect.of(output[0], labels)
            status = "tflite $kind ${affect.emotion.name.lowercase()} ${"%.2f".format(affect.confidence)}"
            affect
        } catch (e: Exception) {
            status = "tflite $kind: ${e.javaClass.simpleName}"
            Affect.UNKNOWN
        }
    }

    private fun voiceBuffer(frames: Array<FloatArray>): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(LogMel.FRAMES * LogMel.MELS * 4).order(ByteOrder.nativeOrder())
        for (row in frames) for (v in row) buf.putFloat(v)
        buf.rewind()
        return buf
    }

    private fun model(name: String): Interpreter? {
        val mapped = mapFile(File(context.getExternalFilesDir(null), name))
            ?: mapAsset(name)
            ?: return null
        return Interpreter(mapped, Interpreter.Options().apply { setNumThreads(2) })
    }

    private fun mapFile(file: File): MappedByteBuffer? {
        if (!file.isFile || file.length() <= 0L) return null
        return FileInputStream(file).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
    }

    private fun mapAsset(name: String): MappedByteBuffer? {
        return try {
            context.assets.openFd(name).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use {
                    it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
