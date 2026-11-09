package com.geeui.voice.engine

/**
 * Gradium Phonon through ptts-openai-server (Docker, port 8880), OpenAI /v1/audio/speech.
 * The server only accepts speed 1.0, so no speed is sent: [speedApplied] stays false and
 * the player applies the emotion speed on the robot. [post] is the HTTP call so tests stay offline.
 */
class PhononTts(
    private val voice: String = VOICE,
    private val post: (body: String) -> ByteArray,
) : ClipTts {
    override var lastAudio: ByteArray = ByteArray(0)
        private set

    override fun speak(text: String, language: String) {
        lastAudio = post(body(text, voice))
    }

    override fun stop() {
        lastAudio = ByteArray(0)
    }

    companion object {
        const val VOICE = "Hélène"

        /** mp3: a streamed wav has no length in its header. */
        fun body(text: String, voice: String = VOICE): String =
            """{"model":"tts-1","input":${CosyTts.json(text)},"voice":${CosyTts.json(voice.ifBlank { "default" })},"response_format":"mp3"}"""
    }
}

/** Speaks with [primary]; an empty clip or a failure falls back to [fallback] so a stopped server does not drop the line. */
class FallbackTts(
    private val primary: ClipTts,
    private val fallback: ClipTts,
) : ClipTts {
    private var used: ClipTts = fallback

    override val lastAudio: ByteArray
        get() = used.lastAudio

    override val speedApplied: Boolean
        get() = used.speedApplied

    override fun speak(text: String, language: String) {
        try {
            primary.speak(text, language)
            if (primary.lastAudio.isNotEmpty() && primary.lastAudio[0] != '{'.code.toByte()) {
                used = primary
                return
            }
        } catch (_: Exception) {
        }
        used = fallback
        fallback.speak(text, language)
    }

    override fun stop() {
        primary.stop()
        fallback.stop()
    }
}
