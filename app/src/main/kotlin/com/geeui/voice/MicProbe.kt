package com.geeui.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/**
 * adb hook: compare pause commands and AudioRecord sources, measure 3 s RMS
 * and the Android 11 `isClientSilenced` flag.
 *
 * adb shell am broadcast -a com.geeui.voice.TEST_MIC -n com.geeui.voice/.TestMicReceiver
 *
 * Writes mic-test.json and status.txt, also mirrored under /data/local/tmp/geeui/.
 */
object MicProbe {
    const val ACTION = "com.geeui.voice.TEST_MIC"
    const val TAG = "GeeUIVoiceTest"
    const val RECORD_MS = 3_000L
    const val RATE = 16_000
    const val FRAME = 320

    private val sources = listOf(
        "MIC" to MediaRecorder.AudioSource.MIC,
        "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
        "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
    )

    fun run(context: Context, bus: AidlBus?, outDir: File): File {
        outDir.mkdirs()
        val status = File(outDir, "status.txt")
        status.writeText("running\n")
        publish(status)
        val trials = JSONArray()
        try {
            // Baseline first: current stock recorder still running, MIC only.
            trials.put(recordOne(context, "none", "MIC", MediaRecorder.AudioSource.MIC))
            // The command we used until now, then the official RobotStatus one.
            // After the official stop, try every source.
            for (pause in listOf("app", "status")) {
                applyPause(bus, pause)
                Thread.sleep(500)
                val list = if (pause == "status") sources else listOf(sources.first())
                for ((name, src) in list) {
                    trials.put(recordOne(context, pause, name, src))
                }
                applyResume(bus, pause)
                Thread.sleep(500)
            }
        } finally {
            // Always try to put Lex back, even if a trial crashed mid-pause.
            try {
                bus?.resumeLexAudio()
            } catch (_: Exception) {
            }
        }
        val result = JSONObject()
        result.put("record_ms", RECORD_MS)
        result.put("rate", RATE)
        result.put("trials", trials)
        val file = File(outDir, "mic-test.json")
        file.writeText(result.toString(2) + "\n")
        publish(file)
        status.writeText("done\n")
        publish(status)
        Log.i(TAG, "TEST_MIC done ${file.absolutePath}")
        return file
    }

    fun logClientState(tag: String, am: AudioManager, sessionId: Int) {
        val row = clientState(am, sessionId)
        Log.i(TAG, "$tag $row")
    }

    private fun applyPause(bus: AidlBus?, mode: String) {
        when (mode) {
            "app" -> bus?.appCmd("stop_audio_service", "com.geeui.lex")
            "status" -> bus?.pauseLexAudio()
        }
        Log.i(TAG, "mic probe pause=$mode")
    }

    private fun applyResume(bus: AidlBus?, mode: String) {
        when (mode) {
            "app" -> bus?.appCmd("start_audio_service", "com.geeui.lex")
            "status" -> bus?.resumeLexAudio()
        }
        Log.i(TAG, "mic probe resume=$mode")
    }

    private fun recordOne(context: Context, pause: String, sourceName: String, source: Int): JSONObject {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val obj = JSONObject()
        obj.put("pause", pause)
        obj.put("source", sourceName)
        obj.put("clients_before", clientsJson(am))
        val min = AudioRecord.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (min <= 0) {
            obj.put("initialized", false)
            obj.put("error", "minBuffer=$min")
            Log.i(TAG, "mic probe pause=$pause source=$sourceName minBuffer failed")
            return obj
        }
        val rec = AudioRecord(
            source,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            min.coerceAtLeast(FRAME * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            obj.put("initialized", false)
            obj.put("error", "not initialized")
            Log.i(TAG, "mic probe pause=$pause source=$sourceName not initialized")
            return obj
        }
        obj.put("initialized", true)
        obj.put("session", rec.audioSessionId)
        val buf = ShortArray(FRAME)
        var sumSq = 0.0
        var peak = 0
        var n = 0
        var silencedMid: Boolean? = null
        try {
            rec.startRecording()
            val until = System.currentTimeMillis() + RECORD_MS
            var mid = false
            while (System.currentTimeMillis() < until) {
                val got = rec.read(buf, 0, FRAME)
                if (got > 0) {
                    var i = 0
                    while (i < got) {
                        val s = buf[i].toInt()
                        val a = if (s < 0) -s else s
                        if (a > peak) peak = a
                        sumSq += s.toDouble() * s.toDouble()
                        i++
                    }
                    n += got
                }
                if (!mid && System.currentTimeMillis() >= until - RECORD_MS / 2) {
                    mid = true
                    val state = clientState(am, rec.audioSessionId)
                    silencedMid = state.optBoolean("silenced", false)
                    obj.put("client", state)
                }
            }
        } catch (e: Exception) {
            obj.put("error", e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                rec.stop()
            } catch (_: IllegalStateException) {
            }
            rec.release()
        }
        val rms = if (n > 0) sqrt(sumSq / n) / 32768.0 else 0.0
        obj.put("frames", n)
        obj.put("rms", rms)
        obj.put("peak", peak)
        if (silencedMid != null) obj.put("silenced", silencedMid)
        obj.put("clients_after", clientsJson(am))
        val sil = obj.opt("silenced")
        Log.i(
            TAG,
            "mic probe pause=$pause source=$sourceName rms=${"%.5f".format(rms)} peak=$peak silenced=$sil session=${obj.opt("session")}",
        )
        return obj
    }

    private fun clientsJson(am: AudioManager): JSONArray {
        val arr = JSONArray()
        for (c in am.activeRecordingConfigurations) {
            arr.put(clientState(c))
        }
        return arr
    }

    private fun clientState(am: AudioManager, sessionId: Int): JSONObject {
        for (c in am.activeRecordingConfigurations) {
            if (c.clientAudioSessionId == sessionId) return clientState(c)
        }
        return JSONObject().put("session", sessionId).put("present", false)
    }

    private fun clientState(c: AudioRecordingConfiguration): JSONObject {
        val obj = JSONObject()
        obj.put("session", c.clientAudioSessionId)
        obj.put("source", c.audioSource)
        obj.put("silenced", c.isClientSilenced)
        val fmt = c.format
        obj.put("rate", fmt.sampleRate)
        obj.put("channels", fmt.channelCount)
        val pkg = try {
            val m = c.javaClass.methods.firstOrNull { it.name == "getClientPackageName" && it.parameterCount == 0 }
            m?.invoke(c) as? String
        } catch (_: Exception) {
            null
        }
        if (!pkg.isNullOrBlank()) obj.put("package", pkg)
        return obj
    }

    fun publish(file: File) {
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
}
