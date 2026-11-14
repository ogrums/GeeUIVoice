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
import com.geeui.voice.bus.DialogueFaces
import com.geeui.voice.session.DobbyPhrase
import com.geeui.voice.session.LiveTurn
import com.geeui.voice.session.parseWakeSource
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
    listenMode: String = "auto",
    wakeWord: String = "both",
    listenFace: String = com.geeui.voice.bus.LISTEN_FACE,
    thinkFace: String = com.geeui.voice.bus.THINK_FACE,
    backendFace: String = "",
    idleFaces: String = "",
    idleInterval: String = "",
    vision: Boolean = true,
    /** "auto" (default): the Dobby gate follows the room noise. A number keeps a fixed RMS gate. */
    dobbyGateSetting: String = "auto",
    /** « Confie à Hermes … » through the sidecar (/hermes/…), light while a result waits. */
    hermes: Boolean = true,
    /** Optional X-Hermes-Token for the sidecar /hermes routes (HERMES_TASKS_TOKEN). */
    private val hermesToken: String = "",
) {
    private val camera = if (vision) RobotCamera(audio) else null
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
    private val hermesDesk = if (hermes && sidecar.isNotBlank()) {
        com.geeui.voice.session.HermesDesk(
            get = { path -> hermesCall("GET", path, null, readMs = 5_000) },
            // The summary of a long result can take a while on the chat model (cold start).
            post = { path, json -> hermesCall("POST", path, json, readMs = 70_000) },
        )
    } else null
    private val session = VoiceSession(
        bus,
        tts,
        timingChat,
        mood = Mood(),
        plan = plan,
        audioAffect = { stt.lastAudioAffect },
        fusion = com.geeui.voiceemo.EmotionFusion(),
        allowedActions = { stt.lastAllowedActions },
        camera = camera?.let { cam ->
            {
                VoiceHud.line = "je regarde…"
                val jpeg = cam.snapshot()
                VoiceHud.diag = if (jpeg == null) "caméra : ${cam.lastError}" else "photo ${jpeg.size / 1024} Ko"
                jpeg
            }
        },
        hermes = hermesDesk,
        sidecarOkAt = {
            val now = System.currentTimeMillis()
            if (sidecar.isNotBlank() && now - sidecarOk.get() > 5_000L) pingSidecar(sidecar)
            sidecarOk.get()
        },
    )
    private val live = LiveTurn(vad, stt, tts, session)
    private val vadThreshold = vad.threshold
    private val turns = Executors.newSingleThreadExecutor()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private val poller = com.geeui.voice.session.InitiativePoller(
        fetch = { fetchPending() },
        ack = { id, status ->
            httpPostJson(sidecar.trimEnd('/') + "/v1/robot/ack", "{\"id\":\"$id\",\"status\":\"$status\"}")
        },
        idle = {
            // Initiative can speak at rest even when the mic is yielded to Lex.
            running.get() && VoiceHud.mode == "idle" && !VoiceHud.playing &&
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
        val inDialogue = VoiceHud.mode in setOf("hear", "think", "talk") || VoiceHud.playing
        if (resting && !inDialogue) bus.showFace(face)
    }

    /** The deployed sidecar has no /v1/robot/pending yet: after a 404, ask again in 10 min, not every 15 s. */
    @Volatile private var pendingMissingUntil = 0L

    private fun fetchPending(now: Long = System.currentTimeMillis()): String? {
        if (now < pendingMissingUntil) return null
        val (code, body) = httpGetCode(sidecar.trimEnd('/') + "/v1/robot/pending")
        if (code == 404) {
            if (pendingMissingUntil == 0L) EventLog.add("initiative: /v1/robot/pending absent (404), nouvel essai dans 10 min")
            pendingMissingUntil = now + PENDING_MISSING_BACKOFF_MS
        } else if (code in 200..299) {
            pendingMissingUntil = 0L
        }
        return body
    }

    /** Unread Hermes results last seen, and whether this app lit the antenna for them. */
    @Volatile private var hermesUnread = 0
    @Volatile private var hermesLit = false
    @Volatile private var hermesSeen = ""

    /**
     * Idle check of the Hermes task list: the antenna glows purple while a result waits.
     * Re-lit at each check because the end of every turn (standAtAttention) turns the light off.
     */
    private fun pollHermes() {
        val desk = hermesDesk ?: return
        val idle = running.get() && VoiceHud.mode == "idle" && !VoiceHud.playing &&
            session.state == com.geeui.voice.session.Dialogue.Idle
        if (!idle) return
        val state = desk.state()
        val seen = when {
            state == null -> "injoignable"
            !state.enabled -> "désactivé"
            else -> "unread=${state.unread} running=${state.running}"
        }
        // One journal line per change (first poll included), not one every 20 s.
        if (seen != hermesSeen) {
            EventLog.add("hermes: $seen")
            hermesSeen = seen
        }
        if (state == null) return
        val unread = if (state.enabled) state.unread else 0
        if (unread > hermesUnread) {
            VoiceHud.line = "Hermes : " + com.geeui.voice.session.HermesDesk.statusLine(state)
        }
        hermesUnread = unread
        if (unread > 0) {
            bus.antennaLight(true, HERMES_LIGHT)
            hermesLit = true
        } else if (hermesLit) {
            bus.antennaLight(false, 0)
            hermesLit = false
        }
    }

    /** One call to the sidecar /hermes routes. Code 0 = no answer. Error bodies are read too (503, 404). */
    private fun hermesCall(method: String, path: String, json: String?, readMs: Int): com.geeui.voice.session.HermesHttp =
        try {
            val conn = URL(sidecar.trimEnd('/') + path).openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = readMs
            conn.requestMethod = method
            if (hermesToken.isNotBlank()) conn.setRequestProperty("X-Hermes-Token", hermesToken)
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { String(it.readBytes(), Charsets.UTF_8) }
            conn.disconnect()
            com.geeui.voice.session.HermesHttp(code, body)
        } catch (_: Exception) {
            com.geeui.voice.session.HermesHttp(0, null)
        }

    private val running = AtomicBoolean(false)
    private val wavTestBusy = AtomicBoolean(false)
    private val wavTest = WavTestTurn(stt, session, timingChat, tts)
    private var thread: Thread? = null
    private val policy = com.geeui.voice.session.ListenPolicy(
        com.geeui.voice.session.ListenPolicy.parseMode(listenMode),
    ).also { it.setWakeSource(parseWakeSource(wakeWord)) }
    private val listenFaceId = DialogueFaces.parseId(listenFace, com.geeui.voice.bus.LISTEN_FACE)
    private val thinkFaceId = DialogueFaces.parseId(
        backendFace.ifBlank { thinkFace },
        com.geeui.voice.bus.BACKEND_FACE,
    )
    private val idleFaceList = com.geeui.voice.bus.IdleFaces.parseList(idleFaces)
    private val idleIntervalMs = com.geeui.voice.bus.IdleFaces.parseInterval(idleInterval)
    private var lastIdleRotateAt = 0L
    private var nextIdleDelayMs = 0L
    private var lastIdleRotated = ""
    private val wantMic = AtomicBoolean(false)
    private val micGate = Object()
    private val wantDobby = AtomicBoolean(false)
    private val dobbyLock = Object()
    @Volatile private var dobby: VoskDobbyWake? = null
    /** Energy gate in front of Vosk, setting dobby_gate ("auto" or a fixed RMS). Dobby thread only. */
    private val dobbyGate = com.geeui.voice.audio.NoiseGate.parse(dobbyGateSetting)
    private var statFrames = 0
    private var statFed = 0
    private var statAt = 0L
    /** When the last local wake fired, to log how long the conversation mic takes to open. */
    @Volatile private var wakeAt = 0L
    /** The pending wake is Hey Dobby (no Lex recording to wait for). */
    @Volatile private var localWake = false
    /** Loudest 20 ms frame since the conversation mic opened, and whether the VAD saw speech. */
    @Volatile private var hearPeak = 0
    @Volatile private var hearSpeech = false

    /** Every 5 min: share of 100 ms frames fed to Vosk, the noise floor and the gate. */
    private fun dobbyStats(rms: Double, fed: Boolean, now: Long = System.currentTimeMillis()) {
        statFrames++
        if (fed) statFed++
        if (statAt == 0L) statAt = now
        if (now - statAt < 300_000L) return
        EventLog.add("dobby stats fed=${statFed * 100 / statFrames.coerceAtLeast(1)}% floor=${dobbyGate.floor.toInt()} gate=${dobbyGate.gate} frames=$statFrames")
        statFrames = 0
        statFed = 0
        statAt = now
    }
    private var dobbyThread: Thread? = null
    private val screenOn = ScreenOn(appContext)
    @Volatile private var lexPaused = false
    private val speechCb = SpeechCallbackBinder { command, data ->
        turns.execute { onLexSpeech(command, data) }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        EventLog.init(appContext)
        EventLog.add("loop start hirux=${policy.hiruxEnabled} dobby=${policy.dobbyEnabled} listen=$listenFaceId backend=$thinkFaceId")
        DialogueFaces.listen = listenFaceId
        DialogueFaces.think = thinkFaceId
        live.onUserText = {
            VoiceHud.clearFaceEvents()
            val who = stt.lastSpeakerName
            VoiceHud.heard = if (who.isBlank()) it else "$who : $it"
            VoiceHud.answer = ""
            VoiceHud.line = it
            setHudMode("think")
            EventLog.add("heard=$it")
            writeLastTurn()
        }
        live.onAnswer = {
            VoiceHud.answer = it
            VoiceHud.line = it
            setHudMode("talk")
            EventLog.add("answer=$it")
            writeLastTurn()
        }
        live.onListen = {
            if (!hearSpeech) EventLog.add("speech start peak=$hearPeak")
            hearSpeech = true
            setHudMode("hear")
        }
        live.onThink = { setHudMode("think") }
        live.onIdle = {
            setHudMode("idle")
            policy.onTurnEnded()
        }
        live.allowBargeIn = {
            !wavTestBusy.get() && !VoiceHud.playing &&
                System.currentTimeMillis() >= VoiceHud.hearAfter &&
                VoiceHud.mode != "think" && VoiceHud.mode != "talk"
        }
        live.worker = { job -> turns.execute(job) }
        if (initiative && sidecar.isNotBlank()) {
            // Same single worker as the turns: an initiative never overlaps a conversation.
            ticker.scheduleWithFixedDelay({ turns.execute { poller.poll() } }, 15, 15, java.util.concurrent.TimeUnit.SECONDS)
        }
        if (hermesDesk != null) {
            ticker.scheduleWithFixedDelay({ turns.execute { pollHermes() } }, 20, 20, java.util.concurrent.TimeUnit.SECONDS)
        }
        ticker.scheduleWithFixedDelay({
            turns.execute {
                val busy = wavTestBusy.get() || VoiceHud.playing ||
                    session.state != com.geeui.voice.session.Dialogue.Idle
                if (policy.onTick(busy = busy)) {
                    if (VoiceHud.mode == "hear" && !hearSpeech) {
                        EventLog.add("hear timeout: no speech, peak=$hearPeak vad=${vadThreshold}")
                    }
                    setHudMode("idle")
                    VoiceHud.line = restLabel()
                    Log.i(TEST_TAG, "mic yielded after idle")
                }
                maybeRotateIdle()
                keepHudInFront()
                applyMic()
            }
        }, 500, 500, java.util.concurrent.TimeUnit.MILLISECONDS)
        hookLexWake()
        prepareDobby()
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
                return@Thread
            }
            val buf = ShortArray(frame)
            while (running.get()) {
                if (!wantMic.get()) {
                    VoiceHud.mic = false
                    VoiceHud.level = 0f
                    resumeLexIfPaused()
                    synchronized(micGate) {
                        while (running.get() && !wantMic.get()) {
                            try {
                                micGate.wait(200)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                    continue
                }
                // MIC / VOICE_RECOGNITION / UNPROCESSED are silenced on
                // Android 11 while Lex keeps its privileged 32 kHz
                // VOICE_RECOGNITION session (uid 1000). That session is not
                // closed by stop_audio_service on this firmware.
                // VOICE_COMMUNICATION stays unsilenced and carries signal.
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min.coerceAtLeast(frame * 4),
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    VoiceHud.line = "micro non initialisé (permission ou déjà pris)"
                    VoiceHud.mic = false
                    rec.release()
                    // Retry after a short wait: Lex may still be releasing the device.
                    synchronized(micGate) {
                        try {
                            micGate.wait(400)
                        } catch (_: InterruptedException) {
                        }
                    }
                    continue
                }
                try {
                    rec.startRecording()
                    VoiceHud.mic = true
                    hearPeak = 0
                    hearSpeech = false
                    val since = wakeAt
                    EventLog.add("mic open" + if (since > 0L) " +${System.currentTimeMillis() - since} ms après le réveil" else "")
                    wakeAt = 0L
                    policy.beginPostWakeTrim()
                    try {
                        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        MicProbe.logClientState("rec start session=${rec.audioSessionId}", am, rec.audioSessionId)
                    } catch (e: Exception) {
                        Log.w(TEST_TAG, "rec silenced check failed", e)
                    }
                    while (running.get() && wantMic.get()) {
                        val n = rec.read(buf, 0, frame)
                        if (n == frame) {
                            VoiceHud.level = levelOf(buf)
                            val r = (VoiceHud.level * 4000f).toInt()
                            if (r > hearPeak) hearPeak = r
                            if (!policy.shouldDropFrame()) live.onFrame(buf.copyOf())
                        }
                    }
                } finally {
                    try {
                        rec.stop()
                    } catch (_: IllegalStateException) {
                    }
                    rec.release()
                    VoiceHud.mic = false
                    VoiceHud.level = 0f
                    if (!wantMic.get()) resumeLexIfPaused()
                }
            }
        }, "geeui-voice").also { it.start() }
        applyMic()
    }

    fun stop() {
        running.set(false)
        wantMic.set(false)
        synchronized(micGate) { micGate.notifyAll() }
        wantDobby.set(false)
        synchronized(dobbyLock) { dobbyLock.notifyAll() }
        ticker.shutdownNow()
        tts.stop()
        VoiceHud.mode = "idle"
        thread?.join(500)
        dobbyThread?.join(800)
        dobbyThread = null
        dobby?.close()
        dobby = null
        (bus as? AidlBus)?.unregisterSpeechCallback()
        resumeLexIfPaused()
        screenOn.release()
    }

    private fun hookLexWake() {
        val aidl = bus as? AidlBus ?: run {
            policy.onCallbackAvailable(false)
            return
        }
        aidl.onConnected = {
            turns.execute { finishHook(aidl) }
        }
        if (aidl.connected()) {
            turns.execute { finishHook(aidl) }
        } else {
            ticker.schedule({
                turns.execute {
                    if (!policy.resolved) {
                        policy.onCallbackAvailable(false)
                        val fallback = if (policy.useWake) "Hey Dobby still on" else "fallback continuous"
                        Log.i(TEST_TAG, "registerSpeechCallback timed out, $fallback")
                        if (policy.useWake) VoiceHud.line = restLabel()
                        applyMic()
                    }
                }
            }, 3, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun finishHook(aidl: AidlBus) {
        if (policy.resolved && policy.callbackAvailable) return
        val ok = aidl.registerSpeechCallback(speechCb)
        Log.i(TEST_TAG, "registerSpeechCallback=$ok")
        policy.onCallbackAvailable(ok)
        if (ok && policy.useWake) {
            VoiceHud.line = restLabel()
        } else if (!ok && policy.useWake) {
            VoiceHud.line = restLabel()
            Log.i(TEST_TAG, "Lex hook failed, Hey Dobby still on")
        }
        applyMic()
    }

    private fun onLexSpeech(command: String, data: String) {
        if (command.equals("wakeup_status", ignoreCase = true)) wakeAt = System.currentTimeMillis()
        Log.i(TEST_TAG, "LtpSpeech $command $data")
        val related = command.equals("wakeup_status", ignoreCase = true) ||
            command.equals("wakeup_doa", ignoreCase = true)
        if (!related) return
        val verdict = policy.considerWake(command, data)
        if (verdict.accepted) {
            EventLog.add("wake accepted(hirux: $command $data) hud=${VoiceHud.visible}")
        } else {
            EventLog.add("wake ignored(hirux ${verdict.reason}: $command $data)")
            return
        }
        VoiceHud.line = "Hi RUX"
        setHudMode("hear")
        screenOn.turnOn()
        (bus as? AidlBus)?.exitDarkScreen()
        applyMic()
    }

    private fun applyMic() {
        val hold = policy.shouldHoldMic()
        if (hold == wantMic.get()) {
            refreshDobby()
            return
        }
        if (hold) {
            // Stop the rest-time Dobby recorder before opening the conversation mic.
            wantDobby.set(false)
            synchronized(dobbyLock) { dobbyLock.notifyAll() }
            (bus as? AidlBus)?.pauseLexAudio()
            lexPaused = true
            // Hi RUX: Lex is still recording its own question, give it time to let go.
            // Hey Dobby: Lex is not recording, and every 100 ms here is the start of the
            // user's question lost (« Bonjour, » cut when the question follows the wake).
            try {
                Thread.sleep(if (localWake) 100L else 400L)
            } catch (_: InterruptedException) {
            }
            localWake = false
            wantMic.set(true)
            synchronized(micGate) { micGate.notifyAll() }
        } else {
            wantMic.set(false)
            synchronized(micGate) { micGate.notifyAll() }
        }
        refreshDobby()
    }

    private fun setHudMode(mode: String) {
        val leavingIdle = VoiceHud.mode == "idle" && mode != "idle"
        VoiceHud.mode = mode
        if (mode == "idle") {
            lastIdleRotateAt = System.currentTimeMillis()
            nextIdleDelayMs = com.geeui.voice.bus.IdleFaces.nextDelayMs(idleIntervalMs)
            lastIdleRotated = ""
        } else if (leavingIdle) {
            lastIdleRotateAt = 0L
            hearPeak = 0
            hearSpeech = false
        }
        val face = when (mode) {
            "hear" -> listenFaceId
            "think" -> thinkFaceId
            "idle" -> com.geeui.voice.bus.RestFace.current
            else -> null
        }
        if (face != null) {
            bus.showFace(face)
            EventLog.add("face $mode $face hud=${VoiceHud.visible}")
        } else {
            EventLog.add("mode $mode hud=${VoiceHud.visible}")
        }
    }

    private var lastFrontAt = 0L

    /**
     * During one of our turns, put VoiceActivity back on top if something
     * covered it (Lex opens com.geeui.lex/.MainActivity on « Hi RUX », the task
     * service can open the desktop). Otherwise the listen / backend / speech
     * faces play on a hidden TextureView. At most every 1.5 s.
     */
    private fun keepHudInFront(now: Long = System.currentTimeMillis()) {
        if (!running.get() || VoiceHud.visible) return
        val inTurn = VoiceHud.mode in setOf("hear", "think", "talk") || VoiceHud.playing
        if (!inTurn) return
        if (now - lastFrontAt < 1_500L) return
        lastFrontAt = now
        EventLog.add("hud covered during ${VoiceHud.mode}: bring to front")
        screenOn.bringToFront()
    }

    /**
     * Optional rest-face rotation (off by default). At least 60 s between
     * switches, only when truly idle, the mood is calm, and the screen is on.
     */
    private fun maybeRotateIdle(now: Long = System.currentTimeMillis()) {
        if (idleFaceList.isEmpty()) return
        if (!running.get()) return
        if (VoiceHud.mode != "idle" || VoiceHud.playing || wavTestBusy.get()) return
        if (session.state != com.geeui.voice.session.Dialogue.Idle) return
        if (!com.geeui.voice.bus.RestFace.isCalm()) return
        if (!screenOn.isInteractive()) return
        if (lastIdleRotateAt == 0L) {
            lastIdleRotateAt = now
            nextIdleDelayMs = com.geeui.voice.bus.IdleFaces.nextDelayMs(idleIntervalMs)
            return
        }
        if (now - lastIdleRotateAt < nextIdleDelayMs) return
        val next = com.geeui.voice.bus.IdleFaces.next(lastIdleRotated, idleFaceList)
        lastIdleRotated = next
        lastIdleRotateAt = now
        nextIdleDelayMs = com.geeui.voice.bus.IdleFaces.nextDelayMs(idleIntervalMs)
        bus.showFace(next)
        EventLog.add("face idle $next")
    }

    private fun restLabel(): String {
        val bits = buildList {
            if (policy.hiruxEnabled) add("Hi RUX")
            if (policy.dobbyEnabled) add("Hey Dobby")
        }
        return if (bits.isEmpty()) "repos" else "repos (${bits.joinToString(" / ")})"
    }

    private fun refreshDobby() {
        val listen = running.get() && policy.dobbyEnabled && !wantMic.get() && dobby != null
        wantDobby.set(listen)
        synchronized(dobbyLock) { dobbyLock.notifyAll() }
    }

    private fun prepareDobby() {
        if (!policy.dobbyEnabled) return
        Thread({
            val dir = try {
                VoskModelStore.ensure(appContext)
            } catch (e: Exception) {
                Log.w(TEST_TAG, "vosk model ensure failed", e)
                null
            }
            if (dir == null) {
                Log.w(
                    TEST_TAG,
                    "vosk model missing; Hey Dobby off. " +
                        "adb push the folder to /data/local/tmp/geeui/${VoskModelStore.MODEL_NAME}",
                )
                VoiceHud.line = "Hey Dobby : modèle manquant"
                return@Thread
            }
            try {
                dobby = VoskDobbyWake(dir)
                refreshDobby()
            } catch (e: Exception) {
                Log.w(TEST_TAG, "vosk init failed", e)
                VoiceHud.line = "Hey Dobby : modèle illisible"
            }
        }, "geeui-dobby-init").start()
        dobbyThread = Thread({
            val rate = 16_000
            val frame = 1_600 // 100 ms, keeps CPU low
            val min = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bytes = ByteArray(frame * 2)
            val shorts = ShortArray(frame)
            while (running.get()) {
                if (!wantDobby.get()) {
                    try {
                        dobby?.flush()
                    } catch (_: Exception) {
                    }
                    synchronized(dobbyLock) {
                        while (running.get() && !wantDobby.get()) {
                            try {
                                dobbyLock.wait(200)
                            } catch (_: InterruptedException) {
                                break
                            }
                        }
                    }
                    continue
                }
                if (min <= 0) {
                    synchronized(dobbyLock) {
                        try { dobbyLock.wait(1_000) } catch (_: InterruptedException) {}
                    }
                    continue
                }
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min.coerceAtLeast(frame * 4),
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    synchronized(dobbyLock) {
                        try { dobbyLock.wait(800) } catch (_: InterruptedException) {}
                    }
                    continue
                }
                try {
                    rec.startRecording()
                    Log.i(TEST_TAG, "dobby listen start session=${rec.audioSessionId}")
                    val engine = dobby ?: break
                    var quiet = DOBBY_HANG_FRAMES + 1
                    var inUtterance = false
                    var peak = 0.0
                    while (running.get() && wantDobby.get()) {
                        val n = rec.read(shorts, 0, frame)
                        if (n <= 0) continue
                        var energy = 0L
                        for (i in 0 until n) {
                            val s = shorts[i].toInt()
                            energy += s * s
                        }
                        val rms = kotlin.math.sqrt(energy.toDouble() / n)
                        val open = dobbyGate.pass(rms)
                        dobbyStats(rms, open)
                        // Skip long silence so Vosk is not woken on every frame, but keep
                        // feeding a short tail after speech: without that tail Vosk never
                        // sees the end of « Hey Dobby » in a quiet room, and the wake only
                        // fires on the next noise (often the user repeating it).
                        if (open) {
                            quiet = 0
                            if (!inUtterance) peak = 0.0
                            inUtterance = true
                            if (rms > peak) peak = rms
                        } else {
                            quiet++
                            if (quiet > DOBBY_HANG_FRAMES) {
                                if (inUtterance) {
                                    inUtterance = false
                                    val tail = try {
                                        engine.flush()
                                    } catch (e: Exception) {
                                        Log.w(TEST_TAG, "vosk flush failed", e)
                                        null
                                    }
                                    if (tail != null) {
                                        EventLog.add("dobby peak=${peak.toInt()} gate=${dobbyGate.gate} floor=${dobbyGate.floor.toInt()}")
                                        wakeAt = System.currentTimeMillis()
                                        wantDobby.set(false)
                                        turns.execute { onDobbyWake(tail) }
                                        break
                                    }
                                }
                                continue
                            }
                        }
                        for (i in 0 until n) {
                            val s = shorts[i].toInt()
                            bytes[i * 2] = s.toByte()
                            bytes[i * 2 + 1] = (s shr 8).toByte()
                        }
                        val verdict = try {
                            engine.accept(bytes, n * 2)
                        } catch (e: Exception) {
                            Log.w(TEST_TAG, "vosk accept failed", e)
                            null
                        } ?: continue
                        EventLog.add("dobby peak=${peak.toInt()} gate=${dobbyGate.gate} floor=${dobbyGate.floor.toInt()}")
                        wakeAt = System.currentTimeMillis()
                        wantDobby.set(false)
                        turns.execute { onDobbyWake(verdict) }
                        break
                    }
                } finally {
                    try {
                        rec.stop()
                    } catch (_: IllegalStateException) {
                    }
                    rec.release()
                    try {
                        dobby?.flush()
                    } catch (_: Exception) {
                    }
                }
            }
        }, "geeui-dobby").also { it.start() }
    }

    private fun onDobbyWake(verdict: DobbyPhrase.Verdict) {
        val decision = policy.acceptLocalWake()
        if (!decision.accepted) {
            EventLog.add("wake ignored(${decision.reason}) dobby=${verdict.phrase}")
            return
        }
        EventLog.add("wake accepted(dobby: ${verdict.phrase}) hud=${VoiceHud.visible}")
        localWake = true
        VoiceHud.line = "Hey Dobby"
        setHudMode("hear")
        screenOn.turnOn()
        (bus as? AidlBus)?.exitDarkScreen()
        applyMic()
    }

    private fun resumeLexIfPaused() {
        if (!lexPaused) return
        policy.onLexResumed()
        (bus as? AidlBus)?.resumeLexAudio()
        lexPaused = false
    }

    private fun pingSidecar(base: String) {
        val code = httpGetCode(base.trimEnd('/') + "/health", 800, 800).first
        if (code in 200..299) sidecarOk.set(System.currentTimeMillis())
    }

    /**
     * Feeds a WAV file instead of the microphone (STT → chat → TTS). The mic is
     * ignored while this runs. Writes result.json, status.txt and a copy of the
     * last TTS clip into [outDir]. Safe to call from a broadcast; work runs on the
     * turn thread.
     */
    fun enqueueMicProbe(outDir: File) {
        outDir.mkdirs()
        File(outDir, "status.txt").writeText("running\n")
        turns.execute {
            try {
                MicProbe.run(appContext, bus as? AidlBus, outDir)
            } catch (e: Exception) {
                Log.e(TEST_TAG, "TEST_MIC failed", e)
                val status = File(outDir, "status.txt")
                status.writeText("error\n")
                MicProbe.publish(status)
            }
        }
    }

    fun debugShowFace(faceId: String) {
        val id = com.geeui.voice.bus.DialogueFaces.parseId(faceId, "")
        if (id.isEmpty()) {
            Log.w(TEST_TAG, "TEST_FACE id refusé: $faceId")
            return
        }
        bus.showFace(id)
        EventLog.add("face test $id hud=${VoiceHud.visible}")
    }

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
        /** Mean amplitude under which a 100 ms frame counts as silence for the Dobby listener. */
        private const val DOBBY_GATE_RMS = 50L
        /** Silent frames still fed to Vosk after speech (5 × 100 ms), then the phrase is flushed. */
        private const val DOBBY_HANG_FRAMES = 5
        private const val PENDING_MISSING_BACKOFF_MS = 10 * 60_000L
        /** Antenna colour while a Hermes result waits (PURPLE). */
        private val HERMES_LIGHT = com.geeui.voice.bus.lightId("PURPLE") ?: 7
    }
}

/** GET returning the body, or null on any error. Short timeouts: this runs while idle. */
internal fun httpGet(url: String): String? = httpGetCode(url).second

/**
 * GET returning (HTTP code, body or null). The body or error stream is always read and
 * closed, and the connection disconnected: an unread 404 body left the socket open, so
 * every 15 s poll of a missing endpoint piled up one CLOSE_WAIT socket to the sidecar.
 */
internal fun httpGetCode(url: String, connectMs: Int = 2_000, readMs: Int = 5_000): Pair<Int, String?> {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        val code = conn.responseCode
        if (code in 200..299) {
            code to conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } else {
            try {
                conn.errorStream?.use { it.readBytes() }
            } catch (_: Exception) {
            }
            code to null
        }
    } catch (_: Exception) {
        0 to null
    } finally {
        conn?.disconnect()
    }
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
        val code = conn.responseCode
        try {
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
        } catch (_: Exception) {
        }
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
