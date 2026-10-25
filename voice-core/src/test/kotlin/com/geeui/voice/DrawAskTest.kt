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
        assertEquals("un renard", asked)
        assertEquals(3, shown)
        assertEquals("Je te le dessine.", say)
        assertEquals(1, tts.spoken.size)
    }

    @Test
    fun imageBodyDecodesPngBytes() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val raw = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""
        assertTrue(decodeImageBody(raw).contentEquals(png))
        assertEquals(0, decodeImageBody("{}").size)
    }
}
