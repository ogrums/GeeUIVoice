package com.geeui.voice.session

import com.geeui.voice.bus.RobotBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.StreamingChat
import com.geeui.voice.engine.TextToSpeech
import com.geeui.voice.skill.SkillLexicon
import com.geeui.voice.skill.SkillRouter

enum class Dialogue { Idle, Listening, Speaking }

/**
 * One spoken turn. A known phrase hits the robot and does not call the LLM.
 * Otherwise the whole answer is spoken in one clip, so it is not chopped.
 */
class VoiceSession(
    bus: RobotBus,
    private val tts: TextToSpeech,
    private val chat: Chat,
    private val language: String = "fr",
    charging: () -> Boolean = { false },
) {
    private val skills = SkillRouter(bus, charging)
    var state: Dialogue = Dialogue.Idle
        private set

    fun onUserText(text: String): String {
        state = Dialogue.Listening
        val hit = SkillLexicon.match(text)
        if (hit != null && skills.handle(hit)) {
            state = Dialogue.Idle
            return ""
        }
        state = Dialogue.Speaking
        val answer = StringBuilder()
        if (chat is StreamingChat) {
            chat.stream(text) { delta -> answer.append(delta) }
        } else {
            answer.append(chat.reply(text))
        }
        val whole = spoken(answer.toString())
        if (whole.isNotEmpty()) tts.speak(whole, language)
        state = Dialogue.Idle
        return whole
    }

    /** Kokoro reads '*' aloud. Keep letters, spaces and normal punctuation. */
    private fun spoken(raw: String): String {
        var s = raw.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)")) { it.groupValues[1] }
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        s = s.replace(Regex("[*_`~]+"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }
}
