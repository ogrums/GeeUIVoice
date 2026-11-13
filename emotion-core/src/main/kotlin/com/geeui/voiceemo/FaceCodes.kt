package com.geeui.voiceemo

/**
 * Face videos h0xxx.mp4 already on the robot (GeeUIFace app/src/main/assets/video).
 * Official names: [docs/tags/EXPRESSIONS-VISAGES.md]. Codes follow those names;
 * GestureCenter is only a hint when several clips share a meaning.
 *
 * | key      | code  | official name / why                                       |
 * |----------|-------|-----------------------------------------------------------|
 * | happy    | h0020 | 快乐 (joy). h0006 大笑 is an XD (eyes as a flat X), laugh |
 * | sad      | h0119 | 哭泣 (crying). h0211 is 伤心2, unused                     |
 * | angry    | h0001 | 愤怒 (anger). h0018 is 狂怒, stronger, unused             |
 * | fear     | h0133 | 害怕 (fear). Not the music screen h0134                   |
 * | surprise | h0046 | 惊讶 (surprise). h0047 is 惊讶2                           |
 * | disgust  | h0033 | 抗拒 (refuse / reject). No 恶心/厌恶 clip; not the       |
 * |          |       | rage of h0001 nor the idle frown loop h0058 皱眉环        |
 * | neutral  | h0059 | 常规环 (idle loop). Sister loops: h0060 笑容环, h0058    |
 * | love     | h0027 | 爱心 (heart)                                               |
 * | heart    | h0116 | 比心 (finger heart)                                        |
 * | laugh    | h0006 | 大笑, eyes as a flat X (XD). User-verified. Was h0002    |
 * | cry      | h0010 | 哭泣1 (crying, variant)                                    |
 * | thinking | h0181 | 语音思考 (voice thinking)                                  |
 * | sleepy   | h0039 | 睡觉 (sleeping)                                            |
 * | cold     | h0051 | 发抖 (shivering)                                           |
 * | hot      | h0099 | 感到热 (feeling hot)                                       |
 * | wink     | h0036 | 单眨眼 (single wink). Was h0131 V5 (slang "awesome")      |
 */
object FaceCodes {
    val EMOTION: Map<Emotion, String> = mapOf(
        Emotion.HAPPY to "h0020",
        Emotion.SAD to "h0119",
        Emotion.ANGRY to "h0001",
        Emotion.FEAR to "h0133",
        Emotion.SURPRISE to "h0046",
        Emotion.DISGUST to "h0033",
        Emotion.NEUTRAL to "h0059",
    )

    /** Nuances finer than [Emotion]: name -> (emotion that drives voice/body, face code). */
    val NUANCE: Map<String, Pair<Emotion, String>> = mapOf(
        "love" to (Emotion.HAPPY to "h0027"),
        "heart" to (Emotion.HAPPY to "h0116"),
        "laugh" to (Emotion.HAPPY to "h0006"),
        "wink" to (Emotion.HAPPY to "h0036"),
        "cry" to (Emotion.SAD to "h0010"),
        "thinking" to (Emotion.NEUTRAL to "h0181"),
        "sleepy" to (Emotion.NEUTRAL to "h0039"),
        "cold" to (Emotion.NEUTRAL to "h0051"),
        "hot" to (Emotion.NEUTRAL to "h0099"),
    )

    fun of(emotion: Emotion): String = EMOTION.getValue(emotion)
}
