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
import com.geeui.voiceemo.TtsEngine
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
    private val paint: ((String) -> ByteArray)? = null,
    private val onPicture: ((ByteArray) -> Unit)? = null,
    private val onDrawing: (String) -> Unit = {},
    private val onDrawn: (Boolean) -> Unit = {},
    /** True while Lemonade is still painting. Noise must not fall through to chat. */
    private val drawBusy: () -> Boolean = { false },
    private val releaseDraw: () -> Unit = {},
) {
    private val skills = SkillRouter(bus, charging)
    var state: Dialogue = Dialogue.Idle
        private set

    fun onUserText(text: String): String {
        state = Dialogue.Listening
        if (ownLine(text)) {
            state = Dialogue.Idle
            return ""
        }
        val explicit = if (paint != null) DrawAsk.subject(text) else null
        if (paint != null && DrawAsk.leave(text)) {
            releaseDraw()
        } else if (explicit != null) {
            return paintOf(explicit)
        }
        val hit = SkillLexicon.match(text)
        if (hit != null && skills.handle(hit)) {
            state = Dialogue.Idle
            return ""
        }
        if (paint != null && drawBusy()) {
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

    /** The speaker says this. Hearing it back must not start another drawing. */
    private fun ownLine(text: String): Boolean {
        val key = text.trim().lowercase().replace(Regex("[!?.]+"), " ").replace(Regex("\\s+"), " ").trim()
        return key == "je te le dessine" || key.startsWith("je te le dessine ")
    }

    private fun paintOf(subject: String): String {
        state = Dialogue.Speaking
        plan?.engine = TtsEngine.KOKORO
        plan?.emotion = "neutral"
        tts.whenAudible(null)
        onDrawing(subject)
        val say = "Je te le dessine."
        tts.speak(say, language)
        val painter = paint
        if (painter == null) {
            state = Dialogue.Idle
            return say
        }
        Thread {
            val png = try {
                painter(subject)
            } catch (_: Exception) {
                ByteArray(0)
            }
            if (png.isNotEmpty()) onPicture?.invoke(png)
            onDrawn(png.isNotEmpty())
        }.apply { name = "geeui-draw"; isDaemon = true }.start()
        state = Dialogue.Idle
        return say
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
