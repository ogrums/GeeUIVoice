package com.geeui.voice

import com.geeui.voice.bus.LtpApi
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
}
