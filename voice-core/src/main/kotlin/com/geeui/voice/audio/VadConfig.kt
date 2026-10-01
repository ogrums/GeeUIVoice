package com.geeui.voice.audio

/**
 * Desk defaults for the RUX mic (close, a bit of fan noise).
 * 20 ms frames. Raise [threshold] if the room triggers alone.
 * Raise [hangoverMs] if words get cut. Lower it if the reply waits too long.
 */
data class VadConfig(
    val threshold: Int = 800,
    val startMs: Int = 80,
    val hangoverMs: Int = 600,
    val minSpeechMs: Int = 280,
    val maxSpeechMs: Int = 8_000,
    val frameMs: Int = 20,
) {
    fun toVad(): EnergyVad = EnergyVad(
        threshold = threshold,
        startFrames = frames(startMs),
        hangoverFrames = frames(hangoverMs),
        minSpeechFrames = frames(minSpeechMs),
        maxSpeechFrames = frames(maxSpeechMs),
    )

    private fun frames(ms: Int): Int = (ms / frameMs).coerceAtLeast(1)

    companion object {
        fun from(values: Map<String, String?>): VadConfig {
            val base = VadConfig()
            return base.copy(
                threshold = values["vad_threshold"]?.toIntOrNull() ?: base.threshold,
                startMs = values["vad_start_ms"]?.toIntOrNull() ?: base.startMs,
                hangoverMs = values["vad_hangover_ms"]?.toIntOrNull() ?: base.hangoverMs,
                minSpeechMs = values["vad_min_ms"]?.toIntOrNull() ?: base.minSpeechMs,
                maxSpeechMs = values["vad_max_ms"]?.toIntOrNull() ?: base.maxSpeechMs,
            )
        }
    }
}
