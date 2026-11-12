package com.geeui.voiceemo

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceCodesTest {
    @Test
    fun emotionFacesMatchOfficialNames() {
        assertEquals("h0006", FaceCodes.of(Emotion.HAPPY))   // 大笑
        assertEquals("h0119", FaceCodes.of(Emotion.SAD))     // 哭泣
        assertEquals("h0001", FaceCodes.of(Emotion.ANGRY))   // 愤怒
        assertEquals("h0133", FaceCodes.of(Emotion.FEAR))    // 害怕
        assertEquals("h0046", FaceCodes.of(Emotion.SURPRISE))// 惊讶
        assertEquals("h0033", FaceCodes.of(Emotion.DISGUST)) // 抗拒
        assertEquals("h0059", FaceCodes.of(Emotion.NEUTRAL)) // 常规环
    }

    @Test
    fun nuancesFollowOfficialNames() {
        assertEquals(Emotion.HAPPY to "h0027", FaceCodes.NUANCE.getValue("love"))     // 爱心
        assertEquals(Emotion.HAPPY to "h0116", FaceCodes.NUANCE.getValue("heart"))    // 比心
        assertEquals(Emotion.HAPPY to "h0006", FaceCodes.NUANCE.getValue("laugh"))    // 大笑, not 傲慢 h0002
        assertEquals(Emotion.HAPPY to "h0036", FaceCodes.NUANCE.getValue("wink"))     // 单眨眼, not V5 h0131
        assertEquals(Emotion.SAD to "h0010", FaceCodes.NUANCE.getValue("cry"))        // 哭泣1
        assertEquals(Emotion.NEUTRAL to "h0181", FaceCodes.NUANCE.getValue("thinking"))
        assertEquals(Emotion.NEUTRAL to "h0039", FaceCodes.NUANCE.getValue("sleepy"))
        assertEquals(Emotion.NEUTRAL to "h0051", FaceCodes.NUANCE.getValue("cold"))
        assertEquals(Emotion.NEUTRAL to "h0099", FaceCodes.NUANCE.getValue("hot"))
    }
}
