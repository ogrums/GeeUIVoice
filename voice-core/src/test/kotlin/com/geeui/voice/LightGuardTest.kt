package com.geeui.voice

import com.geeui.voice.bus.HermesLight
import com.geeui.voice.bus.LightGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LightGuardTest {
    @Test fun repeatedOnDoesNotExtend() {
        val g = LightGuard(maxOnMs = 1_000)
        g.lit(true, 1)
        g.lit(true, 900)
        assertFalse(g.expired(1_000))
        assertTrue(g.expired(1_001))
        g.lit(false, 1_100)
        assertFalse(g.expired(5_000))
        g.lit(true, 6_000)
        assertFalse(g.expired(6_500))
    }

    @Test fun hermesLightStopsAfterHold() {
        val h = HermesLight(holdMs = 1_000)
        assertTrue(h.want(1, 0))
        assertTrue(h.want(1, 999))
        assertFalse(h.want(1, 1_000))
        assertFalse(h.want(1, 50_000))
        // a new result lights it again
        assertTrue(h.want(2, 60_000))
        // sidecar unreachable: off, and back reachable with nothing new: still off
        assertFalse(h.want(null, 60_100))
        assertFalse(h.want(2, 61_500))
        // read, then a new one
        assertFalse(h.want(0, 70_000))
        assertTrue(h.want(1, 80_000))
    }
}
