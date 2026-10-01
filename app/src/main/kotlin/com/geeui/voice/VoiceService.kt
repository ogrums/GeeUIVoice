package com.geeui.voice

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * adb shell am startservice -n com.geeui.voice/.VoiceService -e host http://192.168.1.10:13305/api/v1
 * Stop: am stopservice -n com.geeui.voice/.VoiceService
 */
class VoiceService : Service() {
    private var loop: VoiceLoop? = null
    private var bus: AidlBus? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val host = intent?.getStringExtra("host") ?: "http://127.0.0.1:13305/api/v1"
        if (loop == null) {
            val bound = AidlBus(this)
            bus = bound
            loop = VoiceLoop(bound, host, cacheDir).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loop?.stop()
        loop = null
        super.onDestroy()
    }
}
