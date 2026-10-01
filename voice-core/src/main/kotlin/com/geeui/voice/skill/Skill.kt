package com.geeui.voice.skill

data class SkillHit(
    val id: String,
    val slots: Map<String, String> = emptyMap(),
)

/**
 * Lex intent names observed in BotService.dealCommand, plus FR/EN phrases
 * so the robot can move before a cloud NLU exists.
 */
object SkillLexicon {
    val phrases: Map<String, SkillHit> = mapOf(
        "avance" to SkillHit("actin", mapOf("number" to "98")),
        "walk forward" to SkillHit("actin", mapOf("number" to "98")),
        "marche" to SkillHit("actin", mapOf("number" to "98")),
        "recule" to SkillHit("actin", mapOf("number" to "64")),
        "walk back" to SkillHit("actin", mapOf("number" to "64")),
        "plus fort" to SkillHit("volumeUp"),
        "volume up" to SkillHit("volumeUp"),
        "moins fort" to SkillHit("volumeDown"),
        "volume down" to SkillHit("volumeDown"),
        "tais-toi" to SkillHit("volumemix"),
        "mute" to SkillHit("volumemix"),
        "photo" to SkillHit("takePhoto"),
        "take a photo" to SkillHit("takePhoto"),
        "rentre" to SkillHit("goHome"),
        "go home" to SkillHit("goHome"),
    )

    fun match(text: String): SkillHit? {
        val key = text.trim().lowercase()
        return phrases[key]
    }
}
