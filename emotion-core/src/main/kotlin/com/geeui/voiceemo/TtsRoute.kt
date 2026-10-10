package com.geeui.voiceemo

enum class TtsEngine { KOKORO, COSYVOICE }

/**
 * CosyVoice when the sidecar answered recently and the emotion is not neutral.
 * A long answer is clipped to [MAX_CHARS] by [clip], not sent to Kokoro.
 * Otherwise Kokoro on Lemonade.
 */
object TtsRoute {
    const val MAX_CHARS = 180
    const val HEALTH_MS = 15_000L

    fun choose(
        text: String,
        emotion: Emotion,
        sidecarOkAt: Long,
        now: Long,
    ): TtsEngine {
        val healthy = sidecarOkAt > 0L && now - sidecarOkAt <= HEALTH_MS
        if (!healthy || emotion == Emotion.NEUTRAL || text.isBlank()) return TtsEngine.KOKORO
        return TtsEngine.COSYVOICE
    }

    /** The sidecar rejects more than [MAX_CHARS]. Cut on a sentence end when there is one. */
    fun clip(text: String): String {
        val clean = text.trim()
        if (clean.length <= MAX_CHARS) return clean
        val window = clean.substring(0, MAX_CHARS)
        val cut = window.lastIndexOfAny(charArrayOf('.', '!', '?', '…'))
        return if (cut >= 40) window.substring(0, cut + 1).trim() else window.trim()
    }
}
