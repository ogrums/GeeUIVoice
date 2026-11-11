package com.geeui.voice

import com.geeui.voice.bus.IDLE_FACE
import com.geeui.voice.bus.LISTEN_FACE
import com.geeui.voice.bus.SPEAK_FACE
import com.geeui.voice.bus.THINK_FACE
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
    fun emotionPoseWinsWhileSpeakingAndAfter() {
        assertEquals("h0006", dialogueFace("talk", true, "h0006"))
        assertEquals("h0119", dialogueFace("idle", false, "h0119"))
    }

    @Test
    fun idleGoesBackToThePoseFace() {
        assertEquals(IDLE_FACE, dialogueFace("idle", false, IDLE_FACE))
    }

    @Test
    fun stateClipsAreSmall() {
        for (id in listOf(LISTEN_FACE, THINK_FACE, SPEAK_FACE)) {
            val f = listOf("../app/src/main/assets/video/$id.mp4", "app/src/main/assets/video/$id.mp4")
                .map { java.io.File(it) }.firstOrNull { it.exists() } ?: continue
            assertEquals(true, f.length() < 200_000, "$id trop gros")
        }
    }
}
