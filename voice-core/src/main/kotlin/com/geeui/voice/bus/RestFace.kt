package com.geeui.voice.bus

/**
 * Face shown at rest (standAtAttention and the idle screen). h0059 by default; the robot mood
 * from the sidecar (GET /v1/robot/pending, field "mood") picks another one. Videos from GeeUIFace.
 */
object RestFace {
    /** Mood label from the sidecar (robot_mood.py) → face id. Unknown label → h0059. */
    val BY_MOOD = mapOf(
        "calme" to IDLE_FACE, // yeux neutres
        "content" to "h0063", // petit sourire
        "joyeux" to "h0006", // grand sourire
        "ennuye" to "h0042", // regarde à gauche et à droite
        "boudeur" to "h0119", // moue
        "triste" to "h0010", // larmes
        "fatigue" to "h0039", // yeux qui se ferment
    )

    @Volatile
    var current: String = IDLE_FACE

    fun forMood(label: String): String = BY_MOOD[label.trim().lowercase()] ?: IDLE_FACE

    /** True for the plain idle face and the current mood face: neither is an emotion reaction. */
    fun isRest(face: String): Boolean = face == IDLE_FACE || face == current
}
