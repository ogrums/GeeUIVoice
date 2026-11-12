package com.geeui.voiceemo

import kotlin.test.Test
import kotlin.test.assertEquals

class EmotionFusionTest {
    @Test
    fun audio_above_threshold_beats_the_llm_tag() {
        val r = EmotionFusion(clock = { 0L }).fuse(Emotion.ANGRY, 0.90, Emotion.HAPPY, true)
        assertEquals(Emotion.ANGRY, r.emotion)
        assertEquals(0.90, r.score, 1e-9)
    }

    @Test
    fun audio_exactly_at_threshold_wins() {
        val r = EmotionFusion(clock = { 0L }).fuse(Emotion.SAD, 0.70, Emotion.HAPPY, true)
        assertEquals(Emotion.SAD, r.emotion)
        assertEquals(0.70, r.score, 1e-9)
    }

    @Test
    fun audio_below_threshold_falls_back_to_the_llm_tag() {
        val r = EmotionFusion(clock = { 0L }).fuse(Emotion.ANGRY, 0.69, Emotion.HAPPY, true)
        assertEquals(Emotion.HAPPY, r.emotion)
        assertEquals(EmotionFusion.TEXT_SCORE, r.score, 1e-9)
    }

    @Test
    fun audio_neutral_uses_the_llm_tag() {
        val r = EmotionFusion(clock = { 0L }).fuse(Emotion.NEUTRAL, 0.99, Emotion.SAD, true)
        assertEquals(Emotion.SAD, r.emotion)
    }

    @Test
    fun audio_below_threshold_without_a_tag_is_neutral() {
        val r = EmotionFusion(clock = { 0L }).fuse(Emotion.SAD, 0.50, Emotion.NEUTRAL, false)
        assertEquals(Emotion.NEUTRAL, r.emotion)
    }

    @Test
    fun audio_only_above_threshold_is_kept() {
        val r = EmotionFusion(clock = { 0L }).fuse(Emotion.DISGUST, 0.98, Emotion.NEUTRAL, false)
        assertEquals(Emotion.DISGUST, r.emotion)
        assertEquals(0.98, r.score, 1e-9)
    }

    @Test
    fun threshold_is_configurable() {
        val strict = EmotionFusion(clock = { 0L }, audioThreshold = 0.95)
        assertEquals(Emotion.HAPPY, strict.fuse(Emotion.ANGRY, 0.90, Emotion.HAPPY, true).emotion)
        val loose = EmotionFusion(clock = { 0L }, audioThreshold = 0.50)
        assertEquals(Emotion.ANGRY, loose.fuse(Emotion.ANGRY, 0.50, Emotion.HAPPY, true).emotion)
    }

    @Test
    fun default_audio_threshold_is_0_70() {
        assertEquals(0.70, EmotionFusion.AUDIO_THRESHOLD, 1e-9)
        assertEquals(0.70, EmotionFusion().audioThreshold, 1e-9)
    }

    @Test
    fun cooldown_applies_to_non_neutral_only() {
        var now = 10_000L
        val f = EmotionFusion(clock = { now })
        assertEquals(Emotion.HAPPY, f.fuse(Emotion.HAPPY, 1.0, Emotion.HAPPY, true).emotion)
        now += 1_000
        assertEquals(Emotion.NEUTRAL, f.fuse(Emotion.SAD, 1.0, Emotion.SAD, true).emotion)
        now += 1_000
        assertEquals(Emotion.NEUTRAL, f.fuse(Emotion.NEUTRAL, 1.0, Emotion.NEUTRAL, true).emotion)
        now += 2_000
        assertEquals(Emotion.SAD, f.fuse(Emotion.SAD, 1.0, Emotion.SAD, true).emotion)
    }
}

class EmotionParseTest {
    @Test
    fun disgusted_is_disgust_not_neutral() {
        assertEquals(Emotion.DISGUST, Emotion.parse("disgusted"))
        assertEquals(Emotion.DISGUST, Emotion.parse("DISGUST"))
        assertEquals(Emotion.DISGUST, Emotion.parse("disgust"))
        assertEquals(Emotion.DISGUST, Emotion.parse("dégoût"))
        assertEquals(Emotion.NEUTRAL, Emotion.parse("other"))
        assertEquals(Emotion.NEUTRAL, Emotion.parse("unknown"))
    }

    @Test
    fun disgust_face_is_h0033_refuse() {
        assertEquals("h0033", FaceCodes.of(Emotion.DISGUST))
        assertEquals("h0033", SdkMap.pose(Emotion.DISGUST).faceId)
        assertEquals("PURPLE", SdkMap.pose(Emotion.DISGUST).light)
        assertEquals(24, SdkMap.pose(Emotion.DISGUST).action)
        assertEquals("a0033", SdkMap.pose(Emotion.DISGUST).sound)
    }
}
