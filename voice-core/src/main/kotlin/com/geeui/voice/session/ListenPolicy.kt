package com.geeui.voice.session

/** Outcome of a Lex speech callback, for a clear `wake accepted` / `wake ignored(reason)` log. */
data class WakeVerdict(val accepted: Boolean, val reason: String)

/**
 * When GeeUIVoice should hold the microphone.
 *
 * At rest in wake mode the mic is released so Lex can hear the stock
 * "Hi RUX" phrase. A wake event takes the mic for one conversation;
 * after [idleTimeoutMs] of idle the mic is given back. Continuous VAD
 * is the fallback when the speech callback is missing, or an explicit setting.
 */
enum class ListenMode {
    /** Wake if [callbackAvailable], otherwise continuous VAD. */
    AUTO,
    /** Same as [AUTO] once the callback result is known; prefers wake. */
    WAKE,
    /** Always hold the mic (previous behaviour). */
    CONTINUOUS,
}

class ListenPolicy(
    mode: ListenMode = ListenMode.AUTO,
    val idleTimeoutMs: Long = DEFAULT_IDLE_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var mode: ListenMode = mode
        private set
    var callbackAvailable: Boolean = false
        private set
    var resolved: Boolean = false
        private set
    var holdingMic: Boolean = mode == ListenMode.CONTINUOUS
        private set
    var wakeSource: WakeSource = WakeSource.HIRUX
        private set

    val hiruxEnabled: Boolean get() = wakeSource.usesHirux()
    val dobbyEnabled: Boolean get() = wakeSource.usesDobby()

    /**
     * Use a wake-word path (stock Hi RUX and/or Hey Dobby) instead of
     * continuous VAD. Hi RUX still needs [callbackAvailable]. Hey Dobby
     * does not: it has its own recorder.
     */
    val useWake: Boolean
        get() = when (mode) {
            ListenMode.CONTINUOUS -> false
            ListenMode.WAKE, ListenMode.AUTO -> callbackAvailable || dobbyEnabled
        }

    fun setWakeSource(next: WakeSource) {
        wakeSource = next
        if (resolved) {
            if (useWake) {
                if (!holdingMic) deadline = 0L
            } else {
                holdingMic = true
                deadline = 0L
            }
        }
    }

    private var deadline: Long = 0L
    /** Last time we sent `start_audio_service` to Lex. */
    private var lexResumedAt: Long = 0L
    /** True after an accepted wake, until [beginPostWakeTrim] starts the clock. */
    private var trimArmed: Boolean = false
    private var trimUntil: Long = 0L

    fun setMode(next: ListenMode) {
        mode = next
        if (!useWake && resolved) {
            holdingMic = true
            deadline = 0L
        } else if (!resolved && mode != ListenMode.CONTINUOUS) {
            holdingMic = false
        }
    }

    /**
     * Result of `registerSpeechCallback`. Until this is called, AUTO and WAKE
     * stay yielded so we do not steal the mic from Lex while we try to hook it.
     * A failed register falls back to continuous VAD.
     */
    fun onCallbackAvailable(ok: Boolean) {
        callbackAvailable = ok
        resolved = true
        if (useWake) {
            holdingMic = false
            deadline = 0L
        } else {
            holdingMic = true
            deadline = 0L
        }
    }

    /**
     * Stock wake event (`wakeup_status` / `wakeup_doa`). Takes the mic and
     * starts the idle timer. Returns false when wake mode is not active.
     */
    fun onWake(at: Long = clock()): Boolean {
        if (!useWake) return false
        holdingMic = true
        deadline = at + idleTimeoutMs
        return true
    }

    /**
     * Call when we send `start_audio_service` to Lex. For [POST_RESUME_IGNORE_MS],
     * a following `wakeup_status` is treated as the echo of that resume, not a
     * real "Hi RUX".
     */
    fun onLexResumed(at: Long = clock()) {
        lexResumedAt = at
    }

    /**
     * Decide whether a Lex speech callback is a real wake.
     * Only `wakeup_doa`, or `wakeup_status` whose data is `avatar.listening`,
     * can be accepted. `wakeup_status` is ignored for [POST_RESUME_IGNORE_MS]
     * after [onLexResumed]. [reason] is the token logged after `wake ignored(`.
     */
    fun considerWake(command: String, data: String, at: Long = clock()): WakeVerdict {
        val cmd = command.trim()
        val payload = data.trim()
        val isDoa = cmd.equals("wakeup_doa", ignoreCase = true)
        val isStatus = cmd.equals("wakeup_status", ignoreCase = true)
        if (!isDoa && !isStatus) {
            return WakeVerdict(false, "not-wake")
        }
        if (!hiruxEnabled) {
            return WakeVerdict(false, "hirux-off")
        }
        if (isStatus && !isListeningStatus(payload)) {
            return WakeVerdict(false, payload.ifBlank { "empty-status" })
        }
        if (isStatus && lexResumedAt > 0L && at - lexResumedAt < POST_RESUME_IGNORE_MS) {
            return WakeVerdict(false, "post-resume")
        }
        if (!onWake(at)) {
            return WakeVerdict(false, "wake-mode-off")
        }
        trimArmed = true
        trimUntil = 0L
        return WakeVerdict(true, "accepted")
    }

    /**
     * Vosk « Hey Dobby » (or an equivalent local wake). Same mic-holding
     * path as a stock Hi RUX. Returns false when Dobby is not enabled or
     * wake mode is off.
     */
    fun acceptLocalWake(at: Long = clock()): WakeVerdict {
        if (!dobbyEnabled) {
            return WakeVerdict(false, "dobby-off")
        }
        if (!onWake(at)) {
            return WakeVerdict(false, "wake-mode-off")
        }
        trimArmed = true
        trimUntil = 0L
        return WakeVerdict(true, "accepted")
    }

    /**
     * Start dropping frames for [POST_WAKE_TRIM_MS], once the recorder is
     * actually running after an accepted wake. A no-op if no wake is pending.
     */
    fun beginPostWakeTrim(at: Long = clock()) {
        if (!trimArmed) return
        trimArmed = false
        trimUntil = at + POST_WAKE_TRIM_MS
    }

    /** True while the post-wake beep/window should be discarded, not sent to the VAD. */
    fun shouldDropFrame(at: Long = clock()): Boolean = trimUntil > 0L && at < trimUntil

    private fun isListeningStatus(data: String): Boolean =
        data.equals("avatar.listening", ignoreCase = true) ||
            data.contains("avatar.listening", ignoreCase = true)

    /** Call when a turn ends (idle) so the rest period starts from now. */
    fun onTurnEnded(at: Long = clock()) {
        if (!useWake || !holdingMic) return
        deadline = at + idleTimeoutMs
    }

    /**
     * Periodic check. [busy] is true while a turn, TTS, or a WAV test is
     * running: do not yield mid-conversation. Returns true if the mic was
     * just released.
     */
    fun onTick(busy: Boolean = false, at: Long = clock()): Boolean {
        if (busy || !useWake || !holdingMic) return false
        if (at >= deadline && deadline > 0L) {
            holdingMic = false
            deadline = 0L
            return true
        }
        return false
    }

    fun shouldHoldMic(): Boolean {
        if (!resolved && mode != ListenMode.CONTINUOUS) return false
        if (!useWake) return true
        return holdingMic
    }

    companion object {
        const val DEFAULT_IDLE_MS = 12_000L
        /** Ignore `wakeup_status` after we ourselves resume Lex's audio service. */
        const val POST_RESUME_IGNORE_MS = 2_000L
        /** Drop this much audio after taking the mic on wake (system beep). */
        const val POST_WAKE_TRIM_MS = 350L

        fun parseMode(raw: String?): ListenMode = when (raw?.trim()?.lowercase()) {
            "wake", "wakeup", "hi-rux", "hirux", "hi_rux" -> ListenMode.WAKE
            "continuous", "continous", "vad", "always" -> ListenMode.CONTINUOUS
            else -> ListenMode.AUTO
        }
    }
}
