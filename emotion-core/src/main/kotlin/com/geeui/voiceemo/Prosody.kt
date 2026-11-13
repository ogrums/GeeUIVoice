package com.geeui.voiceemo

/**
 * How the TTS voice is bent for one reply.
 * [speed] and [pitch] are playback ratios (1 = unchanged), [gain] a linear volume ratio.
 */
data class Prosody(val speed: Float = 1f, val pitch: Float = 1f, val gain: Float = 1f) {
    val isNeutral: Boolean get() = speed == 1f && pitch == 1f && gain == 1f

    companion object {
        val NEUTRAL = Prosody()
    }
}

/**
 * Per-emotion voice accent, driven by the fused emotion of [EmotionFusion].
 *
 * The table holds the full-strength values. The real deviation from neutral is
 * scaled by how sure the fusion is: score at [FLOOR] (0.45) gives nothing,
 * score 1.0 gives the full table value, linear in between. So a doubtful emotion
 * barely changes the voice. Keep values within about ±15 %, beyond that the voice
 * sounds robotic.
 */
object EmotionProsody {
    /** Global switch. Set false to keep the voice flat whatever the emotion. */
    @Volatile
    var enabled: Boolean = true

    val TABLE: Map<Emotion, Prosody> = mapOf(
        Emotion.NEUTRAL to Prosody.NEUTRAL,
        Emotion.HAPPY to Prosody(speed = 1.08f, pitch = 1.06f, gain = 1.00f),
        Emotion.SAD to Prosody(speed = 0.90f, pitch = 0.94f, gain = 0.85f),
        Emotion.ANGRY to Prosody(speed = 1.06f, pitch = 0.98f, gain = 1.15f),
        Emotion.FEAR to Prosody(speed = 1.10f, pitch = 1.08f, gain = 0.95f),
        Emotion.SURPRISE to Prosody(speed = 1.05f, pitch = 1.10f, gain = 1.05f),
        // Slightly slower, a little quieter: a recoil, not the bite of angry.
        Emotion.DISGUST to Prosody(speed = 0.95f, pitch = 0.96f, gain = 0.90f),
    )

    /**
     * Floor below which the voice stays flat. Kept at 0.45 so an audio vote that
     * just clears the fusion threshold (0.70) still bends the voice.
     */
    const val FLOOR = 0.45

    /** 0 at [FLOOR], 1 at score 1.0. */
    fun strength(score: Double): Float {
        val t = FLOOR
        return ((score - t) / (1.0 - t)).coerceIn(0.0, 1.0).toFloat()
    }

    fun of(emotion: Emotion, score: Double): Prosody {
        if (!enabled || emotion == Emotion.NEUTRAL) return Prosody.NEUTRAL
        val full = TABLE[emotion] ?: return Prosody.NEUTRAL
        val k = strength(score)
        if (k == 0f) return Prosody.NEUTRAL
        fun mix(v: Float) = 1f + (v - 1f) * k
        return Prosody(speed = mix(full.speed), pitch = mix(full.pitch), gain = mix(full.gain))
    }
}
