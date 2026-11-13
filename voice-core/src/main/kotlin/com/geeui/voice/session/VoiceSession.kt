package com.geeui.voice.session

import com.geeui.voice.bus.IDLE_FACE
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
import com.geeui.voiceemo.Emotion
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
    /** One JPEG on demand ("qu'est-ce que tu vois ?"), or null when the camera fails. Null = no vision. */
    private val camera: (() -> ByteArray?)? = null,
) {
    private val skills = SkillRouter(bus, charging)
    /** Walk or go home, waiting for « oui ». */
    private var pending: RobotAction? = null
    private var pendingAt = 0L
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
        val asked = look(text)
        val answer = StringBuilder()
        val tags = com.geeui.voiceemo.EmotionTagParser()
        var tagged = false
        var replyEmotion = Emotion.NEUTRAL
        var nuanceFace: String? = null
        fun delta(piece: String) {
            val parsed = tags.consume(piece)
            if (parsed.emotion != Emotion.NEUTRAL) {
                tagged = true
                replyEmotion = parsed.emotion
            }
            if (parsed.face != null) nuanceFace = parsed.face
            answer.append(parsed.text)
        }
        if (chat is StreamingChat) {
            chat.stream(asked) { piece -> delta(piece) }
            answer.append(tags.finish().text)
        } else {
            delta(chat.reply(asked))
            answer.append(tags.finish().text)
        }
        val (withoutActions, actions) = ActionTags.split(answer.toString())
        // Always look at a trailing emoji, even when a [happy] tag is already present.
        // Previously a leading tag skipped the cue, and a tag alone stayed under the
        // fusion threshold, so a reply like "[happy] 😄" left the face at h0059.
        val cue = com.geeui.voiceemo.EmojiEmotion.cue(withoutActions)
        if (cue?.face != null && nuanceFace == null) nuanceFace = cue.face
        if (!tagged && cue != null) {
            tagged = true
            if (cue.emotion != Emotion.NEUTRAL) replyEmotion = cue.emotion
        }
        val whole = spoken(withoutActions)
        var moved = false
        var pendingPose: com.geeui.voiceemo.SdkPose? = null
        if (mood != null) {
            val fused = fusion?.fuse(
                audioAffect().emotion,
                audioAffect().confidence.toDouble(),
                replyEmotion,
                tagged,
            )
            // Emoji (or a tag when fusion is missing) still shows a face if audio did not win.
            val reaction = when {
                fused != null && fused.emotion != Emotion.NEUTRAL -> fused
                cue != null && cue.emotion != Emotion.NEUTRAL ->
                    com.geeui.voiceemo.EmotionFusion.Result(cue.emotion, EMOJI_SCORE)
                tagged && replyEmotion != Emotion.NEUTRAL && fused == null ->
                    com.geeui.voiceemo.EmotionFusion.Result(replyEmotion, EMOJI_SCORE)
                else -> fused
            }
            val base = EmotionPipeline.turn(
                say = whole,
                audio = if (reaction == null) audioAffect() else Affect(
                    reaction.emotion,
                    reaction.score.toFloat().coerceIn(0f, 1f),
                ),
                // Fusion is the single arbiter; avoid a second Arbiter.merge pass.
                text = when {
                    reaction != null -> Affect.UNKNOWN
                    cue != null && cue.emotion != Emotion.NEUTRAL ->
                        Affect(cue.emotion, EMOJI_SCORE.toFloat())
                    else -> TextAffect.of(text)
                },
                mood = mood,
                now = now(),
                sidecarOkAt = sidecarOkAt(),
            )
            // The fused (or emoji) emotion drives the face, not a diluted mood leftover.
            val poseEmotion = when {
                reaction != null && reaction.emotion != Emotion.NEUTRAL -> reaction.emotion
                else -> if (base.mood.confidence >= 0.25f) base.mood.emotion else Emotion.NEUTRAL
            }
            var pose = com.geeui.voiceemo.SdkMap.pose(poseEmotion)
            val nuance = nuanceFace
            val nuanceEmotion = com.geeui.voiceemo.FaceCodes.NUANCE.values
                .firstOrNull { it.second == nuance }?.first
            // A finer face (🥰 😂 🤔 or [love]…) when audio did not pick a different emotion.
            if (nuance != null && (poseEmotion == Emotion.NEUTRAL || poseEmotion == nuanceEmotion || nuanceEmotion == null)) {
                pose = pose.copy(faceId = nuance)
            }
            val turn = base.copy(pose = pose)
            plan?.engine = turn.engine
            plan?.emotion = poseEmotion.name.lowercase()
            plan?.prosody = if (reaction == null) com.geeui.voiceemo.Prosody.NEUTRAL
            else com.geeui.voiceemo.EmotionProsody.of(reaction.emotion, reaction.score)
            onEmotion?.invoke(turn)
            // Keep the thinking face until TTS starts. An emoji-only reply has
            // nothing to play, so apply immediately; EchoTts (tests) never
            // fires whenAudible, so we also apply after speak() if needed.
            tts.whenAudible {
                if (pose.faceId != IDLE_FACE) {
                    bus.applyPose(pose)
                    moved = true
                }
            }
            if (whole.isEmpty() && pose.faceId != IDLE_FACE) {
                bus.applyPose(pose)
                moved = true
            }
            pendingPose = pose
        }
        if (whole.isNotEmpty()) tts.speak(whole, language)
        if (!moved) {
            val pose = pendingPose
            if (pose != null && pose.faceId != IDLE_FACE && whole.isNotEmpty()) {
                bus.applyPose(pose)
                moved = true
            }
        }
        // Hold the emotion face for the whole clip (speak is blocking) plus a short
        // tail, then rest. Do not start the rest timer at the beginning of speech.
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

    /**
     * Initiative from the sidecar (reminder, follow-up): say [text] as is, with its leading
     * emotion tag driving face and voice, without calling the LLM or the skills.
     * Also used for the short confirmation / refusal lines of action tags.
     */
    fun say(text: String): String {
        state = Dialogue.Speaking
        val tags = com.geeui.voiceemo.EmotionTagParser()
        val head = tags.consume(text)
        val whole = spoken(head.text + tags.finish().text)
        val emotion = head.emotion
        var moved = false
        if (mood != null && emotion != com.geeui.voiceemo.Emotion.NEUTRAL) {
            plan?.emotion = emotion.name.lowercase()
            plan?.prosody = com.geeui.voiceemo.EmotionProsody.of(emotion, INITIATIVE_SCORE)
            val pose = com.geeui.voiceemo.SdkMap.pose(emotion)
            tts.whenAudible {
                moved = true
                bus.applyPose(pose)
            }
        }
        if (whole.isNotEmpty()) tts.speak(whole, language)
        if (moved) {
            pause(PoseTiming.REST_AFTER_MS)
            bus.standAtAttention()
        }
        plan?.prosody = com.geeui.voiceemo.Prosody.NEUTRAL
        state = Dialogue.Idle
        return whole
    }

    /** Kokoro reads '*' and emoji aloud. Keep letters, spaces and normal punctuation. */
    private fun spoken(raw: String): String {
        // Sidecar tags such as [rappel:2026-11-10T19:30|linge] are read by the sidecar, never spoken.
        var s = raw.replace(Regex("\\[[a-zA-Z_]+:[^\\]\\n]{0,120}]"), "")
        s = com.geeui.voiceemo.EmojiEmotion.strip(s)
        s = s.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)")) { it.groupValues[1] }
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        s = s.replace(Regex("[*_`~]+"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
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

        private const val INITIATIVE_SCORE = 0.7
        private const val EMOJI_SCORE = 0.7
    }
}
