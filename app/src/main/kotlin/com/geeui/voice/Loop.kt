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
import com.geeui.voice.engine.CosyTts
import com.geeui.voice.engine.LemonadeChat
import com.geeui.voice.engine.LemonadeClient
import com.geeui.voice.engine.LemonadeStt
import com.geeui.voice.engine.LemonadeTts
import com.geeui.voice.engine.PlannedTts
import com.geeui.voice.engine.TextToSpeech
import com.geeui.voice.engine.TtsPlan
import com.geeui.voice.session.LiveTurn
import com.geeui.voice.session.VoiceSession
import com.geeui.voiceemo.Mood
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    voice: String = "",
    prompt: String = com.geeui.voice.engine.LemonadeClient.SPOKEN,
    audio: Context,
    private val sidecar: String = com.geeui.voiceemo.HostConfig.SIDECAR,
    cosyModel: String = CosyTts.MODEL,
) {
    private val client = LemonadeClient(
        baseUrl,
        sttModel = sttModel,
        chatModel = chatModel,
        ttsModel = ttsModel,
        systemPrompt = prompt,
    )
    private val plan = TtsPlan()
    private val sidecarOk = AtomicLong(0)
    private val kokoro = LemonadeTts(client, voice)
    private val cosy = CosyTts(plan) { text, emotion ->
        postCosy(sidecar, cosyModel, text, emotion)
    }
    private val clips = PlannedTts(plan, kokoro, cosy)
    private val tts = PlayingTts(clips, cacheDir, audio)
    private val session = VoiceSession(
        bus,
        tts,
        LemonadeChat(client),
        mood = Mood(),
        plan = plan,
        sidecarOkAt = {
            val now = System.currentTimeMillis()
            if (sidecar.isNotBlank() && now - sidecarOk.get() > 5_000L) pingSidecar(sidecar)
            sidecarOk.get()
        },
    )
    private val live = LiveTurn(vad, LemonadeStt(client), tts, session)
    private val turns = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        live.onUserText = {
            VoiceHud.heard = it
            VoiceHud.answer = ""
            VoiceHud.line = it
            VoiceHud.mode = "think"
        }
        live.onAnswer = {
            VoiceHud.answer = it
            VoiceHud.line = it
            VoiceHud.mode = "talk"
        }
        live.onListen = { VoiceHud.mode = "hear" }
        live.onIdle = { VoiceHud.mode = "idle" }
        live.allowBargeIn = {
            !VoiceHud.playing && System.currentTimeMillis() >= VoiceHud.hearAfter
        }
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
            if (sidecar.isNotBlank()) pingSidecar(sidecar)
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

    private fun pingSidecar(base: String) {
        val code = try {
            val conn = URL(base.trimEnd('/') + "/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 800
            conn.readTimeout = 800
            conn.requestMethod = "GET"
            conn.responseCode
        } catch (_: Exception) {
            0
        }
        if (code in 200..299) sidecarOk.set(System.currentTimeMillis())
    }
}

internal fun postCosy(base: String, model: String, text: String, emotion: String): ByteArray {
    if (base.isBlank()) return ByteArray(0)
    val conn = URL(base.trimEnd('/') + "/v1/audio/speech").openConnection() as HttpURLConnection
    conn.connectTimeout = 2_000
    conn.readTimeout = 60_000
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    val body = CosyTts.body(text, emotion, model)
    conn.outputStream.use { it.write(body.toByteArray()) }
    if (conn.responseCode !in 200..299) return ByteArray(0)
    return conn.inputStream.use { it.readBytes() }
}

private fun levelOf(frame: ShortArray): Float {
    var acc = 0.0
    for (s in frame) acc += s * s.toDouble()
    val rms = kotlin.math.sqrt(acc / frame.size)
    return (rms / 4000.0).toFloat().coerceIn(0f, 1f)
}

private data class Wav(val rate: Int, val channels: Int, val pcm: ByteArray, val raw: ByteArray)

/** Plays each clip on AudioTrack if it is PCM WAV, else MediaPlayer. */
class PlayingTts(
    private val remote: com.geeui.voice.engine.ClipTts,
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
        try {
            remote.speak(text, language)
        } catch (e: Exception) {
            VoiceHud.line = e.message?.take(80) ?: "tts erreur"
            VoiceHud.mode = "idle"
            return
        }
        val bytes = remote.lastAudio
        if (bytes.isEmpty() || bytes[0] == '{'.code.toByte()) {
            VoiceHud.diag = if (bytes.isEmpty()) "serveur vide" else "serveur: ${String(bytes).take(90)}"
            VoiceHud.line = VoiceHud.diag
            if (VoiceHud.mode == "talk") VoiceHud.mode = "idle"
            return
        }
        VoiceHud.diag = "serveur ${bytes.size} o ${kindOf(bytes)}"
        VoiceHud.line = VoiceHud.diag
        val file = clipFile(bytes)
        playWithPlayer(file)
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

    /** Same path as LTPAudioService / LetianpaiPlayer: a file, then MediaPlayer. AudioTrack left the rk817 amp off. */
    private fun clipFile(bytes: ByteArray): File {
        if (encoded(bytes)) {
            val ext = if (bytes[0] == 'O'.code.toByte()) "ogg" else "mp3"
            return File(cacheDir, "tts.$ext").also { it.writeBytes(bytes) }
        }
        val wav = parseWav(bytes)
        val file = File(cacheDir, "tts.wav")
        val body = when {
            wav != null -> pcmToWav(wav.pcm, wav.rate, wav.channels)
            bytes.size > 12 && bytes[0] == 'R'.code.toByte() -> bytes
            else -> pcmToWav(bytes, 24_000, 1)
        }
        file.writeBytes(body)
        return file
    }

    private fun pcmToWav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        val header = ByteArray(44)
        val dataSize = pcm.size
        val block = channels * 2
        fun put(at: Int, s: String) = s.toByteArray().copyInto(header, at)
        fun le32(at: Int, v: Int) {
            header[at] = (v and 0xff).toByte()
            header[at + 1] = ((v shr 8) and 0xff).toByte()
            header[at + 2] = ((v shr 16) and 0xff).toByte()
            header[at + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun le16(at: Int, v: Int) {
            header[at] = (v and 0xff).toByte()
            header[at + 1] = ((v shr 8) and 0xff).toByte()
        }
        put(0, "RIFF")
        le32(4, 36 + dataSize)
        put(8, "WAVE")
        put(12, "fmt ")
        le32(16, 16)
        le16(20, 1)
        le16(22, channels)
        le32(24, rate)
        le32(28, rate * block)
        le16(32, block)
        le16(34, 16)
        put(36, "data")
        le32(40, dataSize)
        return header + pcm
    }

    private fun playWithPlayer(file: File) {
        val done = java.util.concurrent.CountDownLatch(1)
        val mp = MediaPlayer()
        val input = java.io.FileInputStream(file)
        try {
            synchronized(gate) {
                if (cancelled.get()) {
                    mp.release()
                    return
                }
                player = mp
                started = false
                mp.setOnCompletionListener { done.countDown() }
                mp.setOnErrorListener { _, what, extra ->
                    VoiceHud.diag = "audio $what/$extra"
                    VoiceHud.line = VoiceHud.diag
                    done.countDown()
                    true
                }
                mp.setDataSource(input.fd)
                mp.prepare()
                VoiceHud.playing = true
                mp.start()
                started = true
            }
            while (!cancelled.get() && !done.await(40, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            }
            if (cancelled.get()) VoiceHud.diag = "coupé (micro)"
        } catch (e: Exception) {
            VoiceHud.diag = "audio: ${e.javaClass.simpleName}"
            VoiceHud.line = VoiceHud.diag
            done.countDown()
        } finally {
            VoiceHud.hearAfter = System.currentTimeMillis() + 800
            VoiceHud.playing = false
            try {
                input.close()
            } catch (_: Exception) {
            }
            if (VoiceHud.mode == "talk") VoiceHud.mode = "idle"
            synchronized(gate) {
                if (player === mp) releasePlayer()
            }
        }
    }

    private fun kindOf(bytes: ByteArray): String {
        if (bytes.size >= 4 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte()) return "wav"
        if (bytes.size >= 4 && bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte()) return "ogg"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) return "mp3"
        if (bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte()) return "mp3"
        return "inconnu"
    }

    private fun playPcm(wav: Wav) {
        playWithPlayer(clipFile(wav.raw))
    }

    private fun playFile(bytes: ByteArray) {
        playWithPlayer(clipFile(bytes))
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
