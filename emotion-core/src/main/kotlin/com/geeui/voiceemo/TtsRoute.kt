package com.geeui.voiceemo

enum class TtsEngine { KOKORO, COSYVOICE }

/**
 * CosyVoice only when the line is short, the emotion is not neutral,
 * and the sidecar answered recently. Otherwise Kokoro on Lemonade.
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
        val expressive = emotion != Emotion.NEUTRAL && text.trim().length in 1..MAX_CHARS
        return if (healthy && expressive) TtsEngine.COSYVOICE else TtsEngine.KOKORO
    }
}
