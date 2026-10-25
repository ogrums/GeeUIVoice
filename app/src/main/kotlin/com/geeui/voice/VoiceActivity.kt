package com.geeui.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
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
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File
import android.text.TextUtils
import com.geeui.voice.engine.CosyTts
import com.geeui.voice.bus.IDLE_FACE
import com.geeui.voice.bus.PoseTiming
import com.geeui.voice.bus.applyPose
import com.geeui.voice.bus.bundledFace
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
    private var faceView: TextureView? = null
    private var pictureView: ImageView? = null
    private var drawLine: TextView? = null
    private var shownPicture: ByteArray? = null
    private var facePlayer: MediaPlayer? = null
    private var faceSurface: Surface? = null
    private var pendingFace: String? = null
    private var shownFace = ""
    private var menu: ScrollView? = null
    private val editors = linkedMapOf<String, EditText>()
    private val refresh = object : Runnable {
        override fun run() {
            if (debug) paintDebug()
            syncPicture()
            val note = drawLine
            if (note != null) {
                val show = VoiceHud.drawing || VoiceHud.diag == "image refusée"
                note.visibility = if (show) View.VISIBLE else View.GONE
                if (show) note.text = VoiceHud.line.ifBlank { VoiceHud.diag }
            }
            if (shownPicture == null) {
                val face = VoiceHud.face.ifBlank { if (debug) "" else IDLE_FACE }
                if (face.isNotBlank() && face != shownFace) playFace(face)
            }
            handler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debug = debugLaunch(intent.getStringExtra("mode"), intent.getStringExtra("debug"))
        bus = AidlBus(this)
        val screen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        faceView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    faceSurface?.release()
                    faceSurface = Surface(surface)
                    pendingFace?.let { startFace(it) }
                }

                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit

                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                    releaseFace()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
            }
        }
        screen.addView(faceView)
        pictureView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        screen.addView(pictureView)
        drawLine = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            visibility = View.GONE
            setPadding(16, 8, 16, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply { bottomMargin = 90 }
        }
        screen.addView(drawLine)
        if (debug) screen.addView(debugPanel())
        menu = settingsMenu()
        screen.addView(menuBand())
        screen.addView(menu)
        setContentView(screen)
        if (debug) {
            VoiceHud.line = "micro coupé"
        } else {
            if (VoiceHud.face.isBlank()) VoiceHud.face = IDLE_FACE
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
            addView(box)
        }
    }

    private fun openMenu() {
        val values = VoiceSettings.effective(this, intent)
        for ((key, edit) in editors) edit.setText(values[key].orEmpty())
        menu?.visibility = View.VISIBLE
    }

    private fun closeMenu() {
        menu?.visibility = View.GONE
    }

    private fun saveMenu() {
        VoiceSettings.save(this, editors.mapValues { it.value.text.toString() })
        closeMenu()
        if (VoiceHud.mic) send(VoiceService.ACTION_START)
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
        root.addView(title)
        root.addView(status)
        root.addView(scan)
        root.addView(caption)
        root.addView(mic)
        root.addView(testRow(
            "Joyeux" to { emote(Emotion.HAPPY, "Je suis content.") },
            "Triste" to { emote(Emotion.SAD, "Je suis triste.") },
            "Colère" to { emote(Emotion.ANGRY, "Je suis en colère.") },
        ))
        root.addView(testRow(
            "Peur" to { emote(Emotion.FEAR, "J'ai peur.") },
            "Surprise" to { emote(Emotion.SURPRISE, "Oh, quelle surprise.") },
            "Calme" to { emote(Emotion.NEUTRAL, "Je reste calme.") },
        ))
        root.addView(testRow("WAV" to { playSample("olivier.wav") }))
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

    private fun testRow(vararg buttons: Pair<String, () -> Unit>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val pad = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 52)
            pad.setMargins(24, 4, 24, 0)
            layoutParams = pad
            buttons.forEach { (label, click) -> addView(testButton(label, click)) }
        }
    }

    /** Same pose the spoken turn uses. The clip and the body start together. */
    private fun emote(emotion: Emotion, line: String) {
        if (!busReady()) return
        val pose = SdkMap.pose(emotion)
        playCosy(emotion.name.lowercase(), line) {
            bus.applyPose(pose)
            VoiceHud.diag = "visage ${pose.faceId} geste ${pose.action ?: "-"} son ${pose.sound ?: "-"}"
        }
    }

    private fun busReady(): Boolean {
        if (bus.connected()) return true
        VoiceHud.line = "bus pas prêt"
        return false
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

    private fun syncPicture() {
        val png = VoiceHud.picture
        val showing = png != null && png.isNotEmpty() && System.currentTimeMillis() < VoiceHud.pictureUntil
        if (showing && png != null) {
            if (png !== shownPicture) showPicture(png)
            return
        }
        if (shownPicture != null) hidePicture()
    }

    private fun showPicture(png: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return
        shownPicture = png
        facePlayer?.release()
        facePlayer = null
        shownFace = ""
        pictureView?.setImageBitmap(bmp)
        pictureView?.visibility = View.VISIBLE
    }

    /** The drawing stays 7 seconds, then the idle face loop starts again. */
    private fun hidePicture() {
        val gone = shownPicture
        pictureView?.setImageDrawable(null)
        pictureView?.visibility = View.GONE
        shownPicture = null
        if (VoiceHud.picture === gone) {
            VoiceHud.picture = null
            VoiceHud.pictureUntil = 0L
        }
        VoiceHud.face = IDLE_FACE
        playFace(IDLE_FACE)
    }

    private fun playFace(id: String) {
        shownFace = id
        val view = faceView
        if (view != null && view.isAvailable && faceSurface != null) startFace(id)
        else pendingFace = id
    }

    /** TextureView, not VideoView: a SurfaceView covers the controls, and VideoView pauses when speech starts. */
    private fun startFace(id: String) {
        pendingFace = null
        val surface = faceSurface ?: return
        facePlayer?.release()
        val mp = MediaPlayer()
        facePlayer = mp
        try {
            assets.openFd(bundledFace(id)).use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            mp.setSurface(surface)
            mp.setVolume(0f, 0f)
            mp.isLooping = true
            mp.setOnErrorListener { _, what, extra ->
                VoiceHud.diag = "visage $id $what/$extra"
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            VoiceHud.diag = "visage $id ${e.javaClass.simpleName}"
            mp.release()
            if (facePlayer === mp) facePlayer = null
        }
    }

    private fun releaseFace() {
        facePlayer?.release()
        facePlayer = null
        faceSurface?.release()
        faceSurface = null
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
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, perms, grants)
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
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
