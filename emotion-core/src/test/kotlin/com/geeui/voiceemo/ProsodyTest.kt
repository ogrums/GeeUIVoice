package com.geeui.voiceemo

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProsodyTest {
    @AfterTest
    fun reset() {
        EmotionProsody.enabled = true
    }

    @Test
    fun neutralStaysFlat() {
        assertEquals(Prosody.NEUTRAL, EmotionProsody.of(Emotion.NEUTRAL, 1.0))
    }

    @Test
    fun atThresholdNothingChanges() {
        assertEquals(Prosody.NEUTRAL, EmotionProsody.of(Emotion.HAPPY, EmotionProsody.FLOOR))
        assertEquals(Prosody.NEUTRAL, EmotionProsody.of(Emotion.SAD, 0.2))
    }

    @Test
    fun fullScoreGivesTableValue() {
        assertEquals(EmotionProsody.TABLE[Emotion.SAD], EmotionProsody.of(Emotion.SAD, 1.0))
    }

    @Test
    fun halfwayIsHalfTheDeviation() {
        val score = EmotionProsody.FLOOR + (1.0 - EmotionProsody.FLOOR) / 2
        val p = EmotionProsody.of(Emotion.HAPPY, score)
        assertEquals(1.04f, p.speed, 1e-4f)
        assertEquals(1.03f, p.pitch, 1e-4f)
    }

    @Test
    fun valuesStayWithinFifteenPercent() {
        for ((_, p) in EmotionProsody.TABLE) {
            for (v in listOf(p.speed, p.pitch, p.gain)) assertTrue(v in 0.85f..1.15f, "$p")
        }
    }

    @Test
    fun switchOffKeepsVoiceFlat() {
        EmotionProsody.enabled = false
        assertEquals(Prosody.NEUTRAL, EmotionProsody.of(Emotion.ANGRY, 1.0))
    }
}
