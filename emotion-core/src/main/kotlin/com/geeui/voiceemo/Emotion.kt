package com.geeui.voiceemo

/** Six labels the TTS and the body both understand. */
enum class Emotion {
    NEUTRAL, HAPPY, SAD, ANGRY, FEAR, SURPRISE;

    companion object {
        fun parse(raw: String?): Emotion {
            val key = raw?.trim()?.lowercase().orEmpty()
            return when (key) {
                "happy", "cheerful", "joy" -> HAPPY
                "sad", "sadness" -> SAD
                "angry", "anger" -> ANGRY
                "fear", "afraid", "fearful" -> FEAR
                "surprise", "surprised" -> SURPRISE
                else -> NEUTRAL
            }
        }
    }
}

data class Affect(val emotion: Emotion, val confidence: Float) {
    init {
        require(confidence in 0f..1f) { "confidence out of range: $confidence" }
    }

    companion object {
        val UNKNOWN = Affect(Emotion.NEUTRAL, 0f)
    }
}
