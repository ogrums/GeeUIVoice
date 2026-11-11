package com.geeui.voice

import com.geeui.voice.audio.EnergyVad
import com.geeui.voice.audio.VadConfig
import com.geeui.voice.audio.VadEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VadPreRollTest {
    private fun frame(value: Int) = ShortArray(320) { value.toShort() }
    private val quiet = frame(0)
    private val soft = frame(300)
    private val loud = frame(5000)

    private fun end(vad: EnergyVad, frames: List<ShortArray>): ShortArray? {
        for (f in frames) {
            val ev = vad.push(f)
            if (ev is VadEvent.SpeechEnd) return ev.pcm
        }
        return null
    }

    @Test
    fun softOnsetBeforeTheStartIsKept() {
        val vad = EnergyVad(threshold = 800, startFrames = 2, hangoverFrames = 3, minSpeechFrames = 4, preRollFrames = 5)
        val pcm = end(vad, List(10) { quiet } + List(3) { soft } + List(6) { loud } + List(3) { quiet })!!
        // 5 pre-roll frames (2 quiet + 3 soft) + 6 loud + 3 quiet tail.
        assertEquals(320 * 14, pcm.size)
        assertEquals(0.toShort(), pcm[0])
        assertEquals(300.toShort(), pcm[320 * 2])
        assertEquals(5000.toShort(), pcm[320 * 5])
    }

    @Test
    fun withoutPreRollTheSoftOnsetIsLost() {
        val vad = EnergyVad(threshold = 800, startFrames = 2, hangoverFrames = 3, minSpeechFrames = 4)
        val pcm = end(vad, List(3) { soft } + List(6) { loud } + List(3) { quiet })!!
        assertEquals(5000.toShort(), pcm[0])
        assertEquals(320 * 9, pcm.size)
    }

    @Test
    fun preRollDoesNotCountAsSpeech() {
        val vad = EnergyVad(threshold = 800, startFrames = 2, hangoverFrames = 2, minSpeechFrames = 8, preRollFrames = 20)
        assertEquals(null, end(vad, List(30) { soft } + List(2) { loud } + List(3) { quiet }))
    }

    @Test
    fun resetDropsTheEchoBeforeTheMicReopens() {
        val vad = EnergyVad(threshold = 800, startFrames = 2, hangoverFrames = 3, minSpeechFrames = 4, preRollFrames = 5)
        repeat(5) { vad.push(soft) }
        vad.reset()
        val pcm = end(vad, List(6) { loud } + List(3) { quiet })!!
        assertEquals(320 * 9, pcm.size)
    }

    @Test
    fun configDefaultsToFourHundredMsAndReadsTheExtra() {
        assertEquals(400, VadConfig().preRollMs)
        assertEquals(250, VadConfig.from(mapOf("vad_preroll_ms" to "250")).preRollMs)
        val vad = VadConfig(threshold = 800, startMs = 40, hangoverMs = 60, minSpeechMs = 80).toVad()
        val pcm = end(vad, List(30) { quiet } + List(5) { loud } + List(3) { quiet })!!
        assertTrue(pcm.size == 320 * (20 + 5 + 3), "400 ms pre-roll = 20 frames, got ${pcm.size / 320}")
    }
}
