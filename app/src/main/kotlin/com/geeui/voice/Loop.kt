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

    override fun speak(text: String, language: String) {
        cancelled.set(false)
        VoiceHud.mode = "talk"
        armSpeaker()
        remote.speak(text, language)
        val bytes = remote.lastAudio
        if (bytes.isEmpty() || bytes[0] == '{'.code.toByte()) {
            if (VoiceHud.mode == "talk") VoiceHud.mode = "idle"
            return
        }
        if (bytes.size > 44 && bytes[0] == 'R'.code.toByte()) playWav(bytes) else playFile(bytes)
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
        try {
            mp.stop()
        } catch (_: IllegalStateException) {
        }
        try {
            mp.release()
        } catch (_: Exception) {
        }
    }

    private fun speechAttrs(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    /** Same stream Lex uses (STREAM_MUSIC). Raise it if the robot is muted. */
    private fun armSpeaker() {
        val am = audio.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < max / 2) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 3) / 4, 0)
        }
        @Suppress("DEPRECATION")
        am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
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

    private fun playWav(wav: ByteArray) {
        val rate = leInt(wav, 24).takeIf { it in 8_000..48_000 } ?: 24_000
        val pcm = wavData(wav) ?: wav.copyOfRange(44, wav.size)
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(speechAttrs())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(min.coerceAtLeast(pcm.size.coerceAtMost(min.coerceAtLeast(4096) * 4)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            VoiceHud.line = "haut-parleur non initialisé"
            t.release()
            return
        }
        track = t
        try {
            t.play()
        } catch (_: IllegalStateException) {
            VoiceHud.line = "lecture impossible"
            synchronized(gate) { if (track === t) releaseTrack() }
            return
        }
        var off = 0
        while (off < pcm.size && !cancelled.get()) {
            val n = t.write(pcm, off, pcm.size - off)
            if (n <= 0) break
            off += n
        }
        val frames = off / 2
        while (!cancelled.get() && t.playState == AudioTrack.PLAYSTATE_PLAYING && t.playbackHeadPosition < frames) {
            Thread.sleep(20)
        }
        if (VoiceHud.mode == "talk") VoiceHud.mode = "idle"
        synchronized(gate) {
            if (track === t) releaseTrack()
        }
    }

    private fun playFile(bytes: ByteArray) {
        val file = File(cacheDir, "tts.mp3")
        file.writeBytes(bytes)
        val mp = MediaPlayer()
        try {
            synchronized(gate) {
                if (cancelled.get()) {
                    mp.release()
                    return
                }
                player = mp
                mp.setAudioAttributes(speechAttrs())
                mp.setVolume(1f, 1f)
                mp.setDataSource(file.absolutePath)
                mp.prepare()
                mp.start()
            }
            while (!cancelled.get()) {
                val playing = try {
                    synchronized(gate) { player === mp && mp.isPlaying }
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

    private fun wavData(wav: ByteArray): ByteArray? {
        var i = 12
        while (i + 8 <= wav.size) {
            val id = String(wav, i, 4, Charsets.US_ASCII)
            val size = leInt(wav, i + 4)
            val start = i + 8
            if (id == "data" && size > 0 && start < wav.size) {
                return wav.copyOfRange(start, (start + size).coerceAtMost(wav.size))
            }
            if (size < 0) break
            i = start + size
        }
        return null
    }

    private fun leInt(b: ByteArray, at: Int): Int {
        if (at + 3 >= b.size) return 0
        return (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)
    }
}
