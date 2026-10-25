package com.geeui.voice

import com.geeui.voice.engine.EchoTts
import com.geeui.voice.engine.decodeImageBody
import com.geeui.voice.session.DrawAsk
import com.geeui.voice.session.VoiceSession
import com.geeui.voice.bus.RecordingBus
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrawAskTest {
    @Test
    fun frenchDrawKeepsTheSubject() {
        assertEquals("un chat rouge", DrawAsk.subject("Dessine moi un chat rouge"))
        assertEquals("un soleil", DrawAsk.subject("dessine-moi un soleil."))
        assertEquals("un chat", DrawAsk.subject("peux-tu dessiner un chat"))
        assertEquals("un chat", DrawAsk.subject("s'il te plaît dessinez-moi un chat"))
        assertEquals("un chat", DrawAsk.subject("desine moi un chat"))
        assertNull(DrawAsk.subject("bonjour"))
        assertNull(DrawAsk.subject("dessine"))
    }

    @Test
    fun drawTurnPaintsAndDoesNotChat() {
        val tts = EchoTts()
        var asked = ""
        var shown = 0
        val session = VoiceSession(
            RecordingBus(),
            tts,
            com.geeui.voice.engine.FixedChat(),
            paint = {
                asked = it
                byteArrayOf(1, 2, 3)
            },
            onPicture = { shown = it.size },
        )
        val say = session.onUserText("dessine moi un renard")
        val deadline = System.currentTimeMillis() + 1_000
        while (shown == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("un renard", asked)
        assertEquals(3, shown)
        assertEquals("Je te le dessine.", say)
        assertEquals(1, tts.spoken.size)
    }

    @Test
    fun followUpStaysAnImageUntilTheHoldEnds() {
        val tts = EchoTts()
        var asked = ""
        var held = true
        var busy = false
        val session = VoiceSession(
            RecordingBus(),
            tts,
            com.geeui.voice.engine.FixedChat(),
            paint = {
                asked = it
                byteArrayOf(1)
            },
            drawHeld = { held },
            drawBusy = { busy },
        )
        session.onUserText("un chien bleu")
        val deadline = System.currentTimeMillis() + 1_000
        while (asked.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("un chien bleu", asked)
        busy = true
        assertEquals("", session.onUserText("et après"))
        assertEquals("un chien bleu", asked)
        held = false
        busy = false
        val chat = session.onUserText("bonjour")
        assertTrue(chat.contains("bonjour"))
    }

    @Test
    fun imageBodyDecodesPngBytes() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val raw = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        assertTrue(decodeImageBody(raw).contentEquals(png))
        assertEquals(0, decodeImageBody("{}").size)
    }
}
