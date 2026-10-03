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
    @Volatile var mic: Boolean = false
}
