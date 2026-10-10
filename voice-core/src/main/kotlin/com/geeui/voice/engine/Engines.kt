package com.geeui.voice.engine

/** Wake, STT, TTS and chat are swappable. None of them call AWS or iFlytek. */
interface WakeWord {
    fun start(onWake: () -> Unit)
    fun stop()
}

interface SpeechToText {
    /** [pcm16le] is a WAV blob when it comes from [com.geeui.voice.session.LiveTurn]. */
    fun transcribe(pcm16le: ByteArray, sampleRate: Int): String
}

interface TextToSpeech {
    fun speak(text: String, language: String)
    fun stop()

    /** Called when the clip actually starts, not when the bytes arrive. */
    fun whenAudible(action: (() -> Unit)?) {}
}

/** A TTS that also keeps the last audio clip, so the Android player can read it. */
interface ClipTts : TextToSpeech {
    val lastAudio: ByteArray
}

interface Chat {
    fun reply(userText: String): String
}

/** Optional. [VoiceSession] speaks each clause as it arrives. */
interface StreamingChat : Chat {
    fun stream(userText: String, onDelta: (String) -> Unit)
}

class NoWake : WakeWord {
    override fun start(onWake: () -> Unit) = Unit
    override fun stop() = Unit
}

class NoStt : SpeechToText {
    override fun transcribe(pcm16le: ByteArray, sampleRate: Int): String = ""
}

class EchoTts : TextToSpeech {
    val spoken = mutableListOf<String>()
    override fun speak(text: String, language: String) {
        spoken += "$language:$text"
    }
    override fun stop() = Unit
}

class FixedChat : Chat {
    override fun reply(userText: String): String =
        "Je n'ai pas encore de modèle. Tu as dit : $userText"
}
