package com.geeui.voice

/** Shared with the scanner view. Written from the mic thread, read on the UI thread. */
object VoiceHud {
    @Volatile var level: Float = 0f
    @Volatile var mode: String = "idle"
    @Volatile var line: String = ""
    @Volatile var heard: String = ""
    @Volatile var answer: String = ""
    @Volatile var diag: String = ""
    @Volatile var playing: Boolean = false
    /** Mic stays closed until this clock time, so the speaker tail is not transcribed. */
    @Volatile var hearAfter: Long = 0L
    @Volatile var mic: Boolean = false
    /** True while VoiceService has a loop (even if AudioRecord is not open yet). */
    @Volatile var running: Boolean = false
    /** Face id to play, h0006 and the others. Empty until a pose starts. */
    @Volatile var face: String = ""
    /** True between VoiceActivity onStart and onStop: our face is on screen. */
    @Volatile var visible: Boolean = false
    /** Rest clip playback: loop (default), once then hold the last frame, or replay every N s. */
    @Volatile var idleVideo: com.geeui.voice.bus.IdleVideo = com.geeui.voice.bus.IdleVideo.LOOP

    /** Every showFace of the current turn, with timestamps. Cleared at the start of a turn. */
    val faceEvents = java.util.concurrent.CopyOnWriteArrayList<com.geeui.voice.session.FaceEvent>()

    fun recordFace(id: String, atMs: Long = System.currentTimeMillis()) {
        face = id
        faceEvents += com.geeui.voice.session.FaceEvent(id, atMs)
    }

    fun clearFaceEvents() {
        faceEvents.clear()
    }

    fun faceDuringSpeech(): String = com.geeui.voice.session.faceDuringSpeech(faceEvents)
}
