package com.geeui.voice

/** Shared with the scanner view. Written from the mic thread, read on the UI thread. */
object VoiceHud {
    @Volatile var level: Float = 0f
    @Volatile var mode: String = "idle"
    @Volatile var line: String = ""
    @Volatile var heard: String = ""
    @Volatile var answer: String = ""
    @Volatile var diag: String = ""
    @Volatile var playing: Boolean = false
    /** Mic stays closed until this clock time, so the speaker tail is not transcribed. */
    @Volatile var hearAfter: Long = 0L
    @Volatile var mic: Boolean = false
    /** Face id to play, h0006 and the others. Empty until a pose starts. */
    @Volatile var face: String = ""
}
