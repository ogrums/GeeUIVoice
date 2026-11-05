package com.geeui.voiceemo

import kotlin.math.exp

/**
 * Turns a model vector into one [Affect].
 * Voice models use [VOICE]. FER-2013 face models use [FACE]: disgust has no pose, so it counts as angry.
 */
object ScoreAffect {
    val VOICE = listOf(
        Emotion.NEUTRAL,
        Emotion.HAPPY,
        Emotion.SAD,
        Emotion.ANGRY,
        Emotion.FEAR,
        Emotion.SURPRISE,
    )

    val FACE = listOf(
        Emotion.ANGRY,
        Emotion.ANGRY,
        Emotion.FEAR,
        Emotion.HAPPY,
        Emotion.SAD,
        Emotion.SURPRISE,
        Emotion.NEUTRAL,
    )

    const val FLOOR = 0.45f

    fun of(scores: FloatArray, labels: List<Emotion>, floor: Float = FLOOR): Affect {
        if (scores.isEmpty() || labels.isEmpty()) return Affect.UNKNOWN
        val probs = probabilities(scores)
        val n = minOf(probs.size, labels.size)
        var best = 0
        for (i in 1 until n) if (probs[i] > probs[best]) best = i
        val confidence = probs[best].coerceIn(0f, 1f)
        if (confidence < floor) return Affect(Emotion.NEUTRAL, confidence)
        return Affect(labels[best], confidence)
    }

    private fun probabilities(scores: FloatArray): FloatArray {
        val sum = scores.sum()
        val already = scores.all { it in 0f..1f } && sum in 0.95f..1.05f
        if (already) return scores
        val peak = scores.maxOrNull() ?: return scores
        var total = 0.0
        val out = FloatArray(scores.size)
        for (i in scores.indices) {
            val e = exp((scores[i] - peak).toDouble())
            out[i] = e.toFloat()
            total += e
        }
        if (total <= 0.0) return scores
        for (i in out.indices) out[i] = (out[i] / total).toFloat()
        return out
    }
}
