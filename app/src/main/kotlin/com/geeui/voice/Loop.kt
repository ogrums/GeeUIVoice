package com.geeui.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.content.Context
import com.geeui.voice.audio.EnergyVad
import com.geeui.voice.audio.VadConfig
import com.geeui.voice.engine.LemonadeChat
import com.geeui.voice.engine.LemonadeClient
import com.geeui.voice.engine.LemonadeStt
import com.geeui.voice.engine.LemonadeTts
import com.geeui.voice.engine.TextToSpeech
import com.geeui.voice.session.LiveTurn
import com.geeui.voice.session.VoiceSession
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mic → VAD → Lemonade STT → skill or streaming chat → Lemonade TTS → speaker.
 * One thread. Lex must not hold AudioRecord at the same time.
 * 16 kHz mono, 20 ms frames.
 */
class VoiceLoop(
    private val bus: com.geeui.voice.bus.RobotBus,
    baseUrl: String,
    private val cacheDir: File,
    vad: EnergyVad = VadConfig().toVad(),
    sttModel: String = "whisper-base",
    chatModel: String = "",
    ttsModel: String = "kokoro",
    audio: Context,
) {
    private val client = LemonadeClient(
        baseUrl,
        sttModel = sttModel,
        chatModel = chatModel,
        ttsModel = ttsModel,
    )
    private val tts = PlayingTts(LemonadeTts(client), cacheDir, audio)
    private val session = VoiceSession(bus, tts, LemonadeChat(client))
    private val live = LiveTurn(vad, LemonadeStt(client), tts, session)
    private val turns = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        live.onUserText = { VoiceHud.line = it; VoiceHud.mode = "think" }
        live.onAnswer = { VoiceHud.line = it; VoiceHud.mode = "talk" }
        live.onListen = { VoiceHud.mode = "hear" }
        live.onIdle = { VoiceHud.mode = "idle" }
        live.worker = { job -> turns.execute(job) }
        thread = Thread({
            val used = try {
                client.resolveStt()
            } catch (_: Exception) {
                "whisper-base"
            }
            val llm = try {
                client.resolveChat().ifBlank { "aucun" }
            } catch (_: Exception) {
                "aucun"
            }
            val voice = try {
                client.resolveTts().ifBlank { "aucun" }
            } catch (_: Exception) {
                "aucun"
            }
            VoiceHud.line = "stt $used · llm $llm · tts $voice"
            val rate = 16_000
            val frame = 320
            val min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (min <= 0) {
                VoiceHud.line = "micro refusé (taux 16 kHz)"
                VoiceHud.mic = false
                running.set(false)
                return@Thread
            }
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min.coerceAtLeast(frame * 4),
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                VoiceHud.line = "micro non initialisé (permission ou déjà pris)"
                VoiceHud.mic = false
                rec.release()
                running.set(false)
                return@Thread
            }
            val buf = ShortArray(frame)
            try {
                rec.startRecording()
                VoiceHud.mic = true
                while (running.get()) {
                    val n = rec.read(buf, 0, frame)
                    if (n == frame) {
                        VoiceHud.level = levelOf(buf)
                        live.onFrame(buf.copyOf())
                    }
                }
            } finally {
                try {
                    rec.stop()
                } catch (_: IllegalStateException) {
                }
                rec.release()
                VoiceHud.mic = false
            }
        }, "geeui-voice").also { it.start() }
    }

    fun stop() {
        running.set(false)
        tts.stop()
        VoiceHud.mode = "idle"
        thread?.join(500)
    }
}

private fun levelOf(frame: ShortArray): Float {
    var acc = 0.0
    for (s in frame) acc += s * s.toDouble()
    val rms = kotlin.math.sqrt(acc / frame.size)
    return (rms / 4000.0).toFloat().coerceIn(0f, 1f)
}

private data class Wav(val rate: Int, val channels: Int, val pcm: ByteArray, val raw: ByteArray)

