package com.geeui.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.media.MediaPlayer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import android.text.TextUtils
import com.geeui.voice.engine.CosyTts
import com.geeui.voice.bus.PoseTiming
import com.geeui.voice.bus.bundledFace
import com.geeui.voice.bus.dialogueFace
import com.geeui.voice.bus.debugLaunch
import com.geeui.voice.bus.standAtAttention
import com.geeui.voiceemo.Emotion
import com.geeui.voiceemo.HostConfig
import com.geeui.voiceemo.SdkMap

/** 480×480 round screen. The top 30 px is the robot battery strip: draw only, never a control. */
class VoiceActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bus: AidlBus
    private var debug = false
    private var painting = false
    private var status: TextView? = null
    private var caption: TextView? = null
    private var mic: Switch? = null
    /**
     * Two stacked TextureViews. The next clip is prepared on the hidden one, shown when its
     * first frame is drawn, and the previous player is released on [releaser]. On the RK3566
     * a release can block 4 s or more in the vendor decoder (Rkvpu_Wait_ProcessPause, once a
     * media.codec crash): on the main thread that froze the face and delayed the next clip.
     */
    private class FaceSlot(val view: TextureView) {
        var surface: Surface? = null
        var player: MediaPlayer? = null
        /** True while the previous player of this slot is being released in the background. */
        var releasing = false
    }
    private val slots = ArrayList<FaceSlot>(2)
    private var front = 0
    private val releaser = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var facePlayer: MediaPlayer? = null
    private var pendingFace: String? = null
    private var shownFace = ""
    private var menu: ScrollView? = null
    private val editors = linkedMapOf<String, EditText>()
    private val refresh = object : Runnable {
        override fun run() {
            if (debug) paintDebug()
            val pose = VoiceHud.face.ifBlank { if (debug) "" else com.geeui.voice.bus.RestFace.current }
            var face = if (debug && pose.isBlank()) "" else dialogueFace(VoiceHud.mode, VoiceHud.playing, pose)
            face = afterglow(pose, face)
            if (face.isNotBlank() && face != shownFace) playFace(face)
            handler.postDelayed(this, 120)
        }
    }

    private var speechEmotion = ""
    private var wasPlaying = false
    private var speechEndedAt = 0L

    /**
     * talk_face=speak: the mouth clip plays while the robot speaks, so the emotion of the
     * answer is shown for [AFTERGLOW_MS] right after the speech, before the rest face.
     */
    private fun afterglow(pose: String, face: String, now: Long = System.currentTimeMillis()): String {
        val dialogue = com.geeui.voice.bus.DialogueFaces
        val playing = VoiceHud.playing
        if (playing && dialogue.isEmotion(pose)) speechEmotion = pose
        if (wasPlaying && !playing) speechEndedAt = now
        if (!wasPlaying && playing) speechEndedAt = 0L
        wasPlaying = playing
        if (!dialogue.speakDuringTalk || speechEmotion.isBlank()) return face
        if (VoiceHud.mode == "hear" || VoiceHud.mode == "think" || playing) return face
        if (speechEndedAt > 0L && now - speechEndedAt < AFTERGLOW_MS) return speechEmotion
        if (speechEndedAt > 0L) speechEmotion = ""
        return face
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTurnScreenOn(true)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        debug = debugLaunch(intent.getStringExtra("mode"), intent.getStringExtra("debug"))
        bus = AidlBus(this)
        // The robot ROM has no autofill service: AutofillManager throws a NullPointerException
        // (IAutoFillManager null) as soon as an EditText becomes visible. Keep every view out of it.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val screen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        repeat(2) { i ->
            val view = TextureView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                alpha = if (i == 0) 1f else 0f
            }
            val slot = FaceSlot(view)
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    slot.surface?.release()
                    slot.surface = Surface(surface)
                    pendingFace?.let { playFace(it) }
                }

                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit

                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                    releaseSlot(slot)
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
            }
            slots += slot
            screen.addView(view)
        }
        if (debug) screen.addView(debugPanel())
        menu = settingsMenu()
        screen.addView(menuBand())
        screen.addView(menu)
        setContentView(screen)
        if (debug) {
            VoiceHud.line = "micro coupé"
        } else {
            if (VoiceHud.face.isBlank()) VoiceHud.face = com.geeui.voice.bus.RestFace.current
            askMic()
        }
    }

    private fun menuBand(): View {
        return View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                78,
                Gravity.BOTTOM,
            )
            setOnClickListener { openMenu() }
        }
    }

    private fun settingsMenu(): ScrollView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
        }
        box.addView(label("Réglages", 18f, Color.WHITE))
        for ((key, _) in VoiceSettings.fields) {
            box.addView(label(VoiceSettings.label(key), 12f, Color.rgb(180, 180, 180)))
            val edit = EditText(this).apply {
                setTextColor(Color.WHITE)
                setSingleLine(true)
                textSize = 13f
                setBackgroundColor(Color.rgb(32, 32, 32))
                setPadding(12, 6, 12, 6)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            editors[key] = edit
            box.addView(edit)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                56,
            ).apply { topMargin = 12 }
        }
        row.addView(testButton("Sauvegarder") { saveMenu() })
        row.addView(testButton("Quitter") { closeMenu() })
        box.addView(row)
        return ScrollView(this).apply {
            setBackgroundColor(Color.argb(235, 0, 0, 0))
            isClickable = true
            visibility = View.GONE
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            addView(box)
        }
    }

    private fun openMenu() {
        val values = VoiceSettings.effective(this, intent)
        for ((key, edit) in editors) edit.setText(values[key].orEmpty())
        try {
            menu?.visibility = View.VISIBLE
        } catch (e: RuntimeException) {
            // Last guard against the ROM autofill bug: the menu shows even if the framework throws.
            android.util.Log.w("GeeUIVoice", "openMenu: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun closeMenu() {
        menu?.visibility = View.GONE
    }

    private fun saveMenu() {
        VoiceSettings.save(this, editors.mapValues { it.value.text.toString() })
        closeMenu()
        // Restart the loop so the new hosts apply, unless the user had stopped the mic.
        if (VoiceHud.running || VoiceHud.mic) send(VoiceService.ACTION_START)
    }

    private fun paintDebug() {
        status?.text = when {
            !VoiceHud.mic && VoiceHud.mode == "idle" -> "VEILLE"
            VoiceHud.mode == "hear" -> "ÉCOUTE"
            VoiceHud.mode == "think" -> "CALCUL"
            VoiceHud.mode == "talk" -> "PAROLE"
            VoiceHud.mic -> "MICRO OUVERT"
            else -> "VEILLE"
        }
        caption?.text = buildString {
            if (VoiceHud.heard.isNotBlank()) append("« ").append(VoiceHud.heard).append(" »")
            if (VoiceHud.answer.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(VoiceHud.answer)
            }
            if (isEmpty()) append(VoiceHud.line)
            if (VoiceHud.diag.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(VoiceHud.diag)
            }
        }
        val sw = mic
        if (sw != null && sw.isChecked != VoiceHud.mic) {
            painting = true
            sw.isChecked = VoiceHud.mic
            painting = false
        }
    }

    private fun debugPanel(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 30, 0, 72)
            isClickable = false
        }
        val title = label("GeeUI Voice", 13f, Color.rgb(180, 40, 32)).apply { sidePad() }
        status = label("VEILLE", 20f, Color.rgb(255, 64, 48)).apply { sidePad() }
        caption = label("", 15f, Color.rgb(230, 190, 180)).apply {
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            sidePad()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        val scan = ScannerView(this).apply {
            isClickable = false
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(480, 150)
        }
        mic = Switch(this).apply {
            text = "Micro"
            setTextColor(Color.rgb(255, 48, 36))
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (painting) return@setOnCheckedChangeListener
                if (checked) askMic() else send(VoiceService.ACTION_STOP)
            }
            val pad = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            pad.setMargins(72, 8, 72, 0)
            layoutParams = pad
        }
        val tests = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52)
            pad.setMargins(48, 8, 48, 0)
            layoutParams = pad
        }
        tests.addView(testButton("WAV") { playSample("olivier.wav") })
        tests.addView(testButton("Triste") { sad() })
        tests.addView(testButton("Joyeux") { happy() })
        root.addView(title)
        root.addView(status)
        root.addView(scan)
        root.addView(caption)
        root.addView(mic)
        root.addView(tests)
        return root
    }

    private fun label(text: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            isClickable = false
            isFocusable = false
        }
    }

    private fun TextView.sidePad() {
        val pad = 72
        setPadding(pad, 0, pad, 0)
    }

    private var sample: MediaPlayer? = null
    private var restTicket = 0

    private fun testButton(label: String, click: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setTextColor(Color.rgb(255, 48, 36))
            setBackgroundColor(Color.rgb(24, 0, 0))
            setOnClickListener { click() }
            val pad = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            pad.setMargins(6, 0, 6, 0)
            layoutParams = pad
        }
    }

    /** Sad pose starts when the clip starts: blue light, ears left. */
    private fun sad() {
        if (!busReady()) return
        val pose = SdkMap.pose(Emotion.SAD)
        playCosy("sad", "Je suis triste.") {
            poseBody(pose.earCmd, pose.earStep, pose.earSpeedMs, pose.earAngle, 3, null, pose.faceId)
        }
    }

    /** Happy pose starts with the clip, plus gesture 11, the left crossed foot. */
    private fun happy() {
        if (!busReady()) return
        val pose = SdkMap.pose(Emotion.HAPPY)
        playCosy("happy", "Je suis content.") {
            poseBody(pose.earCmd, pose.earStep, pose.earSpeedMs, pose.earAngle, 6, 11, pose.faceId)
        }
    }

    private fun busReady(): Boolean {
        if (bus.connected()) return true
        VoiceHud.line = "bus pas prêt"
        return false
    }

    private fun poseBody(cmd: Int, step: Int, speedMs: Int, angle: Int, color: Int, motion: Int?, faceId: String) {
        bus.showFace(faceId)
        bus.ears(cmd, step, speedMs, angle)
        bus.antennaLight(true, color)
        if (motion != null) bus.controlMotion(motion, 1, 3)
        VoiceHud.diag = "visage $faceId oreilles $cmd lumière $color pied ${motion ?: "-"}"
    }

    private fun playCosy(emotion: String, line: String, onStart: () -> Unit) {
        restTicket += 1
        VoiceHud.mode = "think"
        VoiceHud.line = "cosy $emotion"
        val base = intent.getStringExtra("sidecar")?.trim()?.trimEnd('/')
            .orEmpty()
            .ifBlank { HostConfig.SIDECAR }
        Thread {
            val audio = try {
                postCosy(base, CosyTts.MODEL, line, emotion)
            } catch (e: Exception) {
                VoiceHud.diag = e.javaClass.simpleName
                ByteArray(0)
            }
            handler.post {
                if (audio.isEmpty()) {
                    VoiceHud.mode = "idle"
                    VoiceHud.line = "cosy $emotion: pas de son ($base)"
                } else {
                    playBytes(audio, "cosy $emotion", onStart)
                }
            }
        }.start()
    }

    private fun playBytes(wav: ByteArray, label: String, onStart: () -> Unit) {
        val file = File(cacheDir, "cosy-test.wav")
        file.writeBytes(wav)
        sample?.release()
        sample = null
        val mp = MediaPlayer()
        sample = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                VoiceHud.mode = "idle"
                VoiceHud.line = "$label fini"
                if (sample === it) {
                    it.release()
                    sample = null
                }
                restLater()
            }
            mp.setOnErrorListener { player, what, extra ->
                VoiceHud.line = "$label $what/$extra"
                player.release()
                if (sample === player) sample = null
                true
            }
            mp.prepare()
            mp.start()
            onStart()
            VoiceHud.line = label
            VoiceHud.mode = "talk"
        } catch (e: Exception) {
            mp.release()
            sample = null
            VoiceHud.line = "$label: ${e.javaClass.simpleName}"
        }
    }

    private fun restLater() {
        val ticket = restTicket
        handler.postDelayed({
            if (ticket != restTicket) return@postDelayed
            standDown()
        }, PoseTiming.REST_AFTER_MS)
    }

    private fun playFace(id: String) {
        if (slots.size < 2) {
            pendingFace = id
            return
        }
        // First clip: the front view. Afterwards: the hidden one, once its old player is gone.
        val target = if (facePlayer == null) slots[front] else slots[1 - front]
        // A clip still starting on the hidden view (no first frame yet): wait for its swap.
        val starting = target.player != null && target !== slots[front]
        if (!target.view.isAvailable || target.surface == null || target.releasing || starting) {
            pendingFace = id
            return
        }
        startFace(id, target)
    }

    /**
     * TextureView, not VideoView: a SurfaceView covers the controls, and VideoView pauses when speech starts.
     * Release the current player before preparing the next one: a Surface accepts one producer
     * at a time, so preparing first failed with "BufferQueueProducer already connected" and
     * the screen stayed on the first clip (h0059) for every later face. A missing asset is
     * handled by [localFace], so releasing first no longer leaves a frozen frame.
     * A clip that still fails is marked as shown, so the 120 ms refresh does not retry it in a loop.
     */
    private var driftStep = 0
    private val drift = object : Runnable {
        override fun run() {
            val (dx, dy) = DRIFT[driftStep % DRIFT.size]
            val alpha = if (driftStep % 2 == 0) 1f else DRIFT_ALPHA
            driftStep++
            for (slot in slots) {
                slot.view.translationX = dx
                slot.view.translationY = dy
            }
            slots.getOrNull(front)?.view?.alpha = alpha
            handler.postDelayed(this, DRIFT_STEP_MS)
        }
    }

    /**
     * idle_video=once / N s: while the last frame waits for the next play, move the picture by
     * a few pixels and dim it slightly every [DRIFT_STEP_MS], so no pixel stays fixed (burn-in).
     * One recomposition every 2 s: a few tenths of a percent of CPU.
     */
    private fun startDrift() {
        handler.removeCallbacks(drift)
        EventLog.add("hud idle gap: drift")
        handler.post(drift)
    }

    private fun stopDrift() {
        handler.removeCallbacks(drift)
        for (slot in slots) {
            slot.view.translationX = 0f
            slot.view.translationY = 0f
        }
        slots.getOrNull(front)?.view?.alpha = 1f
    }

    private fun startFace(id: String, target: FaceSlot) {
        pendingFace = null
        stopDrift()
        val surface = target.surface ?: return
        val local = localFace(id)
        shownFace = id
        val mp = MediaPlayer()
        try {
            assets.openFd(bundledFace(local)).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setSurface(surface)
            mp.setVolume(0f, 0f)
            // At rest, idle_video=once / N s stops the decoder after one play: the last frame stays
            // on the TextureView and the RenderThread goes quiet. Dialogue clips always loop.
            val rest = VoiceHud.mode == "idle" && !VoiceHud.playing
            val idle = VoiceHud.idleVideo
            mp.isLooping = !rest || idle.loop
            if (rest && !idle.loop) {
                mp.setOnCompletionListener { done ->
                    if (facePlayer !== done) return@setOnCompletionListener
                    // Between two plays the last frame stays: keep it moving a little (no burn-in).
                    startDrift()
                    if (idle.replaySec > 0) {
                        handler.postDelayed({
                            if (facePlayer === done && shownFace == id && VoiceHud.mode == "idle") {
                                stopDrift()
                                try {
                                    done.seekTo(0)
                                    done.start()
                                } catch (_: IllegalStateException) {
                                }
                            }
                        }, idle.replaySec * 1_000L)
                    }
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                VoiceHud.diag = "visage $local $what/$extra"
                EventLog.add("hud error $local $what/$extra")
                true
            }
            val previous = slots.firstOrNull { it !== target && it.player != null }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    handler.post { swapTo(target, mp, previous) }
                }
                false
            }
            mp.prepare()
            target.player = mp
            facePlayer = mp
            mp.start()
            if (previous == null) swapTo(target, mp, null)
            // No first-frame event (a broken clip): swap anyway so the screen does not stay behind.
            else handler.postDelayed({ swapTo(target, mp, previous) }, 1_500L)
            EventLog.add("hud play $id${if (local != id) " (as $local)" else ""} mode=${VoiceHud.mode} playing=${VoiceHud.playing}${if (mp.isLooping) "" else if (rest && VoiceHud.idleVideo.replaySec > 0) " replay=${VoiceHud.idleVideo.replaySec}s" else " once"}")
            if (local != id) VoiceHud.diag = "visage $id absent, $local"
        } catch (e: Exception) {
            VoiceHud.diag = "visage $id ${e.javaClass.simpleName}"
            EventLog.add("hud fail $id: ${e.javaClass.simpleName} ${e.message ?: ""}")
            mp.release()
        }
    }

    private fun hasFaceAsset(id: String): Boolean = try {
        assets.openFd(bundledFace(id)).close()
        true
    } catch (_: Exception) {
        false
    }

    /** If a dialogue face is not in this APK, keep a bundled stand-in so the TextureView stays alive. */
    private fun localFace(id: String): String {
        if (hasFaceAsset(id)) return id
        val fallback = when (id) {
            "h0347" -> "h0180"
            "h0242" -> "h0181"
            else -> com.geeui.voice.bus.IDLE_FACE
        }
        return if (hasFaceAsset(fallback)) fallback else id
    }

    /** Show [target] once [mp] drew its first frame, then let the old player go off the main thread. */
    private fun swapTo(target: FaceSlot, mp: MediaPlayer, previous: FaceSlot?) {
        if (target.player !== mp) return
        target.view.alpha = 1f
        front = slots.indexOf(target)
        val old = previous ?: return
        if (old.player == null) return
        if (old === target) return
        old.view.alpha = 0f
        val player = old.player ?: return
        old.player = null
        old.releasing = true
        val started = System.currentTimeMillis()
        releaser.execute {
            try {
                player.setOnCompletionListener(null)
                player.setOnInfoListener(null)
                player.stop()
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
            val took = System.currentTimeMillis() - started
            if (took > 1_000L) EventLog.add("hud release lent ${took} ms")
            handler.post {
                old.releasing = false
                pendingFace?.let { playFace(it) }
            }
        }
        pendingFace?.let { playFace(it) }
    }

    private fun releaseSlot(slot: FaceSlot) {
        slot.player?.let { p ->
            if (facePlayer === p) facePlayer = null
            try {
                p.release()
            } catch (_: Exception) {
            }
        }
        slot.player = null
        slot.surface?.release()
        slot.surface = null
    }

    private fun releaseFace() {
        for (slot in slots) releaseSlot(slot)
        facePlayer = null
    }

    private fun standDown() {
        if (!::bus.isInitialized || !bus.connected()) return
        bus.standAtAttention()
        VoiceHud.diag = "lumière off, garde-à-vous"
    }

    /** Bundled clip, same MediaPlayer path as LTPAudioService. No mic, no Lemonade. */
    private fun playSample(asset: String) {
        sample?.release()
        sample = null
        val mp = MediaPlayer()
        sample = mp
        try {
            assets.openFd(asset).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setOnCompletionListener {
                VoiceHud.mode = "idle"
                VoiceHud.line = "$asset fini"
                if (sample === it) {
                    it.release()
                    sample = null
                }
            }
            mp.setOnErrorListener { player, what, extra ->
                VoiceHud.line = "$asset $what/$extra"
                player.release()
                if (sample === player) sample = null
                true
            }
            mp.prepare()
            mp.start()
            VoiceHud.heard = "Olivier, il fait beau aujourd'hui, n'est-ce pas ?"
            VoiceHud.answer = "Ah ah ah !"
            VoiceHud.line = asset
            VoiceHud.mode = "talk"
        } catch (e: Exception) {
            mp.release()
            sample = null
            VoiceHud.line = "$asset: ${e.javaClass.simpleName}"
        }
    }

    private fun askMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            send(VoiceService.ACTION_START)
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 1)
        }
        // The camera is optional: without it, "qu'est-ce que tu vois ?" just says it cannot see.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
        if (code == 2) return // camera: optional, refused = no vision
        if (code == 1 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            send(VoiceService.ACTION_START)
        } else {
            VoiceHud.line = "autorise le micro dans les réglages"
            val settings = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
            startActivity(settings)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setTurnScreenOn(true)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
    }

    private fun send(action: String) {
        val next = Intent(this, VoiceService::class.java).setAction(action)
        next.putExtras(getIntent())
        if (action == VoiceService.ACTION_START) {
            for ((key, value) in VoiceSettings.effective(this, getIntent())) {
                next.putExtra(VoiceSettings.extra(key), value)
            }
        }
        startForegroundService(next)
    }

    override fun onDestroy() {
        sample?.release()
        sample = null
        releaseFace()
        releaser.shutdown()
        super.onDestroy()
    }

    // The face refresh runs while the activity is visible (onStart..onStop), so a translucent
    // activity on top (the Letianpai desktop) does not freeze our clip. VoiceHud.visible means
    // "resumed and on top": the loop brings us back when it is false during a turn.
    override fun onStart() {
        super.onStart()
        EventLog.init(this)
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        VoiceHud.visible = true
        EventLog.add("hud front mode=${VoiceHud.mode}")
    }

    override fun onPause() {
        VoiceHud.visible = false
        EventLog.add("hud behind mode=${VoiceHud.mode}")
        super.onPause()
    }

    companion object {
        /** How long the answer's emotion stays after the speech (talk_face=speak). */
        private const val AFTERGLOW_MS = 3_000L
        private const val DRIFT_STEP_MS = 2_000L
        private const val DRIFT_ALPHA = 0.93f
        /** A 3 px circle, one point per step. */
        private val DRIFT = listOf(
            3f to 0f, 2f to 2f, 0f to 3f, -2f to 2f, -3f to 0f, -2f to -2f, 0f to -3f, 2f to -2f,
        )
    }
}
