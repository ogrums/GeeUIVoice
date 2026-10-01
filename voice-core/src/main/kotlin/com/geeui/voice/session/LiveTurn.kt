package com.geeui.voice.session

import com.geeui.voice.audio.EnergyVad
import com.geeui.voice.audio.VadEvent
import com.geeui.voice.audio.pcm16ToWav
import com.geeui.voice.engine.SpeechToText
import com.geeui.voice.engine.TextToSpeech

/**
 * Real-time loop. Frames in, one utterance out.
 * If the user starts talking while TTS plays, TTS is stopped (barge-in)
 * and the new turn is transcribed.
 */
class LiveTurn(
    private val vad: EnergyVad,
    private val stt: SpeechToText,
    private val tts: TextToSpeech,
    private val session: VoiceSession,
    private val sampleRate: Int = 16_000,
    var onUserText: (String) -> Unit = {},
    var onAnswer: (String) -> Unit = {},
) {
    var listening: Boolean = false
        private set

    var worker: (() -> Unit) -> Unit = { it() }

    fun onFrame(frame: ShortArray) {
        when (val event = vad.push(frame)) {
            VadEvent.None -> Unit
            VadEvent.SpeechStart -> {
                listening = true
                tts.stop()
            }
            is VadEvent.SpeechEnd -> {
                listening = false
                val pcm = event.pcm
                worker {
                    val text = stt.transcribe(pcm16ToWav(pcm, sampleRate), sampleRate).trim()
                    if (text.isEmpty()) return@worker
                    onUserText(text)
                    val answer = session.onUserText(text)
                    if (answer.isNotEmpty()) onAnswer(answer)
                }
            }
        }
    }
}
