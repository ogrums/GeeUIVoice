package com.geeui.voiceemo

/**
 * Face videos h0xxx.mp4 already on the robot (GeeUIFace app/src/main/assets/video).
 * Codes are picked from how GeeUIFace's GestureCenter uses them; edit here to taste.
 *
 * | key      | code  | where GeeUIFace uses it                                   |
 * |----------|-------|-----------------------------------------------------------|
 * | happy    | h0006 | wakeupSpeak, foundOwner, win                              |
 * | sad      | h0119 | existing app choice (HandsGestureCenter, unlabeled)       |
 * | angry    | h0001 | getWakeupFennuGesture (愤怒 = anger)                      |
 * | fear     | h0133 | existing app choice (gun hand gesture)                    |
 * | surprise | h0046 | existing app choice (youPinGestures, unlabeled)           |
 * | neutral  | h0059 | idle / standby                                            |
 * | love     | h0027 | MCUCommandConsts.COMMAND_VALUE_LOVE                       |
 * | heart    | h0116 | hands gesture 比心 (finger heart)                          |
 * | laugh    | h0002 | getNoListWinGesture (big win)                             |
 * | cry      | h0010 | getLossGesture                                            |
 * | thinking | h0181 | getUnderstandGesture (AI thinking)                        |
 * | sleepy   | h0039 | goToSleepGesture                                          |
 * | cold     | h0051 | miFeelCold                                                |
 * | hot      | h0099 | miFeelHot                                                 |
 * | wink     | h0131 | hands gesture "yeah"                                      |
 */
object FaceCodes {
    val EMOTION: Map<Emotion, String> = mapOf(
        Emotion.HAPPY to "h0006",
        Emotion.SAD to "h0119",
        Emotion.ANGRY to "h0001",
        Emotion.FEAR to "h0133",
        Emotion.SURPRISE to "h0046",
        Emotion.NEUTRAL to "h0059",
    )

    /** Nuances finer than [Emotion]: name -> (emotion that drives voice/body, face code). */
    val NUANCE: Map<String, Pair<Emotion, String>> = mapOf(
        "love" to (Emotion.HAPPY to "h0027"),
        "heart" to (Emotion.HAPPY to "h0116"),
        "laugh" to (Emotion.HAPPY to "h0002"),
        "wink" to (Emotion.HAPPY to "h0131"),
        "cry" to (Emotion.SAD to "h0010"),
        "thinking" to (Emotion.NEUTRAL to "h0181"),
        "sleepy" to (Emotion.NEUTRAL to "h0039"),
        "cold" to (Emotion.NEUTRAL to "h0051"),
        "hot" to (Emotion.NEUTRAL to "h0099"),
    )

    fun of(emotion: Emotion): String = EMOTION.getValue(emotion)
}
