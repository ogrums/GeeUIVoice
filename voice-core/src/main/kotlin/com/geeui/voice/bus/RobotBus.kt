package com.geeui.voice.bus

/**
 * What Lex calls setSpeechCmd / setTTS / setMcuCommand on ILetianpaiService.
 * The Android service will bind the real AIDL. Tests use [RecordingBus].
 */
interface RobotBus {
    fun speechCmd(command: String, data: String)
    fun speakText(text: String)
    fun controlMotion(number: Int, step: Int, speed: Int)
    fun showFace(faceId: String)
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
}
