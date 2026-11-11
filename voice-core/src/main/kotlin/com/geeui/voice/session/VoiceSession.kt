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
    private val fusion: com.geeui.voiceemo.EmotionFusion? = null,
    /** One JPEG on demand ("qu'est-ce que tu vois ?"), or null when the camera fails. Null = no vision. */
    private val camera: (() -> ByteArray?)? = null,
) {
    private val skills = SkillRouter(bus, charging)
    var state: Dialogue = Dialogue.Idle
        private set

    /** Takes the photo for a vision phrase and attaches it to the next chat turn. */
    private fun look(text: String): String {
        val shoot = camera ?: return text
        if (!VisionIntent.wants(text)) return text
        val jpeg = try {
            shoot()
        } catch (_: Exception) {
            null
        }
        if (jpeg == null || jpeg.isEmpty()) return VisionIntent.BLIND + text
        chat.attachImage(jpeg)
        return text
    }

    fun onUserText(text: String): String {
        state = Dialogue.Listening
        val hit = SkillLexicon.match(text)
        if (hit != null && skills.handle(hit)) {
            state = Dialogue.Idle
            return ""
        }
        state = Dialogue.Speaking
        val asked = look(text)
        val answer = StringBuilder()
        val tags = com.geeui.voiceemo.EmotionTagParser()
        var tagged = false
        var replyEmotion = com.geeui.voiceemo.Emotion.NEUTRAL
        fun delta(piece: String) {
            val parsed = tags.consume(piece)
            if (parsed.emotion != com.geeui.voiceemo.Emotion.NEUTRAL) {
                tagged = true
                replyEmotion = parsed.emotion
            }
            answer.append(parsed.text)
        }
        if (chat is StreamingChat) {
            chat.stream(asked) { piece -> delta(piece) }
            answer.append(tags.finish().text)
        } else {
            delta(chat.reply(asked))
            answer.append(tags.finish().text)
        }
        val whole = spoken(answer.toString())
        var moved = false
        if (mood != null) {
            val reaction = fusion?.fuse(audioAffect().emotion, audioAffect().confidence.toDouble(),
                replyEmotion, tagged)
            val turn = EmotionPipeline.turn(
                say = whole,
                audio = if (reaction == null) audioAffect() else com.geeui.voiceemo.Affect(
                    reaction.emotion,
                    reaction.score.toFloat().coerceIn(0f, 1f),
                ),
                // Fusion is the single arbiter; avoid a second Arbiter.merge pass.
                text = if (reaction == null) TextAffect.of(text) else Affect.UNKNOWN,
                mood = mood,
                now = now(),
                sidecarOkAt = sidecarOkAt(),
            )
            plan?.engine = turn.engine
            plan?.emotion = turn.mood.emotion.name.lowercase()
            // Same fused emotion that drives the face also bends the voice.
            plan?.prosody = if (reaction == null) com.geeui.voiceemo.Prosody.NEUTRAL
            else com.geeui.voiceemo.EmotionProsody.of(reaction.emotion, reaction.score)
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
