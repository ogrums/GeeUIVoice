package com.geeui.voice.bus

/**
 * Faces shown at rest, rotating through a calm look-around / blink set (h0135–h0175).
 * The list skips clips that are startled, thinking, or otherwise not a rest loop.
 * An empty configured list (setting `off` / `h0059`) disables rotation and keeps [IDLE_FACE].
 */
object IdleFaces {
    /** Seconds when the setting is left empty: a new delay is picked in this window each time. */
    const val MIN_SECONDS = 20
    const val MAX_SECONDS = 40
    const val DEFAULT_SECONDS = 30

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
     * `null`/blank → the built-in list.
     * `off`, `0`, `false`, `non`, `none`, or a lone `h0059` → disabled (empty).
     * Otherwise a comma/space-separated list of `hNNNN` (user values are kept even if excluded).
     */
    fun parseList(raw: String?): List<String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT
        val key = trimmed.lowercase()
        if (key in setOf("off", "0", "false", "non", "none", "h0059", "0059")) return emptyList()
        val ids = trimmed.split(Regex("[,;\\s]+")).mapNotNull { token ->
            val id = DialogueFaces.parseId(token, "")
            id.takeIf { it.isNotEmpty() }
        }.distinct()
        return ids
    }

    /**
     * Interval in milliseconds. Blank → 0, meaning "pick 20–40 s at random each cycle".
     * A number ≤ 120 is seconds; above that is milliseconds. Clamped to 15 s … 90 s.
     */
    fun parseInterval(raw: String?): Long {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return 0L
        val n = trimmed.toLongOrNull() ?: return 0L
        val ms = if (n <= 120L) n * 1000L else n
        return ms.coerceIn(15_000L, 90_000L)
    }

    fun nextDelayMs(configured: Long, random: () -> Int = { java.util.Random().nextInt(MAX_SECONDS - MIN_SECONDS + 1) }): Long {
        if (configured > 0L) return configured
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
