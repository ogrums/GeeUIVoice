package com.geeui.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.geeui.voice.bus.RobotBus
import com.renhejia.robot.letianpaiservice.ILetianpaiService

/** Forwards skills to the robot process. Motion opens the servo rail first. */
class AidlBus(context: Context) : RobotBus, ServiceConnection {
    private var api: ILetianpaiService? = null
    private var motorOn = false

    init {
        val intent = Intent("android.intent.action.LETIANPAI")
            .setPackage("com.renhejia.robot.letianpaiservice")
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        api = ILetianpaiService.Stub.asInterface(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        api = null
        motorOn = false
    }

    override fun speechCmd(command: String, data: String) {
        api?.setSpeechCmd(command, data)
    }

    override fun speakText(text: String) {
        api?.setTTS("speakText", text)
    }

    fun connected(): Boolean = api != null

    override fun controlMotion(number: Int, step: Int, speed: Int) {
        val svc = api ?: return
        openMotor(svc)
        svc.setMcuCommand(
            "controlMotion",
            """{"motion":"null","number":$number,"speed":$speed,"desc":"null","id":0,"stepNum":$step}""",
        )
    }

    override fun ears(cmd: Int, step: Int, speedMs: Int, angle: Int) {
        val svc = api ?: return
        openMotor(svc)
        svc.setMcuCommand(
            "controlAntennaMotion",
            """{"cmd":$cmd,"step":$step,"speed":$speedMs,"angle":$angle}""",
        )
    }

    override fun antennaLight(on: Boolean, color: Int) {
        val svc = api ?: return
        val state = if (on) "on" else "off"
        svc.setMcuCommand(
            "controlAntennaLight",
            """{"antenna_light":"$state","antenna_light_color":$color}""",
        )
    }

    /** Function 3 is the leg rail. Function 5 is the cliff rail. The face service turns both on. */
    private fun openMotor(svc: ILetianpaiService) {
        if (!motorOn) {
            svc.setMcuCommand("powerControl", """{"function":3,"status":1}""")
            svc.setMcuCommand("powerControl", """{"function":5,"status":1}""")
            motorOn = true
        }
    }

    override fun showFace(faceId: String) {
        api?.setExpression("controlFace", faceId)
    }
}
