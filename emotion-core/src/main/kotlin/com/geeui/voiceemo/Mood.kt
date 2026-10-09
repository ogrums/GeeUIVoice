package com.geeui.voiceemo

/**
 * Persistent mood. Each observation blends in. Silence pulls back to neutral.
 * [halfLifeMs] is the time for intensity to fall by half with no new affect.
 */
class Mood(
    val halfLifeMs: Long = 45_000,
    private val blend: Float = 0.6f,
    now: Long = 0L,
) {
    var emotion: Emotion = Emotion.NEUTRAL
        private set
    var intensity: Float = 0f
        private set
    private var updatedAt: Long = now

    fun observe(affect: Affect, now: Long) {
        decay(now)
        if (affect.confidence <= 0f) return
        val weight = blend * affect.confidence
        if (affect.emotion == emotion || emotion == Emotion.NEUTRAL) {
            emotion = affect.emotion
            intensity = (intensity * (1f - weight) + weight).coerceIn(0f, 1f)
        } else if (weight > intensity) {
            emotion = affect.emotion
            intensity = weight
        } else {
            intensity *= (1f - weight)
        }
        if (intensity < 0.08f) {
            emotion = Emotion.NEUTRAL
            intensity = 0f
        }
        updatedAt = now
    }

    fun snapshot(now: Long): Affect {
        decay(now)
        updatedAt = now
        return Affect(emotion, intensity)
    }

    private fun decay(now: Long) {
        val elapsed = (now - updatedAt).coerceAtLeast(0L)
        if (elapsed == 0L || intensity == 0f || halfLifeMs <= 0L) return
        val halves = elapsed.toFloat() / halfLifeMs
        intensity *= Math.pow(0.5, halves.toDouble()).toFloat()
        if (intensity < 0.08f) {
            emotion = Emotion.NEUTRAL
            intensity = 0f
        }
    }
}
