package com.geeui.voice.audio

/**
 * Endpointing on the robot. Lemonade has no VAD, so the turn ends here.
 * 20 ms frames, 16 kHz. Speech starts after a few loud frames, ends after
 * [hangoverFrames] of quiet. A turn is dropped if it is shorter than [minSpeechFrames].
 */
class EnergyVad(
    private val threshold: Int = 600,
    private val startFrames: Int = 3,
    private val hangoverFrames: Int = 25,
    private val minSpeechFrames: Int = 12,
    private val maxSpeechFrames: Int = 400,
) {
    private val speech = ArrayList<ShortArray>()
    private var loudRun = 0
    private var quietRun = 0
    private var inSpeech = false

    fun reset() {
        speech.clear()
        loudRun = 0
        quietRun = 0
        inSpeech = false
    }

    fun push(frame: ShortArray): VadEvent {
        val loud = rms(frame) >= threshold
        if (!inSpeech) {
            if (!loud) {
                loudRun = 0
                return VadEvent.None
            }
            loudRun++
            speech.add(frame)
            if (loudRun < startFrames) return VadEvent.None
            inSpeech = true
            quietRun = 0
            return VadEvent.SpeechStart
        }
        speech.add(frame)
        if (loud) quietRun = 0 else quietRun++
        val tooLong = speech.size >= maxSpeechFrames
        if (quietRun < hangoverFrames && !tooLong) return VadEvent.None
        val pcm = concat(speech)
        val longEnough = speech.size >= minSpeechFrames
        reset()
        return if (longEnough) VadEvent.SpeechEnd(pcm) else VadEvent.None
    }

    private fun rms(frame: ShortArray): Int {
        if (frame.isEmpty()) return 0
        var acc = 0.0
        for (s in frame) acc += s * s.toDouble()
        return kotlin.math.sqrt(acc / frame.size).toInt()
    }

    private fun concat(frames: List<ShortArray>): ShortArray {
        val n = frames.sumOf { it.size }
        val out = ShortArray(n)
        var at = 0
        for (f in frames) {
            f.copyInto(out, at)
            at += f.size
        }
        return out
    }
}

sealed class VadEvent {
    data object None : VadEvent()
    data object SpeechStart : VadEvent()
    data class SpeechEnd(val pcm: ShortArray) : VadEvent()
}
