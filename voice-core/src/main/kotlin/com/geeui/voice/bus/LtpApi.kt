package com.geeui.voice.bus

/**
 * Method order of the real ILetianpaiService. The transaction code is the
 * position plus one. A shorter AIDL calls the wrong method and the robot
 * drops the command without an error.
 */
object LtpApi {
    const val TOKEN = "com.renhejia.robot.letianpaiservice.ILetianpaiService"

    val methods = listOf(
        "getRobotStatus",
        "setCommand",
        "setRobotStatus",
        "registerCallback",
        "unregisterCallback",
        "setLongConnectCommand",
        "registerLCCallback",
        "unregisterLCCallback",
        "setMcuCommand",
        "registerMcuCmdCallback",
        "unregisterMcuCmdCallback",
        "setAudioEffect",
        "registerAudioEffectCallback",
        "unregisterAudioEffectCallback",
        "setExpression",
        "registerExpressionCallback",
        "unregisterExpressionCallback",
        "setAppCmd",
        "registerAppCmdCallback",
        "unregisterAppCmdCallback",
        "setRobotStatusCmd",
        "registerRobotStatusCallback",
        "unregisterRobotStatusCallback",
        "setTTS",
        "registerTTSCallback",
        "unregisterTTSCallback",
        "setSpeechCmd",
        "registerSpeechCallback",
        "unregisterSpeechCallback",
        "setSensorResponse",
        "registerSensorResponseCallback",
        "unregisterSensorResponseCallback",
        "setMiCmd",
        "registerMiCmdResponseCallback",
        "unregisterMiCmdResponseCallback",
        "setIdentifyCmd",
        "registerIdentifyCmdCallback",
        "unregisterIdentifyCmdCallback",
        "setBleCmd",
        "registerBleCmdCallback",
        "unregisterBleCmdCallback",
        "setBleResponse",
        "registerBleResponseCallback",
        "unregisterBleResponseCmdCallback",
    )

    fun code(name: String): Int {
        val index = methods.indexOf(name)
        if (index < 0) throw IllegalArgumentException(name)
        return index + 1
    }
}
