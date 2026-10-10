package com.geeui.voice

import com.geeui.voice.bus.LtpApi
import com.geeui.voice.bus.faceCommand
import com.geeui.voice.bus.faceFile
import kotlin.test.Test
import kotlin.test.assertEquals

class LtpApiTest {
    @Test
    fun mcuIsNotTheFirstMethod() {
        assertEquals(9, LtpApi.code("setMcuCommand"))
        assertEquals(15, LtpApi.code("setExpression"))
        assertEquals(24, LtpApi.code("setTTS"))
        assertEquals(27, LtpApi.code("setSpeechCmd"))
    }

    @Test
    fun faceCommandNamesTheClip() {
        val raw = faceCommand("h0119")
        assertEquals(true, raw.startsWith("{"))
        assertEquals(true, raw.contains("\"face\":\"h0119\""))
    }

    @Test
    fun faceFileUsesTheSdcardGeeUIFacePlays() {
        val path = faceFile("h0119") { it == "/sdcard/assets/video/h0119.mp4" }
        assertEquals("/sdcard/assets/video/h0119.mp4", path)
        assertEquals(null, faceFile("h0119") { false })
    }
}