/** Plays each Lemonade clip on AudioTrack if it is PCM WAV, else MediaPlayer. */
class PlayingTts(
    private val remote: LemonadeTts,
    private val cacheDir: File,
    private val audio: Context,
) : TextToSpeech {
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null
    private val cancelled = AtomicBoolean(false)
    private val gate = Any()

    private var started = false

    override fun speak(text: String, language: String) {
        cancelled.set(false)
        VoiceHud.mode = "talk"
        ensureVolume()
        remote.speak(text, language)
        val bytes = remote.lastAudio
        if (bytes.isEmpty() || bytes[0] == '{'.code.toByte()) {
            if (VoiceHud.mode == "talk") VoiceHud.mode = "idle"
            return
        }
        val wav = parseWav(bytes)
        when {
            wav != null -> playPcm(wav)
            bytes[0] == 'R'.code.toByte() -> VoiceHud.line = "wav illisible"
            encoded(bytes) -> playFile(bytes)
            else -> playPcm(Wav(24_000, 1, bytes, bytes))
        }
    }

    override fun stop() {
        cancelled.set(true)
        remote.stop()
        synchronized(gate) {
            releasePlayer()
            releaseTrack()
        }
    }

    private fun releasePlayer() {
        val mp = player
        player = null
        if (mp == null) return
        if (started) {
            try {
                mp.stop()
            } catch (_: IllegalStateException) {
            }
        }
        started = false
        try {
            mp.release()
        } catch (_: Exception) {
        }
    }

    /** This ROM rejects empty AudioAttributes and then mutes the stream. Map the music stream explicitly. */
    @Suppress("DEPRECATION")
    private fun speechAttrs(): AudioAttributes =
        AudioAttributes.Builder()
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .build()

    /** Do not take audio focus: that muted the rest of the robot when playback failed. */
    private fun ensureVolume() {
        val am = audio.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (max / 2).coerceAtLeast(1), 0)
        }
    }

    private fun releaseTrack() {
        val t = track
        track = null
        if (t == null) return
        try {
            t.pause()
            t.flush()
        } catch (_: IllegalStateException) {
        }
        try {
            t.release()
        } catch (_: Exception) {
        }
    }

    private fun playPcm(wav: Wav) {
        val mask = if (wav.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(wav.rate, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(wav.pcm.size)
        val t = AudioTrack.Builder()
            .setAudioAttributes(speechAttrs())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(wav.rate)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(min)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            t.release()
            playFile(wav.raw)
            return
        }
        val wrote = t.write(wav.pcm, 0, wav.pcm.size)
        if (wrote <= 0) {
            t.release()
            playFile(wav.raw)
            return
        }
        track = t
        try {
            t.play()
        } catch (_: IllegalStateException) {
            synchronized(gate) { if (track === t) releaseTrack() }
            return
        }
        val frames = wrote / (2 * wav.channels)
        while (!cancelled.get() && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < frames) {
            Thread.sleep(20)
        }
        if (!cancelled.get()) Thread.sleep(80)
        if (VoiceHud.mode == "talk") VoiceHud.mode = "idle"
        synchronized(gate) {
            if (track === t) releaseTrack()
        }
    }

    private fun playFile(bytes: ByteArray) {
        val ext = when {
            bytes.size > 3 && bytes[0] == 'R'.code.toByte() -> "wav"
            bytes.size > 3 && bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() -> "ogg"
            else -> "mp3"
        }
        val file = File(cacheDir, "tts.$ext")
        file.writeBytes(bytes)
        val mp = MediaPlayer()
        try {
            synchronized(gate) {
                if (cancelled.get()) {
                    mp.release()
                    return
                }
                player = mp
                started = false
                @Suppress("DEPRECATION")
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
                mp.setVolume(1f, 1f)
                mp.setOnErrorListener { _, what, extra ->
                    VoiceHud.line = "audio $what/$extra"
                    true
                }
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.start()
                started = true
            }
            while (!cancelled.get()) {
                val playing = try {
                    synchronized(gate) { started && player === mp && mp.isPlaying }
                } catch (_: IllegalStateException) {
                    false
                }
                if (!playing) break
                Thread.sleep(20)
            }
        } catch (e: Exception) {
            VoiceHud.line = "audio: ${e.javaClass.simpleName}"
        } finally {
            if (VoiceHud.mode == "talk") VoiceHud.mode = "idle"
            synchronized(gate) {
                if (player === mp) releasePlayer()
            }
        }
    }

    private fun parseWav(wav: ByteArray): Wav? {
        if (wav.size < 44 || wav[0] != 'R'.code.toByte()) return null
        var rate = 0
        var channels = 1
        var bits = 0
        var format = 0
        var pcm: ByteArray? = null
        var i = 12
        while (i + 8 <= wav.size) {
            val id = String(wav, i, 4, Charsets.US_ASCII)
            val size = leInt(wav, i + 4)
            val start = i + 8
            if (size < 0 || start > wav.size) break
            if (id == "fmt " && start + 16 <= wav.size) {
                format = leShort(wav, start)
                channels = leShort(wav, start + 2)
                rate = leInt(wav, start + 4)
                bits = leShort(wav, start + 14)
                if (format == 0xFFFE && start + 26 <= wav.size) format = leShort(wav, start + 24)
            } else if (id == "data" && size > 0) {
                pcm = wav.copyOfRange(start, (start + size).coerceAtMost(wav.size))
                break
            }
            i = start + size + (size and 1)
        }
        val data = pcm ?: return null
        if (channels !in 1..2 || rate !in 8_000..48_000) return null
        val samples = when {
            format == 1 && bits == 16 -> data
            format == 3 && bits == 32 -> floatToS16(data)
            format == 1 && bits == 32 -> int32ToS16(data)
            else -> return null
        }
        if (samples.isEmpty() || samples.size > 2_000_000) return null
        return Wav(rate, channels, samples, wav)
    }

    private fun encoded(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val b0 = bytes[0].toInt() and 0xff
        val b1 = bytes[1].toInt() and 0xff
        if (b0 == 0xff && (b1 and 0xe0) == 0xe0) return true
        val head = String(bytes, 0, 4.coerceAtMost(bytes.size), Charsets.US_ASCII)
        return head.startsWith("ID3") || head.startsWith("OggS") || head.startsWith("fLaC")
    }

    private fun floatToS16(src: ByteArray): ByteArray {
        val n = src.size / 4
        val out = ByteArray(n * 2)
        val buf = java.nio.ByteBuffer.wrap(src).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            val sample = (buf.getFloat(i * 4).coerceIn(-1f, 1f) * 32767f).toInt()
            out[i * 2] = (sample and 0xff).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun int32ToS16(src: ByteArray): ByteArray {
        val n = src.size / 4
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            out[i * 2] = src[i * 4 + 2]
            out[i * 2 + 1] = src[i * 4 + 3]
        }
        return out
    }

    private fun leShort(b: ByteArray, at: Int): Int {
        if (at + 1 >= b.size) return 0
        return (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
    }

    private fun leInt(b: ByteArray, at: Int): Int {
        if (at + 3 >= b.size) return 0
        return (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)
    }
}
