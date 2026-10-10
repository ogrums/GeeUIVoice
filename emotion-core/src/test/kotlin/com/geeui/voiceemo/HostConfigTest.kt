package com.geeui.voiceemo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostConfigTest {
    @Test
    fun blank_uses_the_lan_defaults() {
        val cfg = HostConfig.from(emptyMap())
        assertEquals("http://nimbus:13306", cfg.sidecar)
        assertEquals("http://nimbus:13305/api/v1", cfg.lemonadeHost)
        assertEquals("kokoro-v1", cfg.kokoroModel)
        assertEquals("ff_siwis", cfg.kokoroVoiceFr)
        assertEquals("af_heart", cfg.kokoroVoiceEn)
        assertFalse(cfg.cosyEnabled)
        assertEquals("Fun-CosyVoice3-0.5B-2512", cfg.cosyVoiceModel)
    }

    @Test
    fun set_values_win() {
        val cfg = HostConfig.from(mapOf(
            "lemonade" to "http://192.168.1.20:13305/",
            "cosyvoice" to "http://192.168.1.20:13307",
            "chat_model" to "qwen",
        ))
        assertEquals("http://192.168.1.20:13305", cfg.lemonadeHost)
        assertEquals("qwen", cfg.chatModel)
        assertTrue(cfg.cosyEnabled)
    }
}
