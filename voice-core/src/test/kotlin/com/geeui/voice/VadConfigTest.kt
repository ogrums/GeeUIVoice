package com.geeui.voice

import com.geeui.voice.audio.EnergyVad
import com.geeui.voice.audio.VadConfig
import com.geeui.voice.audio.VadEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VadConfigTest {
    @Test
    fun deskDefaultsMatchTwentyMsFrames() {
        val cfg = VadConfig()
        assertEquals(800, cfg.threshold)
        assertEquals(4, cfg.toVad().let { 80 / 20 })
        assertEquals(30, 600 / 20)
        assertEquals(14, 280 / 20)
    }

    @Test
    fun extrasOverrideOnlyWhatIsSet() {
        val cfg = VadConfig.from(mapOf("vad_threshold" to "1200", "vad_hangover_ms" to "400"))
        assertEquals(1200, cfg.threshold)
        assertEquals(400, cfg.hangoverMs)
        assertEquals(80, cfg.startMs)
    }

    @Test
    fun aShortBlipIsDropped() {
        val vad = EnergyVad(threshold = 800, startFrames = 2, hangoverFrames = 2, minSpeechFrames = 8)
        val loud = ShortArray(320) { 5000 }
        val quiet = ShortArray(320)
        assertTrue(vad.push(loud) is VadEvent.None)
        assertTrue(vad.push(loud) is VadEvent.SpeechStart)
        repeat(2) { vad.push(quiet) }
        val end = vad.push(quiet)
        assertTrue(end is VadEvent.None)
    }

    @Test
    fun aRealPhraseEndsOnce() {
        val vad = VadConfig(threshold = 800, startMs = 40, hangoverMs = 60, minSpeechMs = 100).toVad()
        val loud = ShortArray(320) { 5000 }
        val quiet = ShortArray(320)
        var started = false
        var samples = 0
        repeat(3) { if (vad.push(loud) is VadEvent.SpeechStart) started = true }
        repeat(8) { vad.push(loud) }
        var ended = false
        repeat(5) {
            val ev = vad.push(quiet)
            if (ev is VadEvent.SpeechEnd) {
                ended = true
                samples = ev.pcm.size
            }
        }
        assertTrue(started)
        assertTrue(ended)
        assertTrue(samples > 320 * 8)
    }
}
