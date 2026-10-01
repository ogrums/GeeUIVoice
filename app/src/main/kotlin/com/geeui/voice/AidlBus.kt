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

    override fun controlMotion(number: Int, step: Int, speed: Int) {
        val svc = api ?: return
        if (!motorOn) {
            svc.setMcuCommand("powerControl", """{"function":3,"status":1}""")
            motorOn = true
        }
        svc.setMcuCommand(
            "controlMotion",
            """{"motion":"null","number":$number,"speed":$speed,"desc":"null","id":0,"stepNum":$step}""",
        )
    }

    override fun showFace(faceId: String) {
        api?.setExpression("controlFace", faceId)
    }
}
