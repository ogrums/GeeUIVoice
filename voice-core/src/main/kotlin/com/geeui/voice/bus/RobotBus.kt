package com.geeui.voice.bus

import com.geeui.voiceemo.SdkPose

/**
 * What Lex calls setSpeechCmd / setTTS / setMcuCommand on ILetianpaiService.
 * The Android service will bind the real AIDL. Tests use [RecordingBus].
 */
interface RobotBus {
    fun speechCmd(command: String, data: String)
    fun speakText(text: String)
    fun controlMotion(number: Int, step: Int, speed: Int)
    fun showFace(faceId: String)
    fun ears(cmd: Int, step: Int, speedMs: Int, angle: Int)
    fun antennaLight(on: Boolean, color: Int)
}

fun lightId(name: String?): Int? = when (name) {
    "RED" -> 1
    "GREEN" -> 2
    "BLUE" -> 3
    "ORANGE" -> 4
    "WHITE" -> 5
    "YELLOW" -> 6
    "PURPLE" -> 7
    "CYAN" -> 8
    "BLACK" -> 9
    else -> null
}

/** Face, ears, antenna light, and the emotion gesture. Neutral turns the light off and does not step. */
fun RobotBus.applyPose(pose: SdkPose) {
    showFace(pose.faceId)
    ears(pose.earCmd, pose.earStep, pose.earSpeedMs, pose.earAngle)
    val color = lightId(pose.light)
    if (color == null) antennaLight(false, 0) else antennaLight(true, color)
    val action = pose.action
    if (action != null) controlMotion(action, 1, 3)
}

/** How long the pose stays after the clip ends, before 立正. */
object PoseTiming {
    const val REST_AFTER_MS = 3_000L
}
/** Motion 0 is AT+MOVEW,0 (立正). The idle face is h0059. Angle 0 is rewritten to 90, so the ears come back to 15. */
fun RobotBus.standAtAttention() {
    showFace("h0059")
    antennaLight(false, 0)
    ears(3, 1, 400, 15)
    controlMotion(0, 1, 1)
}

/** What GeeUIFace and the task service both accept for controlFace. */
fun faceCommand(id: String): String =
    """{"face":"$id","filePrefix":null,"times":null,"desc":null,"id":0,"is24HourGesture":false}"""

/** GeeUIFace plays `sdcard/assets/video/h0001.mp4`. The resource provider is not on every robot. */
fun faceFile(id: String, exists: (String) -> Boolean): String? {
    val candidates = listOf(
        "/sdcard/assets/video/$id.mp4",
        "/storage/emulated/0/assets/video/$id.mp4",
        "/sdcard/video/$id.mp4",
        "/storage/emulated/0/video/$id.mp4",
    )
    return candidates.firstOrNull(exists)
}

class RecordingBus : RobotBus {
    val events = mutableListOf<String>()

    override fun speechCmd(command: String, data: String) {
        events += "speech $command $data"
    }

    override fun speakText(text: String) {
        events += "tts $text"
    }

    override fun controlMotion(number: Int, step: Int, speed: Int) {
        events += "motion $number $step $speed"
    }

    override fun showFace(faceId: String) {
        events += "face $faceId"
    }

    override fun ears(cmd: Int, step: Int, speedMs: Int, angle: Int) {
        events += "ears $cmd $step $speedMs $angle"
    }

    override fun antennaLight(on: Boolean, color: Int) {
        events += if (on) "light $color" else "light off"
    }
}
