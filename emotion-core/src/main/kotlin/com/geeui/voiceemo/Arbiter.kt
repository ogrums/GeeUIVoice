package com.geeui.voiceemo

/**
 * Audio and text each cast a vote. The higher confidence wins.
 * A near tie on two different labels returns neutral at low confidence,
 * so the mood does not flip on a shrug.
 */
object Arbiter {
    const val TIE = 0.15f

    fun merge(audio: Affect, text: Affect): Affect {
        if (audio.confidence <= 0f) return text
        if (text.confidence <= 0f) return audio
        if (audio.emotion == text.emotion) {
            val c = (audio.confidence + text.confidence) / 2f
            return Affect(audio.emotion, c.coerceIn(0f, 1f))
        }
        val gap = kotlin.math.abs(audio.confidence - text.confidence)
        if (gap < TIE) return Affect(Emotion.NEUTRAL, gap)
        return if (audio.confidence > text.confidence) audio else text
    }
}
