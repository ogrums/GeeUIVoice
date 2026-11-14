package com.geeui.voice.bus

/**
 * How the rest clip plays on our HUD. Looping h0059 keeps the video decoder and the
 * RenderThread busy all day (about a quarter of one core on the RK3566).
 * [replaySec] = 0 with [loop] = false: play once and hold the last frame.
 */
data class IdleVideo(val loop: Boolean, val replaySec: Int = 0) {
    companion object {
        val LOOP = IdleVideo(loop = true)
        val ONCE = IdleVideo(loop = false)
        /** Chosen on 14 Nov 2026: one play of the rest clip every 30 s. */
        val DEFAULT = IdleVideo(loop = false, replaySec = 30)

        /** A number of seconds between two plays (min. 10, default 30), "loop", or "once". */
        fun parse(raw: String?): IdleVideo {
            val v = raw?.trim()?.lowercase().orEmpty()
            if (v.isEmpty()) return DEFAULT
            v.toIntOrNull()?.let { return IdleVideo(loop = false, replaySec = it.coerceAtLeast(10)) }
            return when (v) {
                "once", "une", "1x", "still", "fixe" -> ONCE
                "loop", "boucle" -> LOOP
                else -> DEFAULT
            }
        }
    }
}
