package com.geeui.voice.session

/**
 * A spoken request to draw. The verb may sit in the middle of the sentence.
 * Whisper often adds "peux-tu" or "s'il te plaît" in front.
 */
object DrawAsk {
    private val markers = listOf(
        "dessine moi ",
        "dessines moi ",
        "dessinez moi ",
        "dessiner ",
        "dessinez ",
        "dessines ",
        "dessine ",
        "dessin ",
        "desine ",
        "draw me ",
        "draw ",
    )

    fun subject(text: String): String? {
        var key = text.trim().lowercase()
        key = key.replace(Regex("[!?.]+"), " ")
        key = key.replace(Regex("\\s+"), " ").trim()
        key = key.replace("dessine-moi", "dessine moi")
            .replace("dessines-moi", "dessine moi")
            .replace("dessinez-moi", "dessine moi")
            .replace("dessin-moi", "dessine moi")
            .replace("desine-moi", "dessine moi")
            .replace("desine moi", "dessine moi")
            .replace("dessin moi", "dessine moi")
        var at = -1
        var hit = ""
        for (marker in markers) {
            val i = key.indexOf(marker)
            if (i < 0) continue
            val better = at < 0 || i < at || (i == at && marker.length > hit.length)
            if (better) {
                at = i
                hit = marker
            }
        }
        if (at < 0) return null
        val rest = key.substring(at + hit.length).trim().trimEnd(',', ';', ':')
        return rest.ifEmpty { null }
    }

    /** Words that leave the draw hold and go back to conversation. */
    fun leave(text: String): Boolean {
        val key = fold(text.trim().lowercase().replace(Regex("\\s+"), " "))
        val leaves = listOf("arrete", "stop", "annule", "parle", "laisse tomber", "stoppe")
        return leaves.any { key == it || key.startsWith("$it ") }
    }

    private fun fold(text: String): String {
        val map = mapOf(
            'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
            'à' to 'a', 'â' to 'a', 'ù' to 'u', 'û' to 'u',
            'ô' to 'o', 'î' to 'i', 'ï' to 'i', 'ç' to 'c',
        )
        return buildString(text.length) {
            for (c in text) append(map[c] ?: c)
        }
    }
}
