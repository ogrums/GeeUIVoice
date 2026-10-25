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
}
