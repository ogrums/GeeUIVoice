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
import com.geeui.voice.engine.EmotionStt
import com.geeui.voice.engine.LemonadeTts
import com.geeui.voice.engine.PlannedTts
import com.geeui.voice.engine.TextToSpeech
import com.geeui.voice.engine.TtsPlan
import com.geeui.voice.session.LiveTurn
import com.geeui.voice.session.VoiceSession
import com.geeui.voice.session.WavTestResult
import com.geeui.voice.session.WavTestTurn
import com.geeui.voice.engine.TimingChat
import com.geeui.voice.engine.TimingTts
import com.geeui.voiceemo.Mood
import android.util.Log
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
    sttModel: String = "Whisper-Small",
    chatModel: String = "",
    ttsModel: String = "kokoro",
    voice: String = "",
    prompt: String = com.geeui.voice.engine.LemonadeClient.SPOKEN,
    audio: Context,
    private val sidecar: String = com.geeui.voiceemo.HostConfig.SIDECAR,
    cosyModel: String = CosyTts.MODEL,
    phonon: String = "",
    phononVoice: String = com.geeui.voice.engine.PhononTts.VOICE,
    /** Ask the sidecar every 15 s, when idle, if the robot should speak first. */
    private val initiative: Boolean = true,
) {
    private val appContext = audio
    private val client = LemonadeClient(
        baseUrl,
        sttModel = sttModel,
        chatModel = chatModel,
        ttsModel = ttsModel,
        systemPrompt = prompt,
    )
    private val plan = TtsPlan()
    private val sidecarOk = AtomicLong(0)
    private val kokoro = LemonadeTts(client, voice, plan)
    private val cosy = CosyTts(plan) { text, emotion ->
        postCosy(sidecar, cosyModel, text, emotion)
    }
    /** Phonon (Docker on nimbus) replaces Kokoro when its address is set; Kokoro stays the fallback. */
    private val base: com.geeui.voice.engine.ClipTts =
        if (phonon.isBlank()) kokoro
        else com.geeui.voice.engine.FallbackTts(
            com.geeui.voice.engine.PhononTts(phononVoice) { body -> postSpeech(phonon, body) },
            kokoro,
        )
    private val clips = PlannedTts(plan, base, cosy)
    private val player = PlayingTts(clips, cacheDir, audio) { plan.prosody }
    private val tts = TimingTts(player)
    private val gateway = LemonadeClient(sidecar.trimEnd('/') + "/v1", sttModel = sttModel, systemPrompt = "")
    private val lemonStt = LemonadeStt(client)
    private val stt = EmotionStt(gateway, lemonStt) { ok ->
        if (!ok) sidecarOk.set(0)
    }
    private val timingChat = TimingChat(LemonadeChat(client))
    private val session = VoiceSession(
        bus,
        tts,
        timingChat,
        mood = Mood(),
        plan = plan,
        audioAffect = { stt.lastAudioAffect },
        fusion = com.geeui.voiceemo.EmotionFusion(),
        sidecarOkAt = {
            val now = System.currentTimeMillis()
            if (sidecar.isNotBlank() && now - sidecarOk.get() > 5_000L) pingSidecar(sidecar)
            sidecarOk.get()
        },
    )
    private val live = LiveTurn(vad, stt, tts, session)
    private val turns = Executors.newSingleThreadExecutor()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private val poller = com.geeui.voice.session.InitiativePoller(
        fetch = { httpGet(sidecar.trimEnd('/') + "/v1/robot/pending") },
        ack = { id, status ->
            httpPostJson(sidecar.trimEnd('/') + "/v1/robot/ack", "{\"id\":\"$id\",\"status\":\"$status\"}")
        },
        idle = {
            running.get() && VoiceHud.mic && VoiceHud.mode == "idle" && !VoiceHud.playing &&
                session.state == com.geeui.voice.session.Dialogue.Idle
        },
        say = { text ->
            VoiceHud.heard = ""
            VoiceHud.answer = text
            VoiceHud.line = text
            session.say(text)
        },
        onMood = { label -> restFace(label) },
    )
    /** Robot mood from the sidecar: change the rest face, and show it now if the robot is resting. */
    private fun restFace(label: String) {
        val rest = com.geeui.voice.bus.RestFace
        val face = rest.forMood(label)
        if (face == rest.current) return
        val resting = VoiceHud.face.isBlank() || rest.isRest(VoiceHud.face)
        rest.current = face
        if (resting) bus.showFace(face)
    }

    private val running = AtomicBoolean(false)
    private val wavTestBusy = AtomicBoolean(false)
    private val wavTest = WavTestTurn(stt, session, timingChat, tts)
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        live.onUserText = {
            VoiceHud.clearFaceEvents()
            val who = stt.lastSpeakerName
            VoiceHud.heard = if (who.isBlank()) it else "$who : $it"
            VoiceHud.answer = ""
            VoiceHud.line = it
            VoiceHud.mode = "think"
            Log.i(TEST_TAG, "heard=$it")
            writeLastTurn()
        }
        live.onAnswer = {
            VoiceHud.answer = it
            VoiceHud.line = it
            VoiceHud.mode = "talk"
            Log.i(TEST_TAG, "answer=$it")
            writeLastTurn()
        }
        live.onListen = { VoiceHud.mode = "hear" }
        live.onIdle = { VoiceHud.mode = "idle" }
        live.allowBargeIn = {
            !wavTestBusy.get() && !VoiceHud.playing && System.currentTimeMillis() >= VoiceHud.hearAfter
        }
        live.worker = { job -> turns.execute(job) }
        if (initiative && sidecar.isNotBlank()) {
            // Same single worker as the turns: an initiative never overlaps a conversation.
            ticker.scheduleWithFixedDelay({ turns.execute { poller.poll() } }, 15, 15, java.util.concurrent.TimeUnit.SECONDS)
        }
        thread = Thread({
            val used = try {
                client.resolveStt()
            } catch (_: Exception) {
                "Whisper-Small"
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
        ticker.shutdownNow()
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

    /**
     * Feeds a WAV file instead of the microphone (STT → chat → TTS). The mic is
     * ignored while this runs. Writes result.json, status.txt and a copy of the
     * last TTS clip into [outDir]. Safe to call from a broadcast; work runs on the
     * turn thread.
     */
    fun enqueueWavTest(path: String, outDir: File) {
        outDir.mkdirs()
        val running = File(outDir, "status.txt")
        running.writeText("running\n")
        publishForAdb(running)
        turns.execute {
            val result = try {
                runWavTest(path, outDir)
            } catch (e: Exception) {
                WavTestResult.fail(path, e.message ?: e.javaClass.simpleName)
            }
            try {
                val resultFile = File(outDir, "result.json")
                resultFile.writeText(result.toJson())
                publishForAdb(resultFile)
                val statusFile = File(outDir, "status.txt")
                statusFile.writeText(if (result.ok) "done\n" else "error\n")
                publishForAdb(statusFile)
                outDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith("tts.")) publishForAdb(f)
                }
            } catch (e: Exception) {
                Log.e(TEST_TAG, "write result", e)
            }
        }
    }

    fun runWavTest(path: String, outDir: File): WavTestResult {
        if (!com.geeui.voice.session.WavTestAccess.allowedPath(path)) {
            return WavTestResult.fail(path, "chemin refusé")
        }
        val file = File(path)
        if (!file.isFile) return WavTestResult.fail(path, "fichier introuvable")
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return WavTestResult.fail(path, "lecture: ${e.message ?: e.javaClass.simpleName}")
        }
        wavTestBusy.set(true)
        return try {
            VoiceHud.clearFaceEvents()
            val result = wavTest.run(bytes, path,
                face = { VoiceHud.faceDuringSpeech().ifBlank { VoiceHud.face } },
                faces = { VoiceHud.faceEvents.toList() },
                audio = {
                    com.geeui.voice.session.WavTestAudio(
                        emotion = stt.lastAudioEmotion.ifBlank { stt.lastAudioAffect.emotion.name.lowercase() },
                        confidence = stt.lastAudioConfidence.takeIf { it > 0.0 }
                            ?: stt.lastAudioAffect.confidence.toDouble(),
                        raw = stt.lastAudioEmotionRaw,
                        rawConfidence = stt.lastAudioRawConfidence,
                    )
                },
            )
            val clip = copyLastTts(outDir)
            result.copy(ttsFile = clip)
        } finally {
            wavTestBusy.set(false)
        }
    }


    /** Android 11 : files in Android/data written by the app are mode 600, so
     *  `adb pull` / `adb shell cat` fail. Make them world-readable and also
     *  copy them to /data/local/tmp/geeui/ (writable by the app after the
     *  voicetest script has mkdir'd it) so the PC campaign can collect them. */
    private fun publishForAdb(file: File) {
        try {
            file.setReadable(true, false)
            file.setWritable(true, false)
        } catch (_: Exception) {
        }
        try {
            val mirror = File("/data/local/tmp/geeui")
            if (mirror.isDirectory || mirror.mkdirs()) {
                val dest = File(mirror, file.name)
                file.copyTo(dest, overwrite = true)
                dest.setReadable(true, false)
                dest.setWritable(true, false)
            }
        } catch (_: Exception) {
        }
    }

    private fun copyLastTts(outDir: File): String {
        val src = listOf("tts.mp3", "tts.wav", "tts.ogg", "tts.flac")
            .map { File(cacheDir, it) }
            .filter { it.isFile }
            .maxByOrNull { it.lastModified() } ?: return ""
        outDir.mkdirs()
        val dest = File(outDir, src.name)
        src.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    private fun writeLastTurn() {
        val dir = appContext.getExternalFilesDir("voicetest") ?: return
        try {
            dir.mkdirs()
            val last = File(dir, "last-turn.json")
            val facesJson = VoiceHud.faceEvents.joinToString(",", "[", "]") { it.toJson() }
            last.writeText(
                "{" +
                    "\"heard\":${jsonStr(VoiceHud.heard)}," +
                    "\"answer\":${jsonStr(VoiceHud.answer)}," +
                    "\"face\":${jsonStr(VoiceHud.face)}," +
                    "\"face_during_speech\":${jsonStr(VoiceHud.faceDuringSpeech())}," +
                    "\"faces\":$facesJson," +
                    "\"line\":${jsonStr(VoiceHud.line)}," +
                    "\"mode\":${jsonStr(VoiceHud.mode)}," +
                    "\"audio_emotion\":${jsonStr(stt.lastAudioEmotion.ifBlank { stt.lastAudioAffect.emotion.name.lowercase() })}," +
                    "\"audio_confidence\":${stt.lastAudioConfidence.takeIf { it > 0.0 } ?: stt.lastAudioAffect.confidence.toDouble()}," +
                    "\"audio_emotion_raw\":${jsonStr(stt.lastAudioEmotionRaw)}," +
                    "\"audio_emotion_raw_confidence\":${stt.lastAudioRawConfidence}" +
                    "}\n",
            )
            publishForAdb(last)
        } catch (_: Exception) {
        }
    }

    private fun jsonStr(value: String): String = buildString {
        append('"')
        for (c in value) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
        append('"')
    }

    companion object {
        private const val TEST_TAG = "GeeUIVoiceTest"
    }
}

/** GET returning the body, or null on any error. Short timeouts: this runs while idle. */
internal fun httpGet(url: String): String? = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 2_000
    conn.readTimeout = 5_000
    if (conn.responseCode in 200..299) conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } else null
} catch (_: Exception) {
    null
}

