package com.geeui.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.geeui.voice.bus.LtpApi
import com.geeui.voice.bus.RobotBus
import com.geeui.voice.bus.faceCommand

/** Forwards skills to the robot process. Motion opens the servo rail first. */
class AidlBus(context: Context) : RobotBus, ServiceConnection {
    private var remote: IBinder? = null
    private var motorOn = false

    init {
        val intent = Intent("android.intent.action.LETIANPAI")
            .setPackage("com.renhejia.robot.letianpaiservice")
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        remote = service
        Log.i(TAG, "letianpai bound")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        remote = null
        motorOn = false
        Log.i(TAG, "letianpai unbound")
    }

    fun connected(): Boolean = remote != null

    override fun speechCmd(command: String, data: String) {
        call("setSpeechCmd", command, data)
    }

    override fun speakText(text: String) {
        call("setTTS", "speakText", text)
    }

    override fun controlMotion(number: Int, step: Int, speed: Int) {
        if (remote == null) return
        openMotor()
        call(
            "setMcuCommand",
            "controlMotion",
            """{"motion":"null","number":$number,"speed":$speed,"desc":"null","id":0,"stepNum":$step}""",
        )
    }

    override fun ears(cmd: Int, step: Int, speedMs: Int, angle: Int) {
        if (remote == null) return
        openMotor()
        call(
            "setMcuCommand",
            "controlAntennaMotion",
            """{"cmd":$cmd,"step":$step,"speed":$speedMs,"angle":$angle}""",
        )
    }

    override fun antennaLight(on: Boolean, color: Int) {
        if (remote == null) return
        val state = if (on) "on" else "off"
        call(
            "setMcuCommand",
            "controlAntennaLight",
            """{"antenna_light":"$state","antenna_light_color":$color}""",
        )
    }

    /** Function 3 is the leg rail. Function 5 is the cliff rail. The face service turns both on. */
    private fun openMotor() {
        if (motorOn) return
        call("setMcuCommand", "powerControl", """{"function":3,"status":1}""")
        call("setMcuCommand", "powerControl", """{"function":5,"status":1}""")
        motorOn = true
    }

    override fun showFace(faceId: String) {
        VoiceHud.face = faceId
        call("setExpression", "controlFace", faceCommand(faceId))
    }

    private fun call(method: String, command: String, data: String) {
        val binder = remote ?: return
        val out = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            out.writeInterfaceToken(LtpApi.TOKEN)
            out.writeString(command)
            out.writeString(data)
            val ok = binder.transact(LtpApi.code(method), out, reply, 0)
            if (!ok) {
                Log.e(TAG, "$method rejected")
                VoiceHud.line = "mcu refusé"
                return
            }
            reply.readException()
            Log.i(TAG, "$method $command $data")
        } catch (e: Exception) {
            Log.e(TAG, "$method $command failed", e)
            VoiceHud.line = "mcu ${e.javaClass.simpleName}"
        } finally {
            out.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val TAG = "GeeUIVoice"
    }
}
