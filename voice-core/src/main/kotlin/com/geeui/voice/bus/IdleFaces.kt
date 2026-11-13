package com.geeui.voice.bus

/**
 * Optional rest-face rotation through a calm look-around / blink set (h0135–h0175).
 *
 * **Off by default.** On this robot (RK3566), switching clips with `controlFace`
 * can crash the hardware decoder (`libomxvpu_dec.so` / `Rkvpu_Wait_ProcessPause`)
 * and freeze the screen. The setting `idle_faces=on` (or a custom list) turns
 * rotation back on, with a long interval so the previous clip can finish.
 */
object IdleFaces {
    /** Minimum safe gap between two rest `controlFace` calls, in seconds. */
    const val MIN_SECONDS = 60
    const val MAX_SECONDS = 120
    const val DEFAULT_SECONDS = 90

    /**
     * Faces in h0135–h0175 that do not belong in a rest loop.
     * Stupéfaction and wide eyes look startled; réflexion conflicts with the backend face.
     */
    val EXCLUDED = mapOf(
        "h0142" to "Stupéfaction",
        "h0148" to "Stupéfaction 2",
        "h0149" to "Réfléchit un peu",
        "h0170" to "Réflexion",
        "h0157" to "Yeux écarquillés",
        "h0158" to "Regard les yeux écarquillés",
        "h0160" to "Grands yeux écarquillés",
        "h0164" to "Grands yeux écarquillés 2",
        "h0172" to "Yeux écarquillés 4",
    )

    /** Built-in rest rotation: the range h0135–h0175 minus [EXCLUDED]. */
    val DEFAULT = (135..175).map { "h${it.toString().padStart(4, '0')}" }.filter { it !in EXCLUDED }

    fun isIdle(face: String): Boolean = face in DEFAULT

    /**
     * `null`/blank, `off`, `0`, `false`, `non`, `none`, or a lone `h0059` → disabled.
     * `on`, `auto`, or `default` → the built-in list.
     * Otherwise a comma/space-separated list of `hNNNN` (user values are kept even if excluded).
     */
    fun parseList(raw: String?): List<String> {
        val trimmed = raw?.trim().orEmpty()
        val key = trimmed.lowercase()
        if (trimmed.isEmpty() || key in setOf("off", "0", "false", "non", "none", "h0059", "0059")) {
            return emptyList()
        }
        if (key in setOf("on", "auto", "default")) return DEFAULT
        val ids = trimmed.split(Regex("[,;\\s]+")).mapNotNull { token ->
            val id = DialogueFaces.parseId(token, "")
            id.takeIf { it.isNotEmpty() }
        }.distinct()
        return ids
    }

    /**
     * Interval in milliseconds. Blank → 0, meaning [DEFAULT_SECONDS].
     * A number ≤ 180 is seconds; above that is milliseconds. Clamped to 60 s … 180 s.
     */
    fun parseInterval(raw: String?): Long {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0L
        val n = trimmed.toLongOrNull() ?: return 0L
        val ms = if (n <= 180L) n * 1000L else n
        return ms.coerceIn(MIN_SECONDS * 1000L, 180_000L)
    }

    fun nextDelayMs(
        configured: Long,
        random: () -> Int = { java.util.Random().nextInt(MAX_SECONDS - MIN_SECONDS + 1) },
    ): Long {
        if (configured > 0L) return configured.coerceAtLeast(MIN_SECONDS * 1000L)
        return (MIN_SECONDS + random()) * 1000L
    }

    /** Next face in [list], not repeating [current] when the list has at least two entries. */
    fun next(current: String, list: List<String>, random: (Int) -> Int = { java.util.Random().nextInt(it) }): String {
        if (list.isEmpty()) return IDLE_FACE
        if (list.size == 1) return list[0]
        val choices = list.filter { it != current }
        if (choices.isEmpty()) return list[0]
        return choices[random(choices.size)]
    }
}
