package com.geeui.voice

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Turn the screen on from a service on API 30: [PowerManager.ACQUIRE_CAUSES_WAKEUP]
 * still works here (TURN_SCREEN_ON is API 33+). The activity also sets
 * turnScreenOn / showWhenLocked. [AidlBus.exitDarkScreen] is called separately
 * so a Letianpai dark-sleep layer can drop if the command is accepted.
 */
class ScreenOn(private val context: Context) {
    private var lock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    fun turnOn() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE
            val next = pm.newWakeLock(flags, "geeui:hi-rux")
            next.setReferenceCounted(false)
            next.acquire(15_000L)
            lock?.let { if (it.isHeld) it.release() }
            lock = next
        } catch (e: Exception) {
            Log.e(TAG, "wake lock failed", e)
        }
        try {
            val launch = Intent(context, VoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(launch)
        } catch (e: Exception) {
            Log.e(TAG, "start VoiceActivity failed", e)
        }
    }

    fun release() {
        try {
            lock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        lock = null
    }

    private companion object {
        const val TAG = "GeeUIVoice"
    }
}
