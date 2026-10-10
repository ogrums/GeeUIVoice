package com.geeui.voice

import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.engine.FixedChat
import com.geeui.voice.session.VoiceSession
import com.geeui.voiceemo.Emotion
import com.geeui.voiceemo.EmotionTurn
import com.geeui.voiceemo.Mood
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmotionFusionTest {
    @Test
    fun skillDoesNotApplyAPose() {
        val mood = Mood(now = 0)
        val turns = mutableListOf<EmotionTurn>()
        val session = VoiceSession(
            RecordingBus(),
            EchoTts(),
            FixedChat(),
            mood = mood,
            onEmotion = { turns += it },
            now = { 0L },
        )
        session.onUserText("avance")
        assertTrue(turns.isEmpty())
        assertEquals(Emotion.NEUTRAL, mood.snapshot(0L).emotion)
    }

    @Test
    fun happyWordSelectsTheHappyFace() {
        val mood = Mood(now = 0)
        var face = ""
        val session = VoiceSession(
            RecordingBus(),
            EchoTts(),
            FixedChat(),
            mood = mood,
            onEmotion = { face = it.pose.faceId },
            now = { 1_000L },
        )
        session.onUserText("je suis content")
        assertEquals("h0006", face)
    }
}
