package com.geeui.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.geeui.voice.session.WavTestAccess
import com.geeui.voice.session.WavTestResult
import java.io.File

/**
 * adb hook for a one-shot WAV turn. Off unless this broadcast is sent explicitly.
 *
 * adb shell am broadcast -a com.geeui.voice.TEST_WAV \
 *   -n com.geeui.voice/.TestWavReceiver \
 *   --es path /sdcard/Android/data/com.geeui.voice/files/voicetest/in.wav
 *
 * The path must be under an allowlisted folder and end in .wav. The voice
 * service must already be running (the hook does not start the microphone loop).
 * Result: files/voicetest/result.json, status.txt, and a copy of the TTS clip.
 */
class TestWavReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WavTestAccess.ACTION) return
        val path = intent.getStringExtra("path")?.trim().orEmpty()
        val outDir = context.getExternalFilesDir("voicetest") ?: context.cacheDir
        outDir.mkdirs()
        if (!WavTestAccess.allowedPath(path)) {
            writeFail(outDir, path, "chemin refusé")
            return
        }
        val loop = VoiceService.loopOrNull()
        if (loop == null) {
            writeFail(outDir, path, "service ou boucle inactive : démarrez d'abord GeeUIVoice (START)")
            return
        }
        Log.i("GeeUIVoiceTest", "TEST_WAV path=$path")
        loop.enqueueWavTest(path, outDir)
    }

    private fun writeFail(outDir: File, path: String, error: String) {
        Log.w("GeeUIVoiceTest", error)
        File(outDir, "result.json").writeText(WavTestResult.fail(path, error).toJson())
        File(outDir, "status.txt").writeText("error\n")
    }
}
