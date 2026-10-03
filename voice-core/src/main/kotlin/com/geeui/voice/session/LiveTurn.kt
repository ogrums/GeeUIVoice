package com.geeui.voice.session

import com.geeui.voice.audio.EnergyVad
import com.geeui.voice.audio.VadEvent
import com.geeui.voice.audio.pcm16ToWav
import com.geeui.voice.engine.SpeechToText
import com.geeui.voice.engine.TextToSpeech

/**
 * Real-time loop. Frames in, one utterance out.
 * While the speaker plays, and for a short tail after, frames are dropped
 * so the robot does not transcribe its own answer.
 */
class LiveTurn(
    private val vad: EnergyVad,
    private val stt: SpeechToText,
    private val tts: TextToSpeech,
    private val session: VoiceSession,
    private val sampleRate: Int = 16_000,
    var onUserText: (String) -> Unit = {},
    var onAnswer: (String) -> Unit = {},
    var onListen: () -> Unit = {},
    var onIdle: () -> Unit = {},
    /** False while the speaker is on, or during the echo tail after it stops. */
    var allowBargeIn: () -> Boolean = { true },
) {
    var listening: Boolean = false
        private set

    var worker: (() -> Unit) -> Unit = { it() }

    fun onFrame(frame: ShortArray) {
        if (!allowBargeIn()) {
            vad.reset()
            listening = false
            return
        }
        when (val event = vad.push(frame)) {
            VadEvent.None -> Unit
            VadEvent.SpeechStart -> {
                if (!allowBargeIn()) return
                listening = true
                tts.stop()
                onListen()
            }
            is VadEvent.SpeechEnd -> {
                listening = false
                val pcm = event.pcm
                worker {
                    val text = stt.transcribe(pcm16ToWav(pcm, sampleRate), sampleRate).trim()
                    if (text.isEmpty()) {
                        onIdle()
                        return@worker
                    }
                    onUserText(text)
                    val answer = session.onUserText(text)
                    if (answer.isNotEmpty()) onAnswer(answer)
                }
            }
        }
    }
}
