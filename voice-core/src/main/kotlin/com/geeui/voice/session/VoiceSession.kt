package com.geeui.voice.session

import com.geeui.voice.bus.RobotBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.SentenceSplitter
import com.geeui.voice.engine.StreamingChat
import com.geeui.voice.engine.TextToSpeech
import com.geeui.voice.skill.SkillLexicon
import com.geeui.voice.skill.SkillRouter

enum class Dialogue { Idle, Listening, Speaking }

/**
 * One spoken turn. A known phrase hits the robot and does not call the LLM.
 * Otherwise the answer is spoken clause by clause when the chat can stream.
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
            val split = SentenceSplitter()
            chat.stream(text) { delta ->
                answer.append(delta)
                for (clause in split.push(delta)) tts.speak(clause, language)
            }
            val tail = split.finish()
            if (tail.isNotEmpty()) tts.speak(tail, language)
        } else {
            val whole = chat.reply(text)
            answer.append(whole)
            if (whole.isNotBlank()) tts.speak(whole, language)
        }
        state = Dialogue.Idle
        return answer.toString()
    }
}
