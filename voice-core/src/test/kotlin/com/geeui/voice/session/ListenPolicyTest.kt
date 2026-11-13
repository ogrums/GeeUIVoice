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

    @Test
    fun onlyListeningStatusOrDoaIsAWake() {
        val p = policy()
        p.onCallbackAvailable(true)
        val silence = p.considerWake("wakeup_status", "avatar.silence")
        assertFalse(silence.accepted)
        assertEquals("avatar.silence", silence.reason)
        assertFalse(p.shouldHoldMic())

        val other = p.considerWake("something_else", "avatar.listening")
        assertFalse(other.accepted)
        assertEquals("not-wake", other.reason)

        val listening = p.considerWake("wakeup_status", "avatar.listening")
        assertTrue(listening.accepted)
        assertEquals("accepted", listening.reason)
        assertTrue(p.shouldHoldMic())
    }

    @Test
    fun wakeupDoaIsAcceptedWithoutListeningData() {
        val p = policy()
        p.onCallbackAvailable(true)
        val v = p.considerWake("wakeup_doa", "left")
        assertTrue(v.accepted)
        assertTrue(p.shouldHoldMic())
    }

    @Test
    fun wakeupStatusIsIgnoredForTwoSecondsAfterLexResume() {
        val p = policy()
        p.onCallbackAvailable(true)
        p.onLexResumed()
        now += 1_999
        val early = p.considerWake("wakeup_status", "avatar.listening")
        assertFalse(early.accepted)
        assertEquals("post-resume", early.reason)
        assertFalse(p.shouldHoldMic())
        now += 1
        val late = p.considerWake("WAKEUP_STATUS", "avatar.listening")
        assertTrue(late.accepted)
        assertTrue(p.shouldHoldMic())
    }

    @Test
    fun wakeupDoaIsStillAcceptedDuringTheResumeWindow() {
        val p = policy()
        p.onCallbackAvailable(true)
        p.onLexResumed()
        now += 300
        val v = p.considerWake("wakeup_doa", "")
        assertTrue(v.accepted)
        assertTrue(p.shouldHoldMic())
    }

    @Test
    fun considerWakeReportsWhenWakeModeIsOff() {
        val p = policy(ListenMode.CONTINUOUS)
        p.onCallbackAvailable(true)
        val v = p.considerWake("wakeup_status", "avatar.listening")
        assertFalse(v.accepted)
        assertEquals("wake-mode-off", v.reason)
    }

    @Test
    fun postWakeFramesAreDroppedForTheTrimWindow() {
        val p = policy()
        p.onCallbackAvailable(true)
        assertFalse(p.shouldDropFrame())
        p.considerWake("wakeup_status", "avatar.listening")
        // Trim starts only when the recorder is actually running.
        assertFalse(p.shouldDropFrame())
        p.beginPostWakeTrim()
        assertTrue(p.shouldDropFrame())
        now += ListenPolicy.POST_WAKE_TRIM_MS - 1
        assertTrue(p.shouldDropFrame())
        now += 1
        assertFalse(p.shouldDropFrame())
    }

    @Test
    fun beginPostWakeTrimIsANoOpWithoutAWake() {
        val p = policy()
        p.onCallbackAvailable(true)
        p.beginPostWakeTrim()
        assertFalse(p.shouldDropFrame())
        now += 10
        assertFalse(p.shouldDropFrame())
    }
}
