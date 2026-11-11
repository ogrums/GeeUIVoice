package com.geeui.voice.session

import com.geeui.voice.audio.pcm16ToWav
import com.geeui.voice.audio.readWav
import com.geeui.voice.audio.toMono16k
import com.geeui.voice.engine.SpeechToText
import com.geeui.voice.engine.TimingChat
import com.geeui.voice.engine.TimingTts

/**
 * Safe paths for the adb WAV hook. Anything else is refused, including `..` segments.
 * The recommended drop folder is the app's own external files directory.
 */
object WavTestAccess {
    const val ACTION = "com.geeui.voice.TEST_WAV"

    val prefixes = listOf(
        "/sdcard/geeui/",
        "/storage/emulated/0/geeui/",
        "/sdcard/Android/data/com.geeui.voice/",
        "/storage/emulated/0/Android/data/com.geeui.voice/",
        "/data/local/tmp/geeui/",
    )

    fun allowedPath(path: String): Boolean {
        if (path.isEmpty() || path.any { it == '\u0000' || it == '\n' || it == '\r' }) return false
        val n = path.replace('\\', '/')
        if (n.split('/').any { it == ".." }) return false
        if (!n.endsWith(".wav", ignoreCase = true)) return false
        return prefixes.any { n.startsWith(it) }
    }
}

data class WavTestTags(
    val face: String = "",
    val emotion: String = "",
    val actions: List<String> = emptyList(),
)

/** Sidecar emotion2vec label after STT, or empty when the sidecar did not answer. */
data class WavTestAudio(
    val emotion: String = "",
    val confidence: Double = 0.0,
)

data class WavTestTimings(
    val stt: Long = 0,
    val firstToken: Long = 0,
    val ttsFirstAudio: Long = 0,
    val total: Long = 0,
)

data class WavTestResult(
    val ok: Boolean,
    val path: String = "",
    val transcript: String = "",
    val reply: String = "",
    val rawReply: String = "",
    val tags: WavTestTags = WavTestTags(),
    val audio: WavTestAudio = WavTestAudio(),
    val timings: WavTestTimings = WavTestTimings(),
    val ttsFile: String = "",
    val error: String? = null,
) {
    fun toJson(): String = buildString {
        append("{\n")
        field("ok", ok, last = false)
        field("path", path)
        field("transcript", transcript)
        field("reply", reply)
        field("raw_reply", rawReply)
        append("  \"tags\": {\n")
        append("    \"face\": ").append(q(tags.face)).append(",\n")
        append("    \"emotion\": ").append(q(tags.emotion)).append(",\n")
        append("    \"actions\": [").append(tags.actions.joinToString(", ") { q(it) }).append("]\n")
        append("  },\n")
        append("  \"audio\": {\n")
        append("    \"emotion\": ").append(q(audio.emotion)).append(",\n")
        append("    \"confidence\": ").append(audio.confidence).append("\n")
        append("  },\n")
        append("  \"timings_ms\": {\n")
        append("    \"stt\": ").append(timings.stt).append(",\n")
        append("    \"first_token\": ").append(timings.firstToken).append(",\n")
        append("    \"tts_first_audio\": ").append(timings.ttsFirstAudio).append(",\n")
        append("    \"total\": ").append(timings.total).append("\n")
        append("  },\n")
        field("tts_file", ttsFile)
        append("  \"error\": ").append(if (error == null) "null" else q(error)).append("\n")
        append("}\n")
    }

    private fun StringBuilder.field(name: String, value: String, last: Boolean = false) {
        append("  \"").append(name).append("\": ").append(q(value))
        append(if (last) "\n" else ",\n")
    }

    private fun StringBuilder.field(name: String, value: Boolean, last: Boolean = false) {
        append("  \"").append(name).append("\": ").append(value)
        append(if (last) "\n" else ",\n")
    }

    companion object {
        fun fail(path: String, error: String) = WavTestResult(ok = false, path = path, error = error)
    }
}

internal fun q(value: String): String = buildString {
    append('"')
    for (c in value) when (c) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}

private val EMOTIONS = setOf("happy", "sad", "angry", "fear", "surprise", "neutral")
private val TAG = Regex("\\[([^\\]]{1,80})]")

/** Emotion tag at the start, other [tags] as actions (rappel, oubli, marche…). */
fun extractTags(raw: String, face: String = ""): WavTestTags {
    var emotion = ""
    val actions = mutableListOf<String>()
    for (m in TAG.findAll(raw)) {
        val body = m.groupValues[1].trim()
        if (body.isEmpty()) continue
        val key = body.substringBefore(':').trim().lowercase()
        if (emotion.isEmpty() && key in EMOTIONS) emotion = key
        else actions += body
    }
    return WavTestTags(face = face, emotion = emotion, actions = actions)
}

/**
 * One WAV file in place of the microphone: STT → chat → TTS, with per-stage timings.
 * VAD is skipped: the file is already a complete utterance.
 */
class WavTestTurn(
    private val stt: SpeechToText,
    private val session: VoiceSession,
    private val chat: TimingChat,
    private val tts: TimingTts,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val sampleRate: Int = 16_000,
) {
    fun run(
        wav: ByteArray,
        path: String = "",
        face: () -> String = { "" },
        audio: () -> WavTestAudio = { WavTestAudio() },
    ): WavTestResult {
        chat.reset()
        tts.reset()
        val t0 = now()
        val pcm = try {
            readWav(wav).toMono16k()
        } catch (e: Exception) {
            return WavTestResult.fail(path, e.message ?: "WAV illisible")
        }
        val tStt0 = now()
        val transcript = try {
            stt.transcribe(pcm16ToWav(pcm, sampleRate), sampleRate).trim()
        } catch (e: Exception) {
            return WavTestResult.fail(path, "STT: ${e.message ?: e.javaClass.simpleName}")
        }
        val sttMs = now() - tStt0
        val heardAudio = try {
            audio()
        } catch (_: Exception) {
            WavTestAudio()
        }
        if (transcript.isEmpty()) {
            return WavTestResult(
                ok = false,
                path = path,
                audio = heardAudio,
                timings = WavTestTimings(stt = sttMs, total = now() - t0),
                error = "transcription vide",
            )
        }
        val reply = try {
            session.onUserText(transcript)
        } catch (e: Exception) {
            return WavTestResult.fail(path, "chat/TTS: ${e.message ?: e.javaClass.simpleName}").copy(
                transcript = transcript,
                audio = heardAudio,
                timings = WavTestTimings(stt = sttMs, total = now() - t0),
            )
        }
        return WavTestResult(
            ok = true,
            path = path,
            transcript = transcript,
            reply = reply,
            rawReply = chat.raw,
            tags = extractTags(chat.raw, face()),
            audio = heardAudio,
            timings = WavTestTimings(
                stt = sttMs,
                firstToken = chat.firstTokenMs() ?: 0,
                ttsFirstAudio = tts.firstAudioMs() ?: 0,
                total = now() - t0,
            ),
        )
    }
}
