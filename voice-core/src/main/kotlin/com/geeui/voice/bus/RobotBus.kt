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

/** How long the pose stays after the clip ends, before 立正. A short tail, not a 3 s hold. */
object PoseTiming {
    const val REST_AFTER_MS = 800L
}
/** Motion 0 is AT+MOVEW,0 (立正). The rest face is RestFace.current (h0059, or the robot mood). Angle 0 is rewritten to 90, so the ears come back to 15. */
fun RobotBus.standAtAttention() {
    showFace(RestFace.current)
    antennaLight(false, 0)
    ears(3, 1, 400, 15)
    controlMotion(0, 1, 1)
}

/** What GeeUIFace and the task service both accept for controlFace. */
fun faceCommand(id: String): String =
    """{"face":"$id","filePrefix":null,"times":null,"desc":null,"id":0,"is24HourGesture":false}"""

/** Asset path inside the APK. The robot does not expose these files on disk. */
fun bundledFace(id: String) = "video/$id.mp4"

const val IDLE_FACE = "h0059"

/** Same ids as the stock assistant (GestureCenter): wake / avatar.listening. Eyes with a "?". */
const val LISTEN_FACE = "h0180"
/** avatar.understanding in GestureCenter: eyes and a light bulb, while STT and the LLM work. */
const val THINK_FACE = "h0181"
/** getSpeakingGesture in GestureCenter: eyes and a moving mouth. */
const val SPEAK_FACE = "h0292"

/**
 * Configurable dialogue face ids (settings `listen_face` / `think_face`).
 * Defaults match the stock assistant: [LISTEN_FACE], [THINK_FACE], [SPEAK_FACE].
 */
object DialogueFaces {
    @Volatile var listen: String = LISTEN_FACE
    @Volatile var think: String = THINK_FACE
    @Volatile var speak: String = SPEAK_FACE

    fun reset() {
        listen = LISTEN_FACE
        think = THINK_FACE
        speak = SPEAK_FACE
    }

    /** Accepts `h0180`, `H0180` or `0180`. Unknown values keep [fallback]. */
    fun parseId(raw: String?, fallback: String): String {
        val trimmed = raw?.trim()?.lowercase().orEmpty()
        if (trimmed.isEmpty()) return fallback
        val id = if (trimmed.startsWith("h")) trimmed else "h$trimmed"
        return if (id.matches(Regex("h\\d{4}"))) id else fallback
    }
}

/**
 * Face shown on our own screen for a dialogue state.
 * The same ids are also sent on the bus (`controlFace`) when the loop enters
 * hear / think, so the robot face matches even if the activity is not on top.
 * [mode] is VoiceHud.mode (hear, think, talk, idle). [playing] is true while the TTS clip plays.
 * [pose] is the face of the last pose (VoiceHud.face). An emotion face wins over the speaking
 * face, so the EmotionFusion reaction stays visible while the robot talks. Neutral or no pose
 * gets the mouth. After the clip, the pose face holds until standAtAttention puts the rest face back.
 */
fun dialogueFace(mode: String, playing: Boolean, pose: String): String {
    val emotion = pose.isNotBlank() && !RestFace.isRest(pose)
    return when {
        playing -> if (emotion) pose else DialogueFaces.speak
        mode == "hear" -> DialogueFaces.listen
        mode == "think" || mode == "talk" -> DialogueFaces.think
        else -> pose
    }
}

/** The button screen. Anything else is the face, full screen, with the mic open. */
fun debugLaunch(mode: String?, debug: String? = null): Boolean {
    if (mode?.trim()?.equals("debug", ignoreCase = true) == true) return true
    val flag = debug?.trim()?.lowercase()
    return flag == "1" || flag == "true" || flag == "yes"
}

class RecordingBus(private val clock: () -> Long = { System.currentTimeMillis() }) : RobotBus {
    val events = mutableListOf<String>()
    val faces = mutableListOf<com.geeui.voice.session.FaceEvent>()

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
        faces += com.geeui.voice.session.FaceEvent(faceId, clock())
    }

    override fun ears(cmd: Int, step: Int, speedMs: Int, angle: Int) {
        events += "ears $cmd $step $speedMs $angle"
    }

    override fun antennaLight(on: Boolean, color: Int) {
        events += if (on) "light $color" else "light off"
    }
}
