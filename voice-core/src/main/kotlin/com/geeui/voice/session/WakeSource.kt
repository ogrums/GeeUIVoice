package com.geeui.voice.session

/** Which on-device or stock wake word can start a conversation. */
enum class WakeSource {
    /** Stock Lex « Hi RUX » only. */
    HIRUX,
    /** Our Vosk « Hey Dobby » only. */
    DOBBY,
    /** Both, either one starts a conversation. */
    BOTH,
}

fun WakeSource.usesHirux(): Boolean = this == WakeSource.HIRUX || this == WakeSource.BOTH

fun WakeSource.usesDobby(): Boolean = this == WakeSource.DOBBY || this == WakeSource.BOTH

fun parseWakeSource(raw: String?): WakeSource = when (
    raw?.trim()?.lowercase()?.replace('_', '-')?.replace(' ', '-')
) {
    "hirux", "hi-rux", "lex", "rux" -> WakeSource.HIRUX
    "dobby", "hey-dobby", "hé-dobby", "he-dobby" -> WakeSource.DOBBY
    else -> WakeSource.BOTH
}
