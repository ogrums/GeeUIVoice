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
    /** Action kinds allowed for the person who just spoke (sidecar allowed_actions); null = all. */
    private val allowedActions: () -> Set<String>? = { null },
) {
    private val skills = SkillRouter(bus, charging)
    /** Walk or go home, waiting for « oui ». */
    private var pending: RobotAction? = null
    private var pendingAt = 0L
    var state: Dialogue = Dialogue.Idle
        private set

    fun onUserText(text: String): String {
        state = Dialogue.Listening
        val waiting = pending
        pending = null
        if (waiting != null && now() - pendingAt <= CONFIRM_MS) {
            if (ActionTags.isYes(text)) {
                if (allowed(waiting.kind)) run(waiting) else say(REFUSED)
                state = Dialogue.Idle
                return ""
            }
            if (NO.containsMatchIn(text)) {
                say(CANCELLED)
                state = Dialogue.Idle
                return ""
            }
        }
        val hit = SkillLexicon.match(text)
        if (hit != null && !allowed(kindOf(hit.id))) {
            say(REFUSED)
            state = Dialogue.Idle
            return ""
        }
        if (hit != null && skills.handle(hit)) {
            state = Dialogue.Idle
            return ""
        }
        state = Dialogue.Speaking
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
            chat.stream(text) { piece -> delta(piece) }
            answer.append(tags.finish().text)
        } else {
            delta(chat.reply(text))
            answer.append(tags.finish().text)
        }
        val (withoutActions, actions) = ActionTags.split(answer.toString())
        val whole = spoken(withoutActions)
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
        // AGENTS.md: one action tag per reply. Others are dropped.
        actions.firstOrNull()?.let { act(it) }
        state = Dialogue.Idle
        return whole
    }

    private fun allowed(kind: String): Boolean = allowedActions()?.contains(kind) ?: true

    private fun act(action: RobotAction) {
        when {
            !allowed(action.kind) -> say(REFUSED)
            action.needsConfirmation -> {
                say(ASK)
                pending = action
                pendingAt = now()
            }
            else -> run(action)
        }
    }

    private fun run(action: RobotAction) {
        val hit = action.hit
        if (hit != null) {
            skills.handle(hit)
        } else if (action.light != null) {
            bus.antennaLight(action.light != 0, action.light)
        }
    }

    private fun say(line: String) {
        state = Dialogue.Speaking
        tts.speak(line, language)
    }

    companion object {
        const val CONFIRM_MS = 10_000L
        const val ASK = "Je peux y aller ?"
        const val CANCELLED = "D'accord, je ne bouge pas."
        const val REFUSED = "Je n'ai pas le droit de faire ça pour toi."
        private val NO = Regex("^\\W*(non|nan|stop|attends|annule|pas maintenant)\\b", RegexOption.IGNORE_CASE)

        /** Local phrase skill → action kind, for allowed_actions. */
        fun kindOf(skill: String): String = when (skill) {
            "actin" -> "move"
            "goHome" -> "home"
            "takePhoto" -> "photo"
            "volumeUp", "volumeDown", "volumemax", "volumemix", "volumeadjust" -> "volume"
            else -> "other"
        }
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
