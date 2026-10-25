package com.geeui.voice.session

/**
 * A spoken request to draw. The subject is what follows the verb.
 * Empty leftovers are not a request.
 */
object DrawAsk {
    private val heads = listOf(
        "dessine moi ",
        "dessines moi ",
        "draw me ",
        "dessine ",
    )

    fun subject(text: String): String? {
        var key = text.trim().lowercase().replace(Regex("\\s+"), " ")
        key = key.replace("dessine-moi", "dessine moi").replace("dessines-moi", "dessine moi")
        for (head in heads) {
            if (!key.startsWith(head)) continue
            val rest = key.removePrefix(head).trim().trimEnd('.', '?', '!', ',')
            if (rest.isNotEmpty()) return rest
        }
        return null
    }
}
