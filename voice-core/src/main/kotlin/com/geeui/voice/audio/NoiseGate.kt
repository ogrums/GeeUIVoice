package com.geeui.voice.audio

/**
 * Energy gate in front of the always-on wake recognizer (100 ms frames).
 * A fixed gate of 50 RMS let almost every frame through on the robot: the
 * VOICE_COMMUNICATION source boosts room noise, so Vosk ran all the time.
 * In "auto" mode the gate follows the noise floor: it drops fast when the room
 * gets quieter and rises slowly, and a frame passes when it is [factor] times
 * louder than the floor. A steady louder noise (a fan) is learned in 1 to 2 min. [minGate] keeps the old floor and [maxGate] caps it, so
 * a loud fan does not make the wake word unreachable.
 */
class NoiseGate(
    private val fixed: Int? = null,
    private val minGate: Int = 50,
    private val maxGate: Int = 300,
    private val factor: Double = 2.0,
) {
    var floor: Double = minGate.toDouble()
        private set

    val gate: Int
        get() = fixed ?: (floor * factor).toInt().coerceIn(minGate, maxGate)

    /** Returns true when the frame should be fed to the recognizer. */
    fun pass(rms: Double): Boolean {
        val open = rms >= gate
        if (fixed == null) {
            // Down fast, up slowly; while the gate is open (speech) almost not at all,
            // so « Hey Dobby » itself does not raise the gate.
            val k = when {
                rms < floor -> 0.2
                open -> 0.001
                else -> 0.01
            }
            floor += (rms - floor) * k
            if (floor < 1.0) floor = 1.0
        }
        return open
    }

    companion object {
        /** "auto" (default), or a fixed RMS such as "50". */
        fun parse(raw: String?): NoiseGate {
            val v = raw?.trim()?.lowercase().orEmpty()
            val n = v.toIntOrNull()
            return if (n != null && n > 0) NoiseGate(fixed = n) else NoiseGate()
        }
    }
}
