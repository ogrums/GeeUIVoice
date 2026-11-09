package com.geeui.voice

import com.geeui.voice.engine.ClipTts
import com.geeui.voice.engine.FallbackTts
import com.geeui.voice.engine.PhononTts
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhononTest {
    @Test
    fun bodyHasVoiceMp3AndNoSpeed() {
        val body = PhononTts.body("Bonjour \"toi\"", "Hélène")
        assertEquals(
            """{"model":"tts-1","input":"Bonjour \"toi\"","voice":"Hélène","response_format":"mp3"}""",
            body,
        )
        assertFalse(body.contains("speed"))
    }

    @Test
    fun blankVoiceIsDefault() {
        assertTrue(PhononTts.body("a", " ".trim()).contains("\"voice\":\"default\""))
    }

    @Test
    fun phononLeavesSpeedToThePlayer() {
        val tts = PhononTts { byteArrayOf(9) }
        tts.speak("salut", "fr")
        assertContentEquals(byteArrayOf(9), tts.lastAudio)
        assertFalse(tts.speedApplied)
    }

    @Test
    fun fallsBackToKokoroOnEmptyErrorOrJson() {
        val kokoro = Fixed(byteArrayOf(1), speed = true)
        for (primary in listOf<ClipTts>(
            PhononTts { ByteArray(0) },
            PhononTts { error("down") },
            PhononTts { "{\"error\":{}}".toByteArray() },
        )) {
            val tts = FallbackTts(primary, kokoro)
            tts.speak("x", "fr")
            assertContentEquals(byteArrayOf(1), tts.lastAudio)
            assertTrue(tts.speedApplied)
        }
    }

    @Test
    fun usesPhononWhenItAnswers() {
        val tts = FallbackTts(PhononTts { byteArrayOf(7) }, Fixed(byteArrayOf(1), speed = true))
        tts.speak("x", "fr")
        assertContentEquals(byteArrayOf(7), tts.lastAudio)
        assertFalse(tts.speedApplied)
    }

    private class Fixed(private val bytes: ByteArray, private val speed: Boolean) : ClipTts {
        override var lastAudio = ByteArray(0)
        override val speedApplied get() = speed
        override fun speak(text: String, language: String) { lastAudio = bytes }
        override fun stop() { lastAudio = ByteArray(0) }
    }
}
