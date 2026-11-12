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
            chat.stream(text) { piece -> delta(piece) }
            answer.append(tags.finish().text)
        } else {
            delta(chat.reply(text))
            answer.append(tags.finish().text)
        }
        // Always look at a trailing emoji, even when a [happy] tag is already present.
        // Previously a leading tag skipped the cue, and a tag alone stayed under the
        // fusion threshold, so a reply like "[happy] 😄" left the face at h0059.
        val cue = com.geeui.voiceemo.EmojiEmotion.cue(answer.toString())
        if (cue?.face != null && nuanceFace == null) nuanceFace = cue.face
        if (!tagged && cue != null) {
            tagged = true
            if (cue.emotion != Emotion.NEUTRAL) replyEmotion = cue.emotion
        }
        val whole = spoken(answer.toString())
        var moved = false
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
            // Apply the pose now, not only when TTS becomes audible: a reply that is
            // only an emoji has nothing to speak, so whenAudible would never fire.
            if (pose.faceId != IDLE_FACE) {
                bus.applyPose(pose)
                moved = true
            }
            tts.whenAudible {
                if (pose.faceId != IDLE_FACE) bus.applyPose(pose)
            }
        }
        if (whole.isNotEmpty()) tts.speak(whole, language)
        // Hold the emotion face for the whole clip (speak is blocking) plus a short
        // tail, then rest. Do not start the rest timer at the beginning of speech.
        if (moved) {
            pause(PoseTiming.REST_AFTER_MS)
            bus.standAtAttention()
        }
        state = Dialogue.Idle
        return whole
    }

    /** Kokoro reads '*' and emoji aloud. Keep letters, spaces and normal punctuation. */
    private fun spoken(raw: String): String {
        var s = com.geeui.voiceemo.EmojiEmotion.strip(raw)
        s = s.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)")) { it.groupValues[1] }
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")
        s = s.replace(Regex("[*_`~]+"), "")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    private companion object {
        const val EMOJI_SCORE = 0.7
    }
}
