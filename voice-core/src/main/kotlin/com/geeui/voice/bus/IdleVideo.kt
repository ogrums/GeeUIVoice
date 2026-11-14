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

        /** "loop" (default), "once", or a number of seconds between two plays (min. 10). */
        fun parse(raw: String?): IdleVideo {
            val v = raw?.trim()?.lowercase().orEmpty()
            v.toIntOrNull()?.let { return IdleVideo(loop = false, replaySec = it.coerceAtLeast(10)) }
            return when (v) {
                "once", "une", "1x", "still", "fixe" -> ONCE
                else -> LOOP
            }
        }
    }
}
