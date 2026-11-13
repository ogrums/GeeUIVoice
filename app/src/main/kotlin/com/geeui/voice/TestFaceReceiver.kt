package com.geeui.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * adb hook to show one face on the bus and the local TextureView.
 *
 * adb shell am broadcast -a com.geeui.voice.TEST_FACE \
 *   -n com.geeui.voice/.TestFaceReceiver --es face h0347
 *
 * The voice service must already be running.
 */
class TestFaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val face = intent.getStringExtra("face")?.trim().orEmpty()
        val loop = VoiceService.loopOrNull()
        if (loop == null) {
            Log.w("GeeUIVoiceTest", "TEST_FACE: service ou boucle inactive")
            return
        }
        Log.i("GeeUIVoiceTest", "TEST_FACE face=$face")
        loop.debugShowFace(face)
    }

    companion object {
        const val ACTION = "com.geeui.voice.TEST_FACE"
    }
}
