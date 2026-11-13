package com.geeui.voiceemo

/** French instruction for a generative model, not a change to the model itself. */
const val FRENCH_EMOTION_SYSTEM_PROMPT: String =
    "Commence toujours ta réponse par une seule balise parmi " +
        "[happy] [sad] [angry] [fear] [surprise] [disgust] [neutral] qui décrit " +
        "le ton de ta réponse. Prends neutral dès que le ton est ordinaire, et " +
        "happy seulement si la réponse est vraiment joyeuse. Puis réponds " +
        "normalement sans répéter la balise."

/** [face] is set for a nuance tag such as [love] or [thinking] (see [FaceCodes.NUANCE]). */
data class EmotionText(val emotion: Emotion, val text: String, val face: String? = null)

/**
 * Consumes streamed text, withholding a possible tag until it can be decided.
 * Finish must be called at end of stream so ordinary short text is not lost.
 *
 * A leading `[happy]` is the normal form. A bare word (`neutral Je ne peux pas…`),
 * `(neutral)` or `*neutral*` is also taken when it is the first token, followed
 * by a separator (`:`, `,` or whitespace) and more text. `Neutralement` stays.
 */
class EmotionTagParser {
    private var atStart = true
    private val pending = StringBuilder()

    fun consume(chunk: String): EmotionText {
        if (!atStart) return EmotionText(Emotion.NEUTRAL, chunk)
        pending.append(chunk)
        return emit(finished = false)
    }

    fun finish(): EmotionText = if (!atStart) EmotionText(Emotion.NEUTRAL, "") else emit(finished = true)

    private fun emit(finished: Boolean): EmotionText {
        if (pending.isEmpty()) {
            if (finished) atStart = false
            return EmotionText(Emotion.NEUTRAL, "")
        }
        val s = pending.toString()
        var i = 0
        while (i < s.length && s[i].isWhitespace()) i++
        if (i == s.length) {
            if (finished) return flushPlain()
            return EmotionText(Emotion.NEUTRAL, "")
        }
        return when (s[i]) {
            '[' -> emitBracket(s, i, finished)
            '(' -> emitWrapped(s, i, ')', finished)
            '*' -> emitWrapped(s, i, '*', finished)
            else -> emitBare(s, i, finished)
        }
    }

    private fun emitBracket(s: String, open: Int, finished: Boolean): EmotionText {
        val end = s.indexOf(']', startIndex = open + 1)
        if (end >= 0) {
            val raw = s.substring(open + 1, end)
            atStart = false
            val text = s.substring(end + 1)
            pending.clear()
            val emotion = Emotion.parse(raw)
            FaceCodes.NUANCE[raw.trim().lowercase()]?.let { (e, face) -> return EmotionText(e, text, face) }
            // An unknown opening tag is not an emotion tag and stays in the reply.
            return if (raw.trim().lowercase() !in TAGS) {
                EmotionText(Emotion.NEUTRAL, s.substring(0, open) + "[$raw]$text")
            } else {
                EmotionText(emotion, text)
            }
        }
        if (finished || s.length - open > MAX_TAG_LENGTH) return flushPlain()
        return EmotionText(Emotion.NEUTRAL, "")
    }

    private fun emitWrapped(s: String, open: Int, close: Char, finished: Boolean): EmotionText {
        val end = s.indexOf(close, startIndex = open + 1)
        if (end < 0) {
            if (finished || s.length - open > MAX_TAG_LENGTH) return flushPlain()
            return EmotionText(Emotion.NEUTRAL, "")
        }
        val raw = s.substring(open + 1, end).trim().lowercase()
        if (raw !in TAGS) return flushPlain()
        return takeEmotion(s, end + 1, Emotion.parse(raw), finished)
    }

    private fun emitBare(s: String, start: Int, finished: Boolean): EmotionText {
        var wordEnd = start
        while (wordEnd < s.length && s[wordEnd].isLetter()) wordEnd++
        val word = s.substring(start, wordEnd).lowercase()
        if (wordEnd == s.length) {
            if (finished) return flushPlain()
            if (TAGS.any { it.startsWith(word) }) return EmotionText(Emotion.NEUTRAL, "")
            return flushPlain()
        }
        if (word !in TAGS || !isSeparator(s[wordEnd])) return flushPlain()
        return takeEmotion(s, wordEnd, Emotion.parse(word), finished)
    }

    /** First token is an emotion: skip `:` / `,` / whitespace and require more text. */
    private fun takeEmotion(s: String, from: Int, emotion: Emotion, finished: Boolean): EmotionText {
        var rest = from
        while (rest < s.length && isSeparator(s[rest])) rest++
        if (rest >= s.length) {
            if (finished) return flushPlain()
            return EmotionText(Emotion.NEUTRAL, "")
        }
        atStart = false
        pending.clear()
        return EmotionText(emotion, s.substring(rest))
    }

    private fun flushPlain(): EmotionText {
        val text = pending.toString()
        pending.clear()
        atStart = false
        return EmotionText(Emotion.NEUTRAL, text)
    }

    private companion object {
        const val MAX_TAG_LENGTH = 20
        val TAGS = setOf("happy", "sad", "angry", "fear", "surprise", "disgust", "neutral")

        fun isSeparator(c: Char): Boolean = c == ':' || c == ',' || c.isWhitespace()
    }
}
