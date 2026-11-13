package com.geeui.voice.session

import com.geeui.voice.bus.lightId
import com.geeui.voice.skill.SkillHit

/** One action tag from the end of a reply ([move:forward], [light:blue]…). */
data class RobotAction(val tag: String, val kind: String, val hit: SkillHit?, val light: Int?) {
    /** Walking and going home ask « Je peux y aller ? » first. */
    val needsConfirmation: Boolean get() = kind == "move" || kind == "home"
}

/**
 * The action tags of AGENTS.md, read in code. Unknown tags are not actions (and are left to
 * the emotion/tag parsers). Each tag is removed from the spoken text.
 */
object ActionTags {
    val KINDS = setOf("move", "volume", "light", "photo", "home")
    private val TAG = Regex("\\[(move|volume|light|photo|home)(?::([a-zA-Z]+))?]")
    private val COLORS = setOf("red", "green", "blue", "orange", "white", "yellow", "purple", "cyan")

    fun parse(tag: String, kind: String, arg: String?): RobotAction? {
        val a = arg?.lowercase()
        return when (kind) {
            "move" -> when (a) {
                "forward" -> RobotAction(tag, kind, SkillHit("actin", mapOf("number" to "98")), null)
                "back" -> RobotAction(tag, kind, SkillHit("actin", mapOf("number" to "64")), null)
                else -> null
            }
            "volume" -> when (a) {
                "up" -> RobotAction(tag, kind, SkillHit("volumeUp"), null)
                "down" -> RobotAction(tag, kind, SkillHit("volumeDown"), null)
                else -> null
            }
            "photo" -> if (a == null) RobotAction(tag, kind, SkillHit("takePhoto"), null) else null
            "home" -> if (a == null) RobotAction(tag, kind, SkillHit("goHome"), null) else null
            "light" -> when {
                a == "off" -> RobotAction(tag, kind, null, 0)
                a in COLORS -> RobotAction(tag, kind, null, lightId(a!!.uppercase()))
                else -> null
            }
            else -> null
        }
    }

    /** (text without action tags, actions in order). */
    fun split(text: String): Pair<String, List<RobotAction>> {
        val actions = mutableListOf<RobotAction>()
        val clean = TAG.replace(text) { m ->
            parse(m.value, m.groupValues[1], m.groupValues[2].ifEmpty { null })?.let { actions += it }
            ""
        }
        return clean.replace(Regex("[ \\t]{2,}"), " ").trim() to actions
    }

    private val YES = Regex("^\\W*(oui|ouais|vas[- ]y|d'accord|ok|okay|go|yes|allez)\\b", RegexOption.IGNORE_CASE)

    fun isYes(text: String): Boolean = YES.containsMatchIn(text.replace('’', '\''))
}
