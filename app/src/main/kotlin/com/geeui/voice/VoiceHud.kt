package com.geeui.voice

/** Shared with the scanner view. Written from the mic thread, read on the UI thread. */
object VoiceHud {
    @Volatile var level: Float = 0f
    @Volatile var mode: String = "idle"
    @Volatile var line: String = ""
}
