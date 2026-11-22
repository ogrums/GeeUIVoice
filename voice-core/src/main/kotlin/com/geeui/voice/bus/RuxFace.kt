package com.geeui.voice.bus

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Who draws the robot face.
 * - [GEEUIFACE] (default): the pre-recorded clips (GeeUIFace through the bus, and our own TextureView).
 * - [RUXFACE]: the vector face of the RuxFace app (com.ogrums.ruxface, OpenGL), driven by broadcasts.
 *   If RuxFace is not installed, everything falls back to [GEEUIFACE].
 */
enum class FaceBackend {
    GEEUIFACE, RUXFACE;

    companion object {
        /** `ruxface`, `rux`, `gl`, `opengl` → [RUXFACE]; anything else (empty included) → [GEEUIFACE]. */
        fun parse(raw: String?): FaceBackend = when (raw?.trim()?.lowercase()) {
            "ruxface", "rux", "gl", "opengl", "vector", "vectoriel" -> RUXFACE
            else -> GEEUIFACE
        }
    }
}

/** What the RuxFace app listens to (see the RuxFace README, « Pilotage »). Same face ids hNNNN as GeeUIFace. */
object RuxFaceProtocol {
    const val PACKAGE = "com.ogrums.ruxface"
    const val ACTIVITY = "com.ogrums.ruxface.MainActivity"
    /** extras: `face` (hNNNN, mapped by RuxFace's FaceTags), `state` (RuxFace state id). */
    const val ACTION_STATE = "com.ogrums.ruxface.STATE"
    /** extras: `level` (float 0..1, voice volume → mouth), `hold` (int ms). */
    const val ACTION_LEVEL = "com.ogrums.ruxface.LEVEL"
    /** extra: `json`, e.g. {"eyeR":0.5,"eyeL":1,"mouth":"yawn"} or {"preset":"love","action":"wink"}. */
    const val ACTION_PARAMS = "com.ogrums.ruxface.PARAMS"
    /** Level updates are sent every [LEVEL_PERIOD_MS] while speaking; each holds a bit longer. */
    const val LEVEL_PERIOD_MS = 40L
    const val LEVEL_HOLD_MS = 160
    /** The face is sent again after this delay even if unchanged (RuxFace may have been restarted). */
    const val RESEND_MS = 5_000L
}

/**
 * The face on screen for the dialogue state, as VoiceActivity computes it for its own clips:
 * [dialogueFace] plus the « afterglow » (talk_face=speak: the answer's emotion stays [afterglowMs]
 * after the speech, before the rest face). Used by the RuxFace backend, which has no activity of ours on top.
 */
class FaceDirector(private val afterglowMs: Long = 3_000L) {
    private var speechEmotion = ""
    private var wasPlaying = false
    private var speechEndedAt = 0L

    fun next(mode: String, playing: Boolean, pose: String, rest: String, now: Long): String {
        val p = pose.ifBlank { rest }
        val face = dialogueFace(mode, playing, p)
        val dialogue = DialogueFaces
        if (playing && dialogue.isEmotion(p)) speechEmotion = p
        if (wasPlaying && !playing) speechEndedAt = now
        if (!wasPlaying && playing) speechEndedAt = 0L
        wasPlaying = playing
        if (!dialogue.speakDuringTalk || speechEmotion.isBlank()) return face
        if (mode == "hear" || mode == "think" || playing) return face
        if (speechEndedAt > 0L && now - speechEndedAt < afterglowMs) return speechEmotion
        if (speechEndedAt > 0L) speechEmotion = ""
        return face
    }
}

/**
 * Loudness of a TTS clip over time, for the mouth: one value 0..1 per [windowMs] of audio.
 * RMS in dBFS mapped linearly from [floorDb] (closed) to [topDb] (wide open).
 */
class SpeechEnvelope private constructor(val levels: FloatArray, val windowMs: Int) {
    /** Level at [positionMs] of playback (0 before / after the clip). */
    fun at(positionMs: Int): Float {
        if (positionMs < 0) return 0f
        val i = positionMs / windowMs
        return if (i < levels.size) levels[i] else 0f
    }

    val durationMs: Int get() = levels.size * windowMs

    companion object {
        /** 16-bit little-endian PCM (interleaved if [channels] > 1). */
        fun fromPcm16(pcm: ByteArray, rate: Int, channels: Int, windowMs: Int = 40,
                      floorDb: Double = -45.0, topDb: Double = -12.0): SpeechEnvelope {
            val frame = 2 * channels.coerceAtLeast(1)
            val perWin = (rate.coerceAtLeast(1) * windowMs / 1000).coerceAtLeast(1)
            val frames = pcm.size / frame
            val n = (frames + perWin - 1) / perWin
            val out = FloatArray(n)
            for (w in 0 until n) {
                var sum = 0.0; var count = 0
                val end = minOf(frames, (w + 1) * perWin)
                for (f in w * perWin until end) {
                    val at = f * frame   // first channel is enough for a mouth
                    val s = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xff)).toShort().toDouble() / 32768.0
                    sum += s * s; count++
                }
                val rms = if (count > 0) sqrt(sum / count) else 0.0
                val db = if (rms > 1e-9) 20.0 * log10(rms) else -120.0
                out[w] = ((db - floorDb) / (topDb - floorDb)).coerceIn(0.0, 1.0).toFloat()
            }
            return SpeechEnvelope(out, windowMs)
        }

        /** A RIFF/WAVE PCM 16-bit file; null for anything else (mp3, ogg, float…). */
        fun fromWav(bytes: ByteArray, windowMs: Int = 40): SpeechEnvelope? {
            if (bytes.size < 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            fun le16(at: Int) = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)
            fun le32(at: Int) = le16(at) or (le16(at + 2) shl 16)
            var at = 12
            var channels = 1; var rate = 0; var bits = 0; var format = 0
            while (at + 8 <= bytes.size) {
                val id = String(bytes, at, 4, Charsets.US_ASCII)
                val size = le32(at + 4)
                val body = at + 8
                if (id == "fmt " && body + 16 <= bytes.size) {
                    format = le16(body); channels = le16(body + 2); rate = le32(body + 4); bits = le16(body + 14)
                } else if (id == "data") {
                    if (format != 1 || bits != 16 || rate <= 0) return null
                    val end = if (size <= 0 || body + size > bytes.size) bytes.size else body + size
                    return fromPcm16(bytes.copyOfRange(body, end), rate, channels, windowMs)
                }
                if (size < 0) return null
                at = body + size + (size and 1)
            }
            return null
        }
    }
}
