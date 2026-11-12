package com.geeui.voice.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenPolicyTest {
    private var now = 1_000L
    private fun policy(mode: ListenMode = ListenMode.AUTO): ListenPolicy =
        ListenPolicy(mode, idleTimeoutMs = 12_000, clock = { now })

    @Test
    fun parseModeReadsSettings() {
        assertEquals(ListenMode.AUTO, ListenPolicy.parseMode(null))
        assertEquals(ListenMode.AUTO, ListenPolicy.parseMode(""))
        assertEquals(ListenMode.AUTO, ListenPolicy.parseMode("auto"))
        assertEquals(ListenMode.WAKE, ListenPolicy.parseMode("WAKE"))
        assertEquals(ListenMode.WAKE, ListenPolicy.parseMode("hi-rux"))
        assertEquals(ListenMode.CONTINUOUS, ListenPolicy.parseMode("continuous"))
        assertEquals(ListenMode.CONTINUOUS, ListenPolicy.parseMode("vad"))
    }

    @Test
    fun autoStartsYieldedUntilTheCallbackIsKnown() {
        val p = policy()
        assertFalse(p.resolved)
        assertFalse(p.useWake)
        assertFalse(p.shouldHoldMic())
        assertFalse(p.holdingMic)
    }

    @Test
    fun autoFallsBackToContinuousWhenRegisterFails() {
        val p = policy()
        p.onCallbackAvailable(false)
        assertFalse(p.useWake)
        assertTrue(p.shouldHoldMic())
        assertTrue(p.holdingMic)
        assertFalse(p.onWake())
        now += 20_000
        assertFalse(p.onTick())
        assertTrue(p.shouldHoldMic())
    }

    @Test
    fun autoUsesWakeWhenTheCallbackIsAvailable() {
        val p = policy()
        p.onCallbackAvailable(true)
        assertTrue(p.useWake)
        assertFalse(p.shouldHoldMic())
        assertFalse(p.holdingMic)
    }

    @Test
    fun wakeTakesTheMicUntilIdleTimeout() {
        val p = policy()
        p.onCallbackAvailable(true)
        assertTrue(p.onWake())
        assertTrue(p.shouldHoldMic())
        now += 11_999
        assertFalse(p.onTick())
        assertTrue(p.shouldHoldMic())
        now += 1
        assertTrue(p.onTick())
        assertFalse(p.shouldHoldMic())
    }

    @Test
    fun aFinishedTurnRestartsTheIdleTimer() {
        val p = policy()
        p.onCallbackAvailable(true)
        p.onWake()
        now += 10_000
        p.onTurnEnded()
        now += 11_999
        assertFalse(p.onTick())
        assertTrue(p.shouldHoldMic())
        now += 1
        assertTrue(p.onTick())
        assertFalse(p.shouldHoldMic())
    }

    @Test
    fun busyTickDoesNotYieldMidTurn() {
        val p = policy()
        p.onCallbackAvailable(true)
        p.onWake()
        now += 20_000
        assertFalse(p.onTick(busy = true))
        assertTrue(p.shouldHoldMic())
        assertTrue(p.onTick(busy = false))
        assertFalse(p.shouldHoldMic())
    }

    @Test
    fun continuousIgnoresWakeAndTimeout() {
        val p = policy(ListenMode.CONTINUOUS)
        assertTrue(p.shouldHoldMic())
        p.onCallbackAvailable(true)
        assertFalse(p.useWake)
        assertTrue(p.shouldHoldMic())
        assertFalse(p.onWake())
        now += 30_000
        assertFalse(p.onTick())
        assertTrue(p.shouldHoldMic())
    }

    @Test
    fun explicitWakeStillNeedsTheCallback() {
        val p = policy(ListenMode.WAKE)
        assertFalse(p.shouldHoldMic())
        p.onCallbackAvailable(false)
        // No hook: fall back to continuous so the robot still listens.
        assertFalse(p.useWake)
        assertTrue(p.shouldHoldMic())
    }

    @Test
    fun secondWakeExtendsTheTimer() {
        val p = policy()
        p.onCallbackAvailable(true)
        p.onWake()
        now += 8_000
        assertTrue(p.onWake())
        now += 11_999
        assertFalse(p.onTick())
        now += 1
        assertTrue(p.onTick())
    }
}
