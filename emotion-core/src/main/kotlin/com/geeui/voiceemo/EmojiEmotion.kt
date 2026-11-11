package com.geeui.voiceemo

/**
 * Emoji in an LLM reply (nanobot often ends with 😊) become a robot expression and are
 * never read aloud. [of] gives the emotion of the first known emoji; [strip] removes
 * every emoji, keeping letters, digits and punctuation.
 */
object EmojiEmotion {
    private val MAP: Map<Int, Emotion> = buildMap {
        fun put(emotion: Emotion, vararg emoji: String) {
            for (e in emoji) put(e.codePointAt(0), emotion)
        }
        put(
            Emotion.HAPPY,
            "😊", "🙂", "😄", "😁", "😃", "😀", "😆", "😉", "🥰", "😍", "😘", "🤗", "😂", "🤣",
            "😎", "🥳", "😋", "😇", "❤", "💖", "💕", "💗", "👍", "🎉", "✨", "🌞", "☺",
        )
        put(Emotion.SAD, "😢", "😭", "😞", "😔", "🙁", "☹", "😟", "😥", "😿", "💔", "🥺")
        put(Emotion.SURPRISE, "😮", "😲", "🤩", "😯", "😳", "🙀", "‼", "❗")
        put(Emotion.ANGRY, "😠", "😡", "🤬", "😤", "👿")
        put(Emotion.FEAR, "😨", "😱", "😰", "😧", "😦")
        put(Emotion.NEUTRAL, "🤔", "😐", "😶", "🙃", "😅")
    }

    /** Pictographs, dingbats, flags, keycap/skin-tone/ZWJ/variation-selector glue. */
    private val EMOJI = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{2300}-\\x{23FF}" +
            "\\x{2190}-\\x{21FF}\\x{203C}\\x{2049}\\x{3030}\\x{303D}\\x{3297}\\x{3299}" +
            "\\x{FE0E}\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]",
    )

    /** Emotion of the first mapped emoji, or null when the text has none. */
    fun of(text: String): Emotion? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            MAP[cp]?.let { return it }
            i += Character.charCount(cp)
        }
        return null
    }

    fun strip(text: String): String = text.replace(EMOJI, "").replace(Regex(" {2,}"), " ").trim()
}
