package com.geeui.voice

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.geeui.voice.bus.FaceBackend
import com.geeui.voice.bus.RuxFaceProtocol

/**
 * Face backend switch (setting `face_backend`). With `ruxface`, the face is drawn by the RuxFace app
 * (com.ogrums.ruxface, vector / OpenGL) instead of the GeeUIFace clips:
 * - [AidlBus.showFace] only records the face (no clip decoded by GeeUIFace),
 * - [VoiceLoop] sends the dialogue face ([com.geeui.voice.bus.FaceDirector]) to RuxFace by broadcast,
 * - [PlayingTts] sends the voice loudness (mouth),
 * - [ScreenOn.bringToFront] puts RuxFace on top instead of VoiceActivity.
 * If RuxFace is not installed, [active] is false and everything stays as before (GeeUIFace).
 */
object RuxFaceLink {
    private const val TAG = "GeeUIVoiceRux"

    @Volatile var backend: FaceBackend = FaceBackend.GEEUIFACE

    @Volatile private var installed = false
    @Volatile private var checkedAt = -1L
    @Volatile private var lastFace = ""
    @Volatile private var lastFaceAt = 0L
    @Volatile private var lastFrontAt = 0L

    /** True when the RuxFace backend is chosen AND the app is installed (checked at most every 10 s). */
    fun active(context: Context): Boolean {
        if (backend != FaceBackend.RUXFACE) return false
        val now = SystemClock.elapsedRealtime()
        if (checkedAt < 0 || now - checkedAt > 10_000L) {
            val first = checkedAt < 0
            val was = installed
            installed = try {
                context.packageManager.getPackageInfo(RuxFaceProtocol.PACKAGE, 0); true
            } catch (_: Exception) { false }
            checkedAt = now
            if (first || was != installed) {
                Log.i(TAG, if (installed) "RuxFace présent : visage vectoriel" else "RuxFace absent : repli GeeUIFace")
            }
        }
        return installed
    }

    /** Show face [id] (hNNNN). Unchanged faces are not re-sent, except every [RuxFaceProtocol.RESEND_MS]. */
    fun face(context: Context, id: String) {
        if (id.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (id == lastFace && now - lastFaceAt < RuxFaceProtocol.RESEND_MS) return
        val changed = id != lastFace
        lastFace = id; lastFaceAt = now
        send(context, Intent(RuxFaceProtocol.ACTION_STATE).putExtra("face", id))
        if (changed) EventLog.add("ruxface face $id")
    }

    /** Voice loudness 0..1 → mouth opening, held [holdMs] ms. */
    fun level(context: Context, level: Float, holdMs: Int = RuxFaceProtocol.LEVEL_HOLD_MS) {
        send(context, Intent(RuxFaceProtocol.ACTION_LEVEL).putExtra("level", level.coerceIn(0f, 1f)).putExtra("hold", holdMs))
    }

    /** A composed expression or action, e.g. {"preset":"love","action":"wink"}. */
    fun params(context: Context, json: String) {
        send(context, Intent(RuxFaceProtocol.ACTION_PARAMS).putExtra("json", json))
        EventLog.add("ruxface params $json")
    }

    /**
     * Put RuxFace on top (OpenGL renderer, demo cycle off, current face). Rate-limited to one start
     * every 4 s unless [force]: a running RuxFace only gets onNewIntent.
     */
    fun front(context: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFrontAt < 4_000L) return
        lastFrontAt = now
        try {
            val launch = Intent().setClassName(RuxFaceProtocol.PACKAGE, RuxFaceProtocol.ACTIVITY)
                .putExtra("renderer", "gl")
                .putExtra("auto", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (lastFace.isNotBlank()) launch.putExtra("face", lastFace)
            context.startActivity(launch)
            EventLog.add("ruxface front")
        } catch (e: Exception) {
            Log.e(TAG, "start RuxFace failed", e)
            checkedAt = -1L   // re-check installation next time
        }
    }

    private fun send(context: Context, intent: Intent) {
        try {
            context.sendBroadcast(intent.setPackage(RuxFaceProtocol.PACKAGE))
        } catch (e: Exception) {
            Log.e(TAG, "broadcast ${intent.action} failed", e)
        }
    }
}
