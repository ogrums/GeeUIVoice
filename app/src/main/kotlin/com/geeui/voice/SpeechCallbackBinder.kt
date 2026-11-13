package com.geeui.voice

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.geeui.voice.bus.LtpApi

/**
 * Manual stub for [LtpApi.SPEECH_CALLBACK_TOKEN]. The short committed AIDL
 * must not be extended: a generated stub would get the wrong transaction
 * codes on ILetianpaiService. Lex calls [onSpeechCommandReceived] with
 * `wakeup_status` or `wakeup_doa` when the stock "Hi RUX" engine fires.
 */
class SpeechCallbackBinder(
    private val onCommand: (command: String, data: String) -> Unit,
) : Binder() {
    init {
        attachInterface(null, LtpApi.SPEECH_CALLBACK_TOKEN)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            IBinder.INTERFACE_TRANSACTION -> {
                reply?.writeString(LtpApi.SPEECH_CALLBACK_TOKEN)
                return true
            }
            IBinder.FIRST_CALL_TRANSACTION -> {
                data.enforceInterface(LtpApi.SPEECH_CALLBACK_TOKEN)
                val command = data.readString().orEmpty()
                val payload = data.readString().orEmpty()
                try {
                    onCommand(command, payload)
                } catch (e: Exception) {
                    Log.e(TAG, "speech callback $command failed", e)
                }
                if (flags and IBinder.FLAG_ONEWAY == 0) {
                    reply?.writeNoException()
                }
                return true
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
    }

    private companion object {
        const val TAG = "GeeUIVoice"
    }
}
