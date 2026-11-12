package com.geeui.voice

import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.session.VoiceSession
import kotlin.test.Test
import kotlin.test.assertEquals

class ForgetTagTest {
    @Test
    fun forgetVoiceTagIsNeverSpoken() {
        val tts = EchoTts()
        val chat = object : Chat {
            override fun reply(userText: String) = "[neutral] C'est fait, j'ai tout oublié. [oubli:voix]"
        }
        VoiceSession(RecordingBus(), tts, chat, pause = {}).onUserText("oui")
        assertEquals(listOf("fr:C'est fait, j'ai tout oublié."), tts.spoken)
    }
}
