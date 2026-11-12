package com.geeui.voiceemo

/** Labels the TTS, the face and the body all understand. [DISGUST] is the ONNX class `disgusted`. */
enum class Emotion {
    NEUTRAL, HAPPY, SAD, ANGRY, FEAR, SURPRISE, DISGUST;

    companion object {
        fun parse(raw: String?): Emotion {
            val key = raw?.trim()?.lowercase().orEmpty()
            return when (key) {
                "happy", "cheerful", "joy" -> HAPPY
                "sad", "sadness" -> SAD
                "angry", "anger" -> ANGRY
                "fear", "afraid", "fearful" -> FEAR
                "surprise", "surprised" -> SURPRISE
                "disgust", "disgusted", "dégout", "degout", "dégoût" -> DISGUST
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
