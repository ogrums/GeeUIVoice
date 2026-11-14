package com.geeui.voice

import com.geeui.voice.bus.RecordingBus
import com.geeui.voice.engine.Chat
import com.geeui.voice.engine.EchoTts
import com.geeui.voice.session.HermesDesk
import com.geeui.voice.session.HermesHttp
import com.geeui.voice.session.HermesState
import com.geeui.voice.session.VoiceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HermesDeskTest {
    /** Fake sidecar: answers by path, records every call. */
    private class Sidecar {
        val calls = mutableListOf<String>()
        var intent = """{"intent": null, "prompt": null}"""
        var tasks = HermesHttp(200, """{"enabled": true, "unread": 0, "running": 0, "tasks": []}""")
        var create = HermesHttp(201, """{"id": "t1", "status": "running"}""")
        var speak = HermesHttp(200, """{"id": "t1", "status": "completed", "text": "Hermes a trouv\u00e9 trois fichiers."}""")

        fun get(path: String): HermesHttp {
            calls += "GET $path"
            return if (path == "/hermes/tasks") tasks else HermesHttp(404, null)
        }

        fun post(path: String, json: String): HermesHttp {
            calls += "POST $path $json"
            return when (path) {
                "/hermes/intent" -> HermesHttp(200, intent)
                "/hermes/tasks" -> create
                "/hermes/tasks/latest/speak" -> speak
                else -> HermesHttp(404, null)
            }
        }

        fun desk() = HermesDesk(::get, ::post)
    }

    private class Says(val reply: String) : Chat {
        var asked = 0
        override fun reply(userText: String): String {
            asked++
            return reply
        }
    }

    @Test
    fun ordinarySentencesNeverCallTheSidecar() {
        val side = Sidecar()
        assertNull(side.desk().handle("quelle heure est-il ?"))
        assertNull(side.desk().handle("donne-moi le résultat du match de rugby d'hier soir"))
        assertEquals(emptyList(), side.calls)
    }

    @Test
    fun createSendsThePromptAndConfirms() {
        val side = Sidecar()
        side.intent = """{"intent": "create", "prompt": "compter les fichiers \"demo\""}"""
        assertEquals(HermesDesk.CREATED, side.desk().handle("Confie à Hermès de compter les fichiers \"demo\""))
        assertTrue(side.calls[0].startsWith("POST /hermes/intent {\"text\":\"Confie à Hermès"))
        assertEquals("POST /hermes/tasks {\"prompt\":\"compter les fichiers \\\"demo\\\"\"}", side.calls[1])
    }

    @Test
    fun createWithoutPromptAsksWhat() {
        val side = Sidecar()
        side.intent = """{"intent": "create", "prompt": null}"""
        assertEquals(HermesDesk.ASK_WHAT, side.desk().handle("demande à Hermes"))
        assertEquals(1, side.calls.size)
    }

    @Test
    fun createFailures() {
        val side = Sidecar()
        side.intent = """{"intent": "create", "prompt": "x"}"""
        side.create = HermesHttp(502, """{"status": "failed"}""")
        assertEquals(HermesDesk.UNREACHABLE, side.desk().handle("confie à hermes x"))
        side.create = HermesHttp(503, """{"enabled": false}""")
        assertEquals(HermesDesk.DISABLED, side.desk().handle("confie à hermes x"))
    }

    @Test
    fun readSpeaksTheSidecarSummary() {
        val side = Sidecar()
        side.intent = """{"intent": "read", "prompt": null}"""
        assertEquals("Hermes a trouvé trois fichiers.", side.desk().handle("résultat Hermes"))
        // Explicit mention: no state check before speaking.
        assertEquals(listOf("POST /hermes/intent", "POST /hermes/tasks/latest/speak"), side.calls.map { it.split(" ").take(2).joinToString(" ") })
    }

    @Test
    fun bareReadOnlyWhenSomethingIsUnread() {
        val side = Sidecar()
        side.intent = """{"intent": "read", "prompt": null}"""
        assertNull(side.desk().handle("lis le résultat"), "nothing unread: let the normal turn answer")
        side.tasks = HermesHttp(200, """{"enabled": true, "unread": 1, "running": 0, "tasks": [{"id":"t1"}]}""")
        assertEquals("Hermes a trouvé trois fichiers.", side.desk().handle("lis le résultat"))
        side.speak = HermesHttp(404, """{"error": "x"}""")
        assertEquals(HermesDesk.NO_TASK, side.desk().handle("lis le résultat"))
    }

    @Test
    fun statusLines() {
        val side = Sidecar()
        side.intent = """{"intent": "status", "prompt": null}"""
        side.tasks = HermesHttp(200, """{"enabled": true, "unread": 0, "running": 2, "tasks": []}""")
        assertEquals("Hermes travaille encore sur 2 tâches.", side.desk().handle("où en est Hermes ?"))
        assertEquals("Hermes a un résultat prêt. Dis : lis le résultat.", HermesDesk.statusLine(HermesState(true, 1, 0)))
        assertEquals("Hermes a un résultat prêt et une tâche en cours.", HermesDesk.statusLine(HermesState(true, 1, 1)))
        side.tasks = HermesHttp(0, null)
        assertEquals(HermesDesk.UNREACHABLE, side.desk().handle("où en est Hermes ?"))
        side.tasks = HermesHttp(503, """{"enabled": false}""")
        assertEquals(HermesDesk.DISABLED, side.desk().handle("où en est Hermes ?"))
    }

    @Test
    fun sidecarDownFallsBackToTheNormalTurn() {
        val desk = HermesDesk({ HermesHttp(0, null) }, { _, _ -> HermesHttp(0, null) })
        assertNull(desk.handle("confie à Hermes de lister les projets"))
    }

    @Test
    fun sessionSpeaksHermesLineWithoutTheLlm() {
        val side = Sidecar()
        side.intent = """{"intent": "create", "prompt": "lister les projets"}"""
        val tts = EchoTts()
        val chat = Says("réponse du LLM")
        val session = VoiceSession(RecordingBus(), tts, chat, pause = {}, hermes = side.desk())
        assertEquals(HermesDesk.CREATED, session.onUserText("Confie à Hermes de lister les projets"))
        assertEquals(listOf("fr:${HermesDesk.CREATED}"), tts.spoken)
        assertEquals(0, chat.asked)
        session.onUserText("quelle heure est-il")
        assertEquals(1, chat.asked)
    }

    @Test
    fun guestsWithoutTheHermesKindAreRefused() {
        val side = Sidecar()
        side.intent = """{"intent": "create", "prompt": "x"}"""
        val tts = EchoTts()
        val chat = Says("ok")
        val session = VoiceSession(
            RecordingBus(), tts, chat, pause = {}, hermes = side.desk(),
            allowedActions = { setOf("light") },
        )
        session.onUserText("confie à Hermes de tout effacer")
        assertEquals(listOf("fr:${VoiceSession.REFUSED}"), tts.spoken)
        assertEquals(emptyList(), side.calls)
        // A short « résultat » phrase without Hermes goes to the LLM as usual.
        session.onUserText("lis le résultat")
        assertEquals(1, chat.asked)
    }
}
