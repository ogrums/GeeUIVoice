package com.geeui.voice.session

import com.geeui.voice.bus.RobotBus
import com.geeui.voice.bus.PoseTiming
import com.geeui.voice.bus.applyPose
import com.geeui.voice.bus.standAtAttention
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.StreamingChat
import com.geeui.voice.engine.TextToSpeech
import com.geeui.voice.engine.TtsPlan
import com.geeui.voice.skill.SkillLexicon
import com.geeui.voice.skill.SkillRouter
import com.geeui.voiceemo.Affect
import com.geeui.voiceemo.EmotionPipeline
import com.geeui.voiceemo.EmotionTurn
import com.geeui.voiceemo.Mood
import com.geeui.voiceemo.TextAffect

enum class Dialogue { Idle, Listening, Speaking }

/**
 * One spoken turn. A known phrase hits the robot and does not call the LLM
 * or the emotion pose. Otherwise the answer is spoken in one clip.
 */
class VoiceSession(
    private val bus: RobotBus,
    private val tts: TextToSpeech,
    private val chat: Chat,
    private val language: String = "fr",
    charging: () -> Boolean = { false },
    private val mood: Mood? = null,
    private val onEmotion: ((EmotionTurn) -> Unit)? = null,
    private val audioAffect: () -> Affect = { Affect.UNKNOWN },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sidecarOkAt: () -> Long = { 0L },
    private val plan: TtsPlan? = null,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
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
        var moved = false
        if (mood != null) {
            val turn = EmotionPipeline.turn(
                say = whole,
                audio = audioAffect(),
                text = TextAffect.of(text),
                mood = mood,
                now = now(),
                sidecarOkAt = sidecarOkAt(),
            )
            plan?.engine = turn.engine
            plan?.emotion = turn.mood.emotion.name.lowercase()
            onEmotion?.invoke(turn)
            tts.whenAudible {
                moved = true
                bus.applyPose(turn.pose)
            }
        }
        if (whole.isNotEmpty()) tts.speak(whole, language)
        if (moved) {
            pause(PoseTiming.REST_AFTER_MS)
            bus.standAtAttention()
        }
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