internal fun httpPostJson(url: String, body: String) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 5_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        conn.responseCode
        conn.disconnect()
    } catch (_: Exception) {
    }
}

internal fun postCosy(base: String, model: String, text: String, emotion: String): ByteArray =
    postSpeech(base, CosyTts.body(text, emotion, model))

/** POST {base}/v1/audio/speech. Empty on a blank base or an HTTP error, so the caller falls back. */
internal fun postSpeech(base: String, body: String): ByteArray {
    if (base.isBlank()) return ByteArray(0)
    val conn = URL(base.trimEnd('/') + "/v1/audio/speech").openConnection() as HttpURLConnection
    conn.connectTimeout = 2_000
    conn.readTimeout = 60_000
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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
    private val prosody: () -> com.geeui.voiceemo.Prosody = { com.geeui.voiceemo.Prosody.NEUTRAL },
) : TextToSpeech {
    private var player: MediaPlayer? = null
    private var track: AudioTrack? = null
    private val cancelled = AtomicBoolean(false)
    private val gate = Any()
    private var started = false
    private var audible: (() -> Unit)? = null

    override fun whenAudible(action: (() -> Unit)?) {
        audible = action
    }

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
                shape(mp)
                VoiceHud.playing = true
                mp.start()
                started = true
                val action = audible
                audible = null
                action?.invoke()
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
                releaseBoost()
            }
        }
    }

    private var boost: android.media.audiofx.LoudnessEnhancer? = null

    /**
     * Emotion accent: pitch (and speed when the server did not apply it) through
     * PlaybackParams, softer through setVolume, louder through LoudnessEnhancer
     * (MediaPlayer volume cannot go above 1). Any failure keeps the plain voice.
     */
    private fun shape(mp: MediaPlayer) {
        val p = prosody()
        if (p.isNeutral) return
        val speed = if (remote.speedApplied) 1f else p.speed
        if (speed != 1f || p.pitch != 1f) {
            try {
                // May start the prepared player right away; the start() that follows is then a no-op.
                mp.playbackParams = mp.playbackParams.setSpeed(speed).setPitch(p.pitch)
            } catch (e: Exception) {
                VoiceHud.diag = "prosodie: ${e.javaClass.simpleName}"
            }
        }
        try {
            if (p.gain < 1f) {
                mp.setVolume(p.gain, p.gain)
            } else if (p.gain > 1f) {
                val mB = (2000.0 * kotlin.math.log10(p.gain.toDouble())).toInt()
                boost = android.media.audiofx.LoudnessEnhancer(mp.audioSessionId).apply {
                    setTargetGain(mB)
                    enabled = true
                }
            }
        } catch (e: Exception) {
            VoiceHud.diag = "gain: ${e.javaClass.simpleName}"
        }
    }

    private fun releaseBoost() {
        val b = boost
        boost = null
        try {
            b?.release()
        } catch (_: Exception) {
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
