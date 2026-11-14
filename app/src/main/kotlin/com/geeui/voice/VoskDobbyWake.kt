package com.geeui.voice

import android.util.Log
import com.geeui.voice.session.DobbyPhrase
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Streaming closed-grammar Vosk recognizer for « Hey Dobby ».
 * Not thread-safe: use from the dobby listener thread only.
 */
class VoskDobbyWake(modelDir: File) : AutoCloseable {
    private val model = Model(modelDir.absolutePath)
    private val recognizer = Recognizer(model, SAMPLE_RATE, DobbyPhrase.GRAMMAR)

    init {
        recognizer.setWords(true)
        Log.i(TAG, "vosk dobby ready ${modelDir.absolutePath} (${VoskModelStore.ATTRIBUTION})")
    }

    /**
     * Feed one PCM16 LE mono chunk. Returns an accepted phrase, or null
     * if this chunk did not end a matching utterance.
     */
    fun accept(pcm: ByteArray, length: Int): DobbyPhrase.Verdict? {
        if (length <= 0) return null
        val ended = recognizer.acceptWaveForm(pcm, length)
        if (!ended) return null
        return consider(recognizer.result)
    }

    /** Flush a trailing utterance when the listener pauses. */
    fun flush(): DobbyPhrase.Verdict? = consider(recognizer.finalResult)

    private fun consider(json: String): DobbyPhrase.Verdict? {
        val parsed = parse(json) ?: return null
        val verdict = DobbyPhrase.consider(parsed.text, dobbyConf = parsed.dobbyConf)
        val conf = parsed.dobbyConf?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "-"
        if (!verdict.accepted) {
            if (verdict.phrase.isNotBlank() && verdict.phrase != "[unk]") {
                EventLog.add("dobby ignored(${verdict.reason}) [${verdict.phrase}] conf=$conf")
            }
            return null
        }
        EventLog.add("dobby heard [${verdict.phrase}] conf=$conf")
        return verdict
    }

    override fun close() {
        try {
            recognizer.close()
        } catch (_: Exception) {
        }
        try {
            model.close()
        } catch (_: Exception) {
        }
    }

    data class Parsed(val text: String, val dobbyConf: Float?)

    companion object {
        const val SAMPLE_RATE = 16_000f
        private const val TAG = "GeeUIVoiceTest"

        fun parse(json: String): Parsed? {
            if (json.isBlank()) return null
            return try {
                val obj = JSONObject(json)
                val text = obj.optString("text", "")
                var dobbyConf: Float? = null
                val words = obj.optJSONArray("result")
                if (words != null) {
                    for (i in 0 until words.length()) {
                        val w = words.optJSONObject(i) ?: continue
                        if (w.optString("word") == "dobby") {
                            dobbyConf = w.optDouble("conf", Double.NaN).toFloat().takeIf { it.isFinite() }
                            break
                        }
                    }
                }
                Parsed(text, dobbyConf)
            } catch (_: Exception) {
                null
            }
        }
    }
}
