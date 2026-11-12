package com.geeui.voice.session

import com.geeui.voice.bus.IDLE_FACE

/** One `showFace` call, with a timestamp in epoch milliseconds. */
data class FaceEvent(val face: String, val atMs: Long) {
    fun toJson(): String = """{"face":${q(face)},"at_ms":$atMs}"""
}

/**
 * The emotion face shown while the robot was speaking: the last non-idle face
 * before `standAtAttention` puts [IDLE_FACE] back. Idle-only turns return [IDLE_FACE].
 */
fun faceDuringSpeech(events: List<FaceEvent>): String =
    events.lastOrNull { it.face.isNotBlank() && it.face != IDLE_FACE }?.face
        ?: events.lastOrNull { it.face.isNotBlank() }?.face.orEmpty()
