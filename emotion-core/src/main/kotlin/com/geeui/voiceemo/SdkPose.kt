package com.geeui.voiceemo

/**
 * RobotSDK 2.5 pose. Ears are a gesture, not a servo.
 * cmd 1 and 3 are both documented as "both ears left". The demo uses 3.
 * angle is 0..90. Light names are the AAR constants, not the web page.
 * action is a number from the published 1..80 list. Null means no body move.
 * sound is a published a00xx id. Null means silence. Not robotPlayTTs.
 */
data class SdkPose(
    val faceId: String,
    val earCmd: Int,
    val earStep: Int,
    val earSpeedMs: Int,
    val earAngle: Int,
    val light: String?,
    val action: Int?,
    val sound: String?,
)

object SdkMap {
    fun pose(emotion: Emotion): SdkPose = when (emotion) {
        Emotion.HAPPY -> SdkPose("h0006", 3, 2, 250, 60, "YELLOW", 77, "a0032")
        Emotion.SAD -> SdkPose("h0119", 1, 1, 500, 40, "BLUE", 20, "a0086")
        Emotion.ANGRY -> SdkPose("h0001", 2, 2, 200, 70, "RED", 15, "a0020")
        Emotion.FEAR -> SdkPose("h0133", 1, 1, 400, 30, "CYAN", 44, "a0037")
        Emotion.SURPRISE -> SdkPose("h0046", 3, 1, 150, 90, "WHITE", 76, "a0095")
        Emotion.NEUTRAL -> SdkPose("h0059", 1, 1, 400, 0, null, null, null)
    }
}
