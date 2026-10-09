package com.geeui.voiceemo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmotionPipelineTest {
    @Test
    fun tie_on_disagreement_stays_neutral() {
        val merged = Arbiter.merge(
            Affect(Emotion.ANGRY, 0.55f),
            Affect(Emotion.HAPPY, 0.50f),
        )
        assertEquals(Emotion.NEUTRAL, merged.emotion)
    }

    @Test
    fun audio_wins_when_clearly_stronger() {
        val merged = Arbiter.merge(
            Affect(Emotion.SAD, 0.9f),
            Affect(Emotion.NEUTRAL, 0.4f),
        )
        assertEquals(Emotion.SAD, merged.emotion)
    }

    @Test
    fun mood_decays_to_neutral() {
        val mood = Mood(halfLifeMs = 1_000, now = 0L)
        mood.observe(Affect(Emotion.HAPPY, 1f), now = 0L)
        val later = mood.snapshot(now = 10_000L)
        assertEquals(Emotion.NEUTRAL, later.emotion)
    }

    @Test
    fun short_sad_line_uses_cosyvoice_and_cry_face() {
        val mood = Mood(now = 0L)
        val turn = EmotionPipeline.turn(
            say = "D'accord, j'y vais doucement.",
            audio = Affect(Emotion.SAD, 0.8f),
            text = Affect(Emotion.SAD, 0.7f),
            mood = mood,
            now = 1_000L,
            sidecarOkAt = 500L,
        )
        assertEquals(TtsEngine.COSYVOICE, turn.engine)
        assertEquals("h0119", turn.pose.faceId)
        assertTrue(turn.mood.confidence > 0.25f)
    }

    @Test
    fun long_line_or_dead_sidecar_stays_on_kokoro() {
        val mood = Mood(now = 0L)
        val long = EmotionPipeline.turn(
            say = "a".repeat(200),
            audio = Affect(Emotion.HAPPY, 0.9f),
            text = Affect(Emotion.HAPPY, 0.9f),
            mood = mood,
            now = 1_000L,
            sidecarOkAt = 900L,
        )
        assertEquals(TtsEngine.KOKORO, long.engine)

        val down = EmotionPipeline.turn(
            say = "salut",
            audio = Affect(Emotion.HAPPY, 0.9f),
            text = Affect(Emotion.HAPPY, 0.9f),
            mood = Mood(now = 0L),
            now = 30_000L,
            sidecarOkAt = 1_000L,
        )
        assertEquals(TtsEngine.KOKORO, down.engine)
    }
}
