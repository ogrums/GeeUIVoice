package com.geeui.voiceemo

/**
 * Picks the emotion that drives the face and the voice for one reply.
 *
 * Audio above [audioThreshold] (default 0.70) wins outright: the LLM tag is ignored.
 * The tag is only used when audio is neutral or below the threshold. A non-neutral
 * result is held for [cooldownMs] so two close turns do not flip the face twice.
 */
class EmotionFusion(
    private val clock: () -> Long = { System.currentTimeMillis() },
    val audioThreshold: Double = AUDIO_THRESHOLD,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private var lastReactionAt = 0L

    fun fuse(
        audioEmotion: Emotion,
        audioConfidence: Double,
        textEmotion: Emotion,
        textTagPresent: Boolean,
    ): Result {
        val audioConf = audioConfidence.coerceIn(0.0, 1.0)
        val result = when {
            audioEmotion != Emotion.NEUTRAL && audioConf >= audioThreshold ->
                Result(audioEmotion, audioConf)
            textTagPresent && textEmotion != Emotion.NEUTRAL ->
                Result(textEmotion, TEXT_SCORE)
            else ->
                Result(Emotion.NEUTRAL, audioConf)
        }
        if (result.emotion == Emotion.NEUTRAL) return result
        val now = clock()
        if (lastReactionAt != 0L && now - lastReactionAt < cooldownMs) {
            return Result(Emotion.NEUTRAL, result.score)
        }
        lastReactionAt = now
        return result
    }

    data class Result(val emotion: Emotion, val score: Double)

    companion object {
        /** Audio confidence at which the microphone vote overrides the LLM tag. */
        const val AUDIO_THRESHOLD = 0.70
        /** Score given to a text tag when it is used (audio missing or below threshold). */
        const val TEXT_SCORE = 0.70
        const val COOLDOWN_MS = 4_000L

        /** Kept as an alias of [AUDIO_THRESHOLD] for callers that still name it THRESHOLD. */
        const val THRESHOLD = AUDIO_THRESHOLD
    }
}
