package com.geeui.voice

import com.geeui.voice.audio.NoiseGate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class NoiseGateTest {
    @Test
    fun autoGateClosesOnSteadyRoomNoise() {
        val g = NoiseGate()
        repeat(600) { g.pass(90.0) } // 1 min of fan noise at 90 RMS
        assertFalse(g.pass(90.0))
        assertTrue(g.gate in 170..190)
        assertTrue(g.pass(600.0)) // speech well above the floor still passes
    }

    @Test
    fun autoGateNeverBelowOldFixedGate() {
        val g = NoiseGate()
        repeat(600) { g.pass(5.0) }
        assertEquals(50, g.gate)
    }

    @Test
    fun speechBurstDoesNotRaiseTheGateMuch() {
        val g = NoiseGate()
        repeat(300) { g.pass(40.0) }
        repeat(15) { g.pass(2_000.0) } // 1.5 s of « Hey Dobby »
        assertTrue(g.gate < 450)
        assertTrue(g.pass(500.0))
    }

    @Test
    fun capKeepsWakeReachableInLoudRoom() {
        val g = NoiseGate()
        repeat(3_000) { g.pass(1_000.0) }
        assertEquals(300, g.gate)
    }

    @Test
    fun fixedValueKeepsOldBehaviour() {
        val g = NoiseGate.parse("50")
        repeat(100) { g.pass(200.0) }
        assertEquals(50, g.gate)
        assertTrue(g.pass(60.0))
    }
}

class IdleVideoTest {
    @Test
    fun parsesSettings() {
        assertEquals(com.geeui.voice.bus.IdleVideo.LOOP, com.geeui.voice.bus.IdleVideo.parse(""))
        assertEquals(com.geeui.voice.bus.IdleVideo.LOOP, com.geeui.voice.bus.IdleVideo.parse("loop"))
        assertEquals(com.geeui.voice.bus.IdleVideo.ONCE, com.geeui.voice.bus.IdleVideo.parse("once"))
        assertEquals(30, com.geeui.voice.bus.IdleVideo.parse("30").replaySec)
        assertEquals(10, com.geeui.voice.bus.IdleVideo.parse("2").replaySec)
    }
}
