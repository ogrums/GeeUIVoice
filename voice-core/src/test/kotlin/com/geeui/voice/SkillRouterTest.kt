package com.geeui.voice

import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.engine.FixedChat
import com.geeui.voice.session.VoiceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillRouterTest {
    @Test
    fun forwardWalkUsesValidatedMotion() {
        val bus = RecordingBus()
        val session = VoiceSession(bus, EchoTts(), FixedChat())
        session.onUserText("avance")
        assertEquals(listOf("motion 98 3 2"), bus.events)
    }

    @Test
    fun chargingBlocksMotionAndSpeaks() {
        val bus = RecordingBus()
        val session = VoiceSession(bus, EchoTts(), FixedChat(), charging = { true })
        session.onUserText("walk forward")
        assertTrue(bus.events.single().startsWith("tts "))
    }

    @Test
    fun unknownTextGoesToChat() {
        val tts = EchoTts()
        val session = VoiceSession(RecordingBus(), tts, FixedChat())
        val answer = session.onUserText("quelle heure est-il")
        assertTrue(answer.contains("quelle heure"))
        assertEquals(1, tts.spoken.size)
    }
}
