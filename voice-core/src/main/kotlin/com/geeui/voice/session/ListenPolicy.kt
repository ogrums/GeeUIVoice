package com.geeui.voice.session

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

    /** Use the Hi RUX path: only after the callback is known to work. */
    val useWake: Boolean
        get() = when (mode) {
            ListenMode.CONTINUOUS -> false
            ListenMode.WAKE, ListenMode.AUTO -> callbackAvailable
        }

    private var deadline: Long = 0L

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

        fun parseMode(raw: String?): ListenMode = when (raw?.trim()?.lowercase()) {
            "wake", "wakeup", "hi-rux", "hirux", "hi_rux" -> ListenMode.WAKE
            "continuous", "continous", "vad", "always" -> ListenMode.CONTINUOUS
            else -> ListenMode.AUTO
        }
    }
}
