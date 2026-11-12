package com.geeui.voice.bus

/**
 * Face shown at rest (standAtAttention and the idle screen). h0059 by default; the robot mood
 * from the sidecar (GET /v1/robot/pending, field "mood") picks another one. Videos from GeeUIFace.
 */
object RestFace {
    /** Mood label from the sidecar (robot_mood.py) → face id. Unknown label → h0059. */
    val BY_MOOD = mapOf(
        "calme" to IDLE_FACE, // h0059 常规环, boucle neutre
        "content" to "h0060", // 笑容环, boucle sourire (pas h0063 回正黄)
        "joyeux" to "h0020", // 快乐, joie (h0006 est un rire en X, pas un sourire)
        "ennuye" to "h0042", // 左下斜, regard en bas à gauche (pas d'ennui dédié)
        "boudeur" to "h0058", // 皱眉环, boucle froncée (pas h0119 哭泣)
        "triste" to "h0010", // 哭泣1, larmes
        "fatigue" to "h0039", // 睡觉, yeux qui se ferment
    )

    @Volatile
    var current: String = IDLE_FACE

    fun forMood(label: String): String = BY_MOOD[label.trim().lowercase()] ?: IDLE_FACE

    /** True for the plain idle face and the current mood face: neither is an emotion reaction. */
    fun isRest(face: String): Boolean = face == IDLE_FACE || face == current
}
