package com.geeui.voice.engine

import com.geeui.voiceemo.TtsEngine
import com.geeui.voiceemo.TtsRoute

/** Filled by VoiceSession just before it speaks. The player reads it. */
class TtsPlan {
    var engine: TtsEngine = TtsEngine.KOKORO
    var emotion: String = "neutral"
}

/**
 * Speaks with CosyVoice when the plan says so. An empty clip or a failure
 * falls back to Kokoro so a missing sidecar does not drop the line.
 */
class PlannedTts(
    private val plan: TtsPlan,
    private val kokoro: ClipTts,
    private val cosy: ClipTts,
) : ClipTts {
    private var used: ClipTts = kokoro
    private var audible: (() -> Unit)? = null

    override val lastAudio: ByteArray
        get() = used.lastAudio

    override fun whenAudible(action: (() -> Unit)?) {
        audible = action
    }

    override fun speak(text: String, language: String) {
        if (plan.engine == TtsEngine.COSYVOICE) {
            try {
                cosy.speak(text, language)
                if (cosy.lastAudio.isNotEmpty()) {
                    used = cosy
                    heard()
                    return
                }
            } catch (_: Exception) {
            }
        }
        used = kokoro
        kokoro.speak(text, language)
        heard()
    }

    private fun heard() {
        val action = audible
        audible = null
        action?.invoke()
    }

    override fun stop() {
        cosy.stop()
        kokoro.stop()
    }
}

/** POST {base}/v1/audio/speech. [post] is the HTTP call so tests stay offline. */
class CosyTts(
    private val plan: TtsPlan,
    private val post: (text: String, emotion: String) -> ByteArray,
) : ClipTts {
    override var lastAudio: ByteArray = ByteArray(0)
        private set

    override fun speak(text: String, language: String) {
        lastAudio = post(TtsRoute.clip(text), plan.emotion)
    }

    override fun stop() {
        lastAudio = ByteArray(0)
    }

    companion object {
        const val MODEL = "Fun-CosyVoice3-0.5B-2512"

        fun body(text: String, emotion: String, model: String = MODEL): String =
            """{"model":${json(model)},"input":${json(text)},"voice":${json(emotion.ifBlank { "neutral" })},"response_format":"wav"}"""

        private fun json(value: String): String {
            val out = StringBuilder(value.length + 2)
            out.append('"')
            for (c in value) {
                when (c) {
                    '\\' -> out.append("\\\\")
                    '"' -> out.append("\\\"")
                    '\n' -> out.append("\\n")
                    else -> out.append(c)
                }
            }
            out.append('"')
            return out.toString()
        }
    }
}
