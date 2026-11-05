package com.geeui.voiceemo

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SenseTest {
    @Test
    fun aTonePeaksBelowTheTopMelBin() {
        val pcm = ShortArray(LogMel.SAMPLE_RATE) { i ->
            (sin(2.0 * PI * 440.0 * i / LogMel.SAMPLE_RATE) * 16000.0).toInt().toShort()
        }
        val row = LogMel.features(pcm).last()
        assertEquals(LogMel.MELS, row.size)
        assertTrue(row[12] > row[63])
    }

    @Test
    fun voiceScoresPickTheLoudestLabel() {
        val affect = ScoreAffect.of(
            floatArrayOf(0.05f, 0.8f, 0.05f, 0.04f, 0.03f, 0.03f),
            ScoreAffect.VOICE,
        )
        assertEquals(Emotion.HAPPY, affect.emotion)
        assertEquals(0.8f, affect.confidence)
    }

    @Test
    fun disgustOnAFaceModelCountsAsAngry() {
        val affect = ScoreAffect.of(
            floatArrayOf(0.05f, 0.7f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f),
            ScoreAffect.FACE,
        )
        assertEquals(Emotion.ANGRY, affect.emotion)
    }

    @Test
    fun aWeakScoreStaysNeutral() {
        val affect = ScoreAffect.of(
            floatArrayOf(0.2f, 0.3f, 0.1f, 0.1f, 0.1f, 0.2f),
            ScoreAffect.VOICE,
        )
        assertEquals(Emotion.NEUTRAL, affect.emotion)
    }
}
