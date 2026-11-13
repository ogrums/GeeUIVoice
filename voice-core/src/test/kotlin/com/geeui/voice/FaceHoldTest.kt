package com.geeui.voice

import com.geeui.voice.bus.PoseTiming
import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.engine.FixedChat
import com.geeui.voice.engine.StreamingChat
import com.geeui.voice.session.VoiceSession
import com.geeui.voice.session.WavTestAudio
import com.geeui.voice.session.WavTestTurn
import com.geeui.voice.session.faceDuringSpeech
import com.geeui.voice.audio.pcm16ToWav
import com.geeui.voice.engine.SpeechToText
import com.geeui.voice.engine.TimingChat
import com.geeui.voice.engine.TimingTts
import com.geeui.voiceemo.Affect
import com.geeui.voiceemo.Emotion
import com.geeui.voiceemo.EmotionFusion
import com.geeui.voiceemo.Mood
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaceHoldTest {
    @Test
    fun emotionFaceIsHeldThenRestedAndHookSeesItDuringSpeech() {
        val bus = RecordingBus(clock = { 1_000L })
        var waited = 0L
        val session = VoiceSession(
            bus,
            EchoTts(),
            TagChat("[angry] Non."),
            mood = Mood(now = 0),
            onEmotion = {},
            now = { 1_000L },
            fusion = EmotionFusion(clock = { 1_000L }),
            audioAffect = { Affect(Emotion.ANGRY, 0.99f) },
            pause = { waited = it },
        )
        session.onUserText("ça va pas")
        assertEquals(PoseTiming.REST_AFTER_MS, waited)
        assertEquals(800L, PoseTiming.REST_AFTER_MS)
        assertTrue(bus.events.contains("face h0001"), bus.events.toString())
        assertEquals(
            listOf("face h0059", "light off", "ears 3 1 400 15", "motion 0 1 1"),
            bus.events.takeLast(4),
        )
        assertEquals("h0001", faceDuringSpeech(bus.faces))
        // The last event is the rest, so a snapshot of VoiceHud.face after the turn would be h0059.
        assertEquals("h0059", bus.faces.last().face)
    }

    @Test
    fun happyTagAndSmileShowsHappyFaceEvenWithEmptySpeech() {
        val bus = RecordingBus()
        val tts = EchoTts()
        val session = VoiceSession(
            bus,
            tts,
            TagChat("[happy] \uD83D\uDE04"),
            mood = Mood(now = 0),
            now = { 1_000L },
            fusion = EmotionFusion(clock = { 1_000L }),
            pause = {},
        )
        val said = session.onUserText("bonjour")
        assertEquals("", said)
        assertTrue(tts.spoken.isEmpty())
        assertTrue(bus.events.contains("face h0020"), bus.events.toString())
        assertEquals("h0020", faceDuringSpeech(bus.faces))
    }

    @Test
    fun wavHookRecordsFaceEventsInResultJson() {
        val bus = RecordingBus(clock = { 42L })
        val chat = TimingChat(TagChat("[sad] Oh."))
        val tts = TimingTts(EchoTts())
        val session = VoiceSession(
            bus, tts, chat,
            mood = Mood(now = 0),
            now = { 1_000L },
            fusion = EmotionFusion(clock = { 1_000L }),
            audioAffect = { Affect(Emotion.SAD, 0.99f) },
            pause = {},
        )
        val turn = WavTestTurn(
            stt = object : SpeechToText {
                override fun transcribe(pcm16le: ByteArray, sampleRate: Int) = "oh"
            },
            session = session,
            chat = chat,
            tts = tts,
        )
        val result = turn.run(
            pcm16ToWav(ShortArray(320) { 1000 }, 16_000),
            path = "/sdcard/geeui/oh.wav",
            faces = { bus.faces.toList() },
            audio = { WavTestAudio("sad", 0.99, raw = "sad", rawConfidence = 0.99) },
        )
        assertEquals("h0119", result.faceDuringSpeech)
        assertEquals("h0119", result.tags.face)
        assertTrue(result.faces.any { it.face == "h0119" })
        assertTrue(result.faces.last().face == "h0059")
        val json = result.toJson()
        assertTrue(json.contains("\"face_during_speech\": \"h0119\""), json)
        assertTrue(json.contains("\"face\": \"h0119\""), json)
        assertTrue(json.contains("\"faces\":"), json)
        assertTrue(json.contains("\"raw\": \"sad\""), json)
        assertTrue(json.contains("h0119"), json)
        assertTrue(json.contains("\"at_ms\":42"), json)
    }
}

private class TagChat(private val raw: String) : Chat, StreamingChat {
    override fun reply(userText: String) = raw
    override fun stream(userText: String, onDelta: (String) -> Unit) = onDelta(raw)
}
