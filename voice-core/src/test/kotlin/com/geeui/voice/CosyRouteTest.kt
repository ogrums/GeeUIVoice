package com.geeui.voice

import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.ClipTts
import com.geeui.voice.engine.CosyTts
import com.geeui.voice.engine.FixedChat
import com.geeui.voice.engine.PlannedTts
import com.geeui.voice.engine.TtsPlan
import com.geeui.voice.session.VoiceSession
import com.geeui.voiceemo.Mood
import com.geeui.voiceemo.TtsEngine
import com.geeui.voiceemo.TtsRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CosyRouteTest {
    @Test
    fun bodyNamesTheCosyVoice3Model() {
        val raw = CosyTts.body("bonjour", "happy")
        assertTrue(raw.contains("Fun-CosyVoice3-0.5B-2512"))
        assertTrue(raw.contains("\"voice\":\"happy\""))
        assertTrue(raw.contains("\"response_format\":\"wav\""))
    }

    @Test
    fun happyLineWithALiveSidecarUsesCosy() {
        val plan = TtsPlan()
        val kokoro = MemoryClip()
        val cosy = MemoryClip()
        val bus = RecordingBus()
        val session = VoiceSession(
            bus,
            PlannedTts(plan, kokoro, cosy),
            FixedChat(),
            mood = Mood(now = 0),
            plan = plan,
            now = { 1_000L },
            sidecarOkAt = { 1_000L },
        )
        session.onUserText("je suis content")
        assertEquals(TtsEngine.COSYVOICE, plan.engine)
        assertEquals("happy", plan.emotion)
        assertEquals(1, cosy.calls)
        assertEquals(0, kokoro.calls)
        assertTrue(bus.events.contains("ears 3 2 250 60"))
        assertTrue(bus.events.contains("light 6"))
        assertTrue(bus.events.contains("motion 77 1 3"))
        assertTrue(bus.events.contains("face h0006"))
    }

    @Test
    fun longAnswerIsClippedBeforeTheCosyPost() {
        val plan = TtsPlan()
        plan.engine = TtsEngine.COSYVOICE
        plan.emotion = "sad"
        var sent = ""
        CosyTts(plan) { text, _ ->
            sent = text
            byteArrayOf(1)
        }.speak("Ceci est une phrase assez longue pour le decoupage. " + "b".repeat(200), "fr")
        assertTrue(sent.length <= TtsRoute.MAX_CHARS)
        assertTrue(sent.endsWith("."))
    }

    @Test
    fun emptyCosyClipFallsBackToKokoro() {
        val plan = TtsPlan()
        plan.engine = TtsEngine.COSYVOICE
        val kokoro = MemoryClip(byteArrayOf(1))
        val cosy = MemoryClip(ByteArray(0))
        PlannedTts(plan, kokoro, cosy).speak("salut", "fr")
        assertEquals(1, kokoro.calls)
    }

    @Test
    fun plainTalkStaysOnKokoroAndDoesNotStep() {
        val plan = TtsPlan()
        val kokoro = MemoryClip()
        val cosy = MemoryClip()
        val bus = RecordingBus()
        val session = VoiceSession(
            bus,
            PlannedTts(plan, kokoro, cosy),
            FixedChat(),
            mood = Mood(now = 0),
            plan = plan,
            now = { 1_000L },
            sidecarOkAt = { 1_000L },
        )
        session.onUserText("bonjour")
        assertEquals(TtsEngine.KOKORO, plan.engine)
        assertEquals(0, cosy.calls)
        assertEquals(1, kokoro.calls)
        assertTrue(bus.events.none { it.startsWith("motion") })
        assertTrue(bus.events.contains("light off"))
    }

    @Test
    fun skillDoesNotCallCosy() {
        val plan = TtsPlan()
        val cosy = MemoryClip()
        val session = VoiceSession(
            RecordingBus(),
            PlannedTts(plan, MemoryClip(), cosy),
            FixedChat(),
            mood = Mood(now = 0),
            plan = plan,
            now = { 1_000L },
            sidecarOkAt = { 1_000L },
        )
        session.onUserText("avance")
        assertEquals(0, cosy.calls)
        assertEquals(TtsEngine.KOKORO, plan.engine)
    }
}

private class MemoryClip(private val bytes: ByteArray = byteArrayOf(1, 2)) : ClipTts {
    var calls = 0
    override var lastAudio: ByteArray = ByteArray(0)
        private set

    override fun speak(text: String, language: String) {
        calls += 1
        lastAudio = bytes
    }

    override fun stop() {
        lastAudio = ByteArray(0)
    }
}
