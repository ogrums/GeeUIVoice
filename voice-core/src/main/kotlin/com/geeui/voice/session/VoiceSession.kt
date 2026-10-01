package com.geeui.voice.session

import com.geeui.voice.bus.RobotBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.TextToSpeech
import com.geeui.voice.skill.SkillLexicon
import com.geeui.voice.skill.SkillRouter

enum class Dialogue { Idle, Listening, Speaking }

/**
 * Same turn as LTPAudioService / BotService, without DUI or Lex:
 * text in → skill or chat → TTS.
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
        val answer = chat.reply(text)
        state = Dialogue.Speaking
        tts.speak(answer, language)
        state = Dialogue.Idle
        return answer
    }
}
