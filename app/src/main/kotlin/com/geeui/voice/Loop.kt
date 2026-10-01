package com.geeui.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import com.geeui.voice.audio.EnergyVad
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
) {
    private val client = LemonadeClient(baseUrl)
    private val tts = PlayingTts(LemonadeTts(client), cacheDir)
    private val session = VoiceSession(bus, tts, LemonadeChat(client))
    private val live = LiveTurn(EnergyVad(), LemonadeStt(client), tts, session)
    private val turns = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        live.worker = { job -> turns.execute(job) }
        thread = Thread({
            val rate = 16_000
            val frame = 320
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min.coerceAtLeast(frame * 4),
            )
            val buf = ShortArray(frame)
            rec.startRecording()
            try {
                while (running.get()) {
                    val n = rec.read(buf, 0, frame)
                    if (n == frame) live.onFrame(buf.copyOf())
                }
            } finally {
                rec.stop()
                rec.release()
            }
        }, "geeui-voice").also { it.start() }
    }

    fun stop() {
        running.set(false)
        tts.stop()
        thread?.join(500)
    }
}

/** Plays each Lemonade clip on AudioTrack if it is PCM WAV, else MediaPlayer. */
class PlayingTts(
    private val remote: LemonadeTts,
    private val cacheDir: File,
) : TextToSpeech {
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null

    private val cancelled = AtomicBoolean(false)

    override fun speak(text: String, language: String) {
        cancelled.set(false)
        remote.speak(text, language)
        val bytes = remote.lastAudio
        if (bytes.size > 44 && bytes[0] == 'R'.code.toByte()) playWav(bytes) else playFile(bytes)
    }

    override fun stop() {
        cancelled.set(true)
        remote.stop()
        player?.stop()
        player?.release()
        player = null
        track?.pause()
        track?.flush()
        track?.release()
        track = null
    }

    private fun playWav(wav: ByteArray) {
        val rate = (wav[24].toInt() and 0xff) or ((wav[25].toInt() and 0xff) shl 8)
        val pcm = wav.copyOfRange(44, wav.size)
        val t = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(if (rate > 0) rate else 24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size.coerceAtLeast(4096))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        t.write(pcm, 0, pcm.size)
        t.play()
        while (t.playState == AudioTrack.PLAYSTATE_PLAYING && !cancelled.get()) Thread.sleep(20)
        t.release()
        track = null
    }

    private fun playFile(bytes: ByteArray) {
        val file = File(cacheDir, "tts.mp3")
        file.writeBytes(bytes)
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        mp.prepare()
        mp.start()
        while (mp.isPlaying && !cancelled.get()) Thread.sleep(20)
        mp.release()
        player = null
    }
}
