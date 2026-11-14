package com.geeui.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-disk journal of wakes, faces and screen focus. logcat "chatty"
 * drops most of our lines on this ROM, so this is what we read after a test:
 * `adb shell run-as com.geeui.voice cat files/events.log`
 */
object EventLog {
    private const val TAG = "GeeUIVoiceTest"
    private const val MAX_BYTES = 256 * 1024L
    @Volatile private var file: File? = null
    // UTC on purpose: the app process here reports a China time zone while the system shows Paris.
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "events.log")
    }

    fun add(message: String) {
        Log.i(TAG, message)
        val f = file ?: return
        try {
            synchronized(this) {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, "events.1.log")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText("${fmt.format(Date())} $message\n")
            }
        } catch (_: Exception) {
        }
    }
}
