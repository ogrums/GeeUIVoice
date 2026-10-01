package com.geeui.voice

import com.geeui.voice.audio.EnergyVad
import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.engine.FixedChat
import com.geeui.voice.engine.SpeechToText
import com.geeui.voice.session.LiveTurn
import com.geeui.voice.session.VoiceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTurnTest {
    @Test
    fun silenceThenSpeechThenSilenceRunsOneTurn() {
        val bus = RecordingBus()
        val tts = EchoTts()
        val session = VoiceSession(bus, tts, FixedChat())
        val stt = object : SpeechToText {
            override fun transcribe(pcm16le: ByteArray, sampleRate: Int) = "avance"
        }
        val live = LiveTurn(EnergyVad(), stt, tts, session)
        val loud = ShortArray(320) { 4000 }
        val quiet = ShortArray(320)
        repeat(5) { live.onFrame(quiet) }
        repeat(15) { live.onFrame(loud) }
        repeat(30) { live.onFrame(quiet) }
        assertEquals(listOf("motion 98 3 2"), bus.events)
        assertTrue(tts.spoken.isEmpty())
    }

    @Test
    fun speechStartStopsTts() {
        val tts = EchoTts()
        var stopped = false
        val stopping = object : com.geeui.voice.engine.TextToSpeech {
            override fun speak(text: String, language: String) = tts.speak(text, language)
            override fun stop() { stopped = true }
        }
        val session = VoiceSession(RecordingBus(), stopping, FixedChat())
        session.onUserText("bonjour")
        val live = LiveTurn(
            EnergyVad(startFrames = 1),
            object : SpeechToText {
                override fun transcribe(pcm16le: ByteArray, sampleRate: Int) = ""
            },
            stopping,
            session,
        )
        live.onFrame(ShortArray(320) { 4000 })
        assertTrue(stopped)
    }
}
