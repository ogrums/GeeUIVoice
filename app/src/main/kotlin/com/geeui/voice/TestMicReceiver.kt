package com.geeui.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * adb hook for a microphone probe (RMS + silenced flag). Off unless this
 * broadcast is sent explicitly. The voice service must already be running.
 *
 * adb shell am broadcast -a com.geeui.voice.TEST_MIC -n com.geeui.voice/.TestMicReceiver
 */
class TestMicReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MicProbe.ACTION) return
        val outDir = context.getExternalFilesDir("voicetest") ?: context.cacheDir
        outDir.mkdirs()
        val loop = VoiceService.loopOrNull()
        if (loop == null) {
            Log.w(MicProbe.TAG, "TEST_MIC: service ou boucle inactive")
            val result = File(outDir, "mic-test.json")
            result.writeText("{\"error\":\"service ou boucle inactive\"}\n")
            MicProbe.publish(result)
            val status = File(outDir, "status.txt")
            status.writeText("error\n")
            MicProbe.publish(status)
            return
        }
        Log.i(MicProbe.TAG, "TEST_MIC start")
        loop.enqueueMicProbe(outDir)
    }
}
