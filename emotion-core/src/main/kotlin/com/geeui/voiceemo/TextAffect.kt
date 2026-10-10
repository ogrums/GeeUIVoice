package com.geeui.voiceemo

/**
 * Keyword vote from the transcript. High enough that one clear word
 * crosses the pose threshold in [EmotionPipeline]. No second model.
 */
object TextAffect {
    private val groups = listOf(
        Emotion.ANGRY to listOf("colère", "colere", "furieux", "furieuse", "angry", "rage"),
        Emotion.SAD to listOf("triste", "tristesse", "sad", "pleure"),
        Emotion.FEAR to listOf("peur", "afraid", "fear", "effrayé", "effraye"),
        Emotion.SURPRISE to listOf("surprise", "surpris", "incroyable", "wow"),
        Emotion.HAPPY to listOf("content", "heureux", "heureuse", "happy", "joie", "super"),
    )

    fun of(text: String): Affect {
        val lower = text.lowercase()
        for ((emotion, words) in groups) {
            if (words.any { lower.contains(it) }) return Affect(emotion, 0.8f)
        }
        return Affect.UNKNOWN
    }
}
