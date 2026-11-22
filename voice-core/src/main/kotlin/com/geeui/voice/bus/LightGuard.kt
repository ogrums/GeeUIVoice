package com.geeui.voice.bus

/**
 * Nothing leaves the antenna light on forever. [lit] is fed by every antenna command; the
 * first « on » starts the clock (repeated « on » do not extend it), « off » stops it.
 * At rest, once [expired], the loop turns the light off.
 */
class LightGuard(private val maxOnMs: Long = MAX_ON_MS) {
    @Volatile var onSince = 0L
        private set

    fun lit(on: Boolean, now: Long) {
        onSince = when {
            !on -> 0L
            onSince == 0L -> now
            else -> onSince
        }
    }

    fun expired(now: Long): Boolean = onSince > 0L && now - onSince >= maxOnMs

    companion object {
        /** Longest time the antenna may stay lit while the robot is at rest. */
        const val MAX_ON_MS = 5 * 60_000L
    }
}

/** The antenna state shared by the bus (writer) and the loop (watchdog). */
object AntennaLight {
    val guard = LightGuard()
}

/**
 * Purple « a Hermes result waits » light: on for [holdMs] when the unread count grows,
 * then off even if the result is still unread (it used to stay lit for days).
 * An unreachable sidecar (null) turns it off without forgetting what was already shown.
 */
class HermesLight(private val holdMs: Long = LightGuard.MAX_ON_MS) {
    private var notified = 0
    private var until = 0L

    fun want(unread: Int?, now: Long): Boolean {
        if (unread == null) return false
        if (unread > notified) until = now + holdMs
        notified = unread
        return unread > 0 && now < until
    }
}
