package com.geeui.voiceemo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdkPoseTest {
    @Test
    fun angry_uses_named_face_red_and_a_stomp() {
        val pose = SdkMap.pose(Emotion.ANGRY)
        assertEquals("h0001", pose.faceId)
        assertEquals(2, pose.earCmd)
        assertEquals("RED", pose.light)
        assertEquals(15, pose.action)
        assertEquals("a0020", pose.sound)
        assertTrue(pose.earAngle in 0..90)
    }

    @Test
    fun fear_is_the_feishu_afraid_face_not_the_music_one() {
        val pose = SdkMap.pose(Emotion.FEAR)
        assertEquals("h0133", pose.faceId)
        assertEquals("a0037", pose.sound)
    }

    @Test
    fun neutral_closes_light_and_does_not_move() {
        val pose = SdkMap.pose(Emotion.NEUTRAL)
        assertEquals("h0059", pose.faceId)
        assertNull(pose.light)
        assertNull(pose.action)
        assertNull(pose.sound)
    }
}
