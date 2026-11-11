package com.geeui.voiceemo

/** French instruction for a generative model, not a change to the model itself. */
const val FRENCH_EMOTION_SYSTEM_PROMPT: String =
    "Commence toujours ta réponse par une seule balise parmi " +
        "[happy] [sad] [angry] [fear] [surprise] [neutral] qui décrit le ton " +
        "de ta réponse, puis réponds normalement sans répéter la balise."

/** [face] is set for a nuance tag such as [love] or [thinking] (see [FaceCodes.NUANCE]). */
data class EmotionText(val emotion: Emotion, val text: String, val face: String? = null)

/**
 * Consumes streamed text, withholding a possible tag until it can be decided.
 * Finish must be called at end of stream so ordinary short text is not lost.
 */
class EmotionTagParser {
    private var atStart = true
    private val pending = StringBuilder()

    fun consume(chunk: String): EmotionText {
        if (!atStart) return EmotionText(Emotion.NEUTRAL, chunk)
        pending.append(chunk)
        if (pending.isEmpty()) return EmotionText(Emotion.NEUTRAL, "")
        if (pending[0] != '[') return plainText()
        val end = pending.indexOf(']')
        if (end >= 0) {
            val raw = pending.substring(1, end)
            atStart = false
            val text = pending.substring(end + 1)
            pending.clear()
            val emotion = Emotion.parse(raw)
            FaceCodes.NUANCE[raw.trim().lowercase()]?.let { (e, face) -> return EmotionText(e, text, face) }
            // An unknown opening tag is not an emotion tag and stays in the reply.
            return if (raw.trim().lowercase() !in TAGS) {
                EmotionText(Emotion.NEUTRAL, "[$raw]$text")
            } else {
                EmotionText(emotion, text)
            }
        }
        if (pending.length > MAX_TAG_LENGTH) return plainText()
        return EmotionText(Emotion.NEUTRAL, "")
    }

    fun finish(): EmotionText = plainText()

    private fun plainText(): EmotionText {
        val text = pending.toString()
        pending.clear()
        atStart = false
        return EmotionText(Emotion.NEUTRAL, text)
    }

    private companion object {
        const val MAX_TAG_LENGTH = 20
        val TAGS = setOf("happy", "sad", "angry", "fear", "surprise", "neutral")
    }
}
