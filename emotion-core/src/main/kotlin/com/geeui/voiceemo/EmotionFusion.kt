package com.geeui.voiceemo

/**
 * Single source of truth for reaction fusion on the robot.
 * Audio emotion (from the gateway transcription response) weighs 0.6, the LLM
 * reply tag from [EmotionTagParser] weighs 0.4 (confidence 1.0 when a tag was
 * present, 0 otherwise). Best score below 0.45 yields neutral; non-neutral
 * reactions are suppressed for [cooldownMs] after the last one.
 */
class EmotionFusion(
    private val cooldownMs: Long = 4_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Result(val emotion: Emotion, val score: Double)

    private var lastReactionAt: Long? = null

    @Synchronized
    fun fuse(audioEmotion: Emotion, audioConfidence: Double, textEmotion: Emotion, textTagPresent: Boolean): Result {
        val scores = HashMap<Emotion, Double>()
        scores.merge(audioEmotion, AUDIO_WEIGHT * audioConfidence.coerceIn(0.0, 1.0), Double::plus)
        scores.merge(textEmotion, TEXT_WEIGHT * (if (textTagPresent) 1.0 else 0.0), Double::plus)
        val best = scores.maxByOrNull { it.value }!!
        if (best.value < THRESHOLD || best.key == Emotion.NEUTRAL) return Result(Emotion.NEUTRAL, best.value)
        val now = clock()
        val last = lastReactionAt
        if (last != null && now - last < cooldownMs) return Result(Emotion.NEUTRAL, best.value)
        lastReactionAt = now
        return Result(best.key, best.value)
    }

    companion object {
        const val AUDIO_WEIGHT = 0.6
        const val TEXT_WEIGHT = 0.4
        const val THRESHOLD = 0.45
    }
}
