package com.geeui.voice.session

/**
 * Phrases that ask the robot to look. Only these take a photo: no camera otherwise,
 * no continuous capture. Matched in code, before the LLM, so the photo leaves with the turn.
 */
object VisionIntent {
    private val PHRASES = listOf(
        "qu'est-ce que tu vois", "qu'est ce que tu vois", "que vois-tu", "que vois tu", "tu vois quoi",
        "tu vois ça", "tu vois ca", "regarde ça", "regarde ca", "regarde ce", "regarde cette",
        "regarde mon", "regarde ma", "regarde mes", "lis ça", "lis ca", "lis ce", "lis cette", "lis-moi",
        "lis moi", "décris ce que", "decris ce que", "c'est quoi ça", "c'est quoi ca",
        "qu'est-ce que c'est que ça", "qu'est-ce que je tiens", "qu'est ce que je tiens",
        "combien de doigts", "de quelle couleur est", "what do you see", "look at this",
    )

    fun wants(text: String): Boolean {
        val t = " " + text.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trim() + " "
        return PHRASES.any { t.contains(it) }
    }

    /** Said to the LLM when the camera did not answer, so it does not invent a description. */
    const val BLIND = "(RUX a voulu regarder mais n'a pas pu : caméra indisponible) "
}
