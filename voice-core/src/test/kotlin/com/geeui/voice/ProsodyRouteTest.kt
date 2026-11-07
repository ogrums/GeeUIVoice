package com.geeui.voice

import com.geeui.voice.engine.ClipTts
import com.geeui.voice.engine.CosyTts
import com.geeui.voice.engine.PlannedTts
import com.geeui.voice.engine.TtsPlan
import com.geeui.voice.engine.speedField
import com.geeui.voiceemo.Prosody
import com.geeui.voiceemo.TtsEngine
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProsodyRouteTest {
    @Test
    fun speedFieldOnlyWhenNotOne() {
        assertEquals("", speedField(1f))
        assertEquals(",\"speed\":1.080", speedField(1.08f))
    }

    @Test
    fun speedFieldIgnoresFrenchLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals(",\"speed\":0.900", speedField(0.9f))
        } finally {
            Locale.setDefault(old)
        }
    }

    private class FakeKokoro(private val plan: TtsPlan) : ClipTts {
        override var lastAudio = ByteArray(0)
        override var speedApplied = false
        override fun speak(text: String, language: String) {
            lastAudio = byteArrayOf(1)
            speedApplied = plan.prosody.speed != 1f
        }
        override fun stop() = Unit
    }

    @Test
    fun kokoroAppliesSpeedServerSideCosyDoesNot() {
        val plan = TtsPlan()
        plan.prosody = Prosody(speed = 1.08f, pitch = 1.06f)
        val cosy = CosyTts(plan) { _, _ -> byteArrayOf(2) }
        val planned = PlannedTts(plan, FakeKokoro(plan), cosy)
        plan.engine = TtsEngine.KOKORO
        planned.speak("bonjour", "fr")
        assertTrue(planned.speedApplied)
        plan.engine = TtsEngine.COSYVOICE
        planned.speak("bonjour", "fr")
        assertFalse(planned.speedApplied)
    }
}
