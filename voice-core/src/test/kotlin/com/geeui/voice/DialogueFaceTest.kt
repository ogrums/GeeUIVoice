package com.geeui.voice

import com.geeui.voice.bus.IDLE_FACE
import com.geeui.voice.bus.LISTEN_FACE
import com.geeui.voice.bus.SPEAK_FACE
import com.geeui.voice.bus.THINK_FACE
import com.geeui.voice.bus.DialogueFaces
import com.geeui.voice.bus.dialogueFace
import kotlin.test.Test
import kotlin.test.assertEquals

class DialogueFaceTest {
    @Test
    fun micCaptureShowsTheListeningFace() {
        assertEquals(LISTEN_FACE, dialogueFace("hear", false, IDLE_FACE))
        assertEquals(LISTEN_FACE, dialogueFace("hear", false, "h0006"))
    }

    @Test
    fun waitingForTheAnswerShowsTheThinkingFace() {
        assertEquals(THINK_FACE, dialogueFace("think", false, IDLE_FACE))
        assertEquals(THINK_FACE, dialogueFace("talk", false, IDLE_FACE))
    }

    @Test
    fun neutralSpeechShowsTheMouth() {
        assertEquals(SPEAK_FACE, dialogueFace("talk", true, IDLE_FACE))
        assertEquals(SPEAK_FACE, dialogueFace("talk", true, ""))
    }

    @Test
    fun backendFaceDoesNotStayWhileSpeaking() {
        // Neutral answer: the last showFace was the backend clip, the mouth must replace it.
        assertEquals(SPEAK_FACE, dialogueFace("talk", true, THINK_FACE))
        assertEquals(SPEAK_FACE, dialogueFace("talk", true, LISTEN_FACE))
        assertEquals(THINK_FACE, dialogueFace("talk", false, THINK_FACE))
    }

    @Test
    fun emotionPoseWinsWhileSpeakingWithTalkFaceEmotion() {
        DialogueFaces.speakDuringTalk = false
        try {
            assertEquals("h0006", dialogueFace("talk", true, "h0006"))
            assertEquals("h0006", dialogueFace("talk", false, "h0006"))
            assertEquals("h0119", dialogueFace("idle", false, "h0119"))
        } finally {
            DialogueFaces.reset()
        }
    }

    @Test
    fun mouthWhileSpeakingByDefault() {
        // The user wants to see the speaking clip: an emotion answer no longer hides it.
        assertEquals(SPEAK_FACE, dialogueFace("talk", true, "h0006"))
        assertEquals(THINK_FACE, dialogueFace("talk", false, "h0006"))
        assertEquals("h0119", dialogueFace("idle", false, "h0119"))
        assertEquals(true, DialogueFaces.parseTalkFace(null))
        assertEquals(false, DialogueFaces.parseTalkFace("emotion"))
    }

    @Test
    fun idleGoesBackToThePoseFace() {
        assertEquals(IDLE_FACE, dialogueFace("idle", false, IDLE_FACE))
    }

    @Test
    fun backAtRestNeverFlashesTheBackendClip() {
        // Seen on the robot: mode idle while the pose was still h0242 for ~0.45 s.
        assertEquals(IDLE_FACE, dialogueFace("idle", false, THINK_FACE))
        assertEquals(IDLE_FACE, dialogueFace("idle", false, LISTEN_FACE))
    }

    @Test
    fun stateClipsAreBundledAndSmall() {
        for (id in listOf(LISTEN_FACE, THINK_FACE, SPEAK_FACE, IDLE_FACE)) {
            val f = listOf("../app/src/main/assets/video/$id.mp4", "app/src/main/assets/video/$id.mp4")
                .map { java.io.File(it) }.firstOrNull { it.exists() }
            assertEquals(true, f != null, "$id absent des assets")
            // Idle h0059 keeps its AAC track (~300 Ko). Dialogue clips stay small.
            if (id != IDLE_FACE) {
                assertEquals(true, f!!.length() < 200_000, "$id trop gros: ${f.length()}")
            }
        }
    }

    @Test
    fun parseIdAcceptsPlainOrPrefixedCodes() {
        assertEquals("h0180", DialogueFaces.parseId("h0180", LISTEN_FACE))
        assertEquals("h0180", DialogueFaces.parseId("0180", LISTEN_FACE))
        assertEquals("h0181", DialogueFaces.parseId("H0181", THINK_FACE))
        assertEquals(LISTEN_FACE, DialogueFaces.parseId("nope", LISTEN_FACE))
        assertEquals(THINK_FACE, DialogueFaces.parseId("", THINK_FACE))
        assertEquals(THINK_FACE, DialogueFaces.parseId(null, THINK_FACE))
    }

    @Test
    fun dialogueFaceHonoursConfiguredIds() {
        DialogueFaces.listen = "h0098"
        DialogueFaces.think = "h0050"
        try {
            assertEquals("h0098", dialogueFace("hear", false, IDLE_FACE))
            assertEquals("h0050", dialogueFace("think", false, IDLE_FACE))
        } finally {
            DialogueFaces.reset()
        }
    }
}
