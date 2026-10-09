# Migration plan: GeeUIVoiceFusion

One APK, `com.geeui.voice`. GeeUIVoice keeps the microphone, the VAD and Lemonade. GeeUIVoiceEmo becomes a Gradle module inside that APK. GeeUIAIAudioService is not merged. Its missing skills are a later backlog.

Target name in speech and in this document: GeeUIVoiceFusion. The application id stays `com.geeui.voice` so the existing `adb` start line still works. Do not publish a second package.

## What moves, what stays out

| Source | Fate |
|---|---|
| `GeeUIVoice/voice-core` | stays |
| `GeeUIVoice/app` (`VoiceService`, `AidlBus`, HUD) | stays, grows the emotion call |
| `GeeUIVoiceEmo/emotion-core` | copied in as `:emotion-core` |
| `GeeUIVoiceEmo/app` (`EmotionBus`, `SdkMap`) | copied into `:app`, then the Emo APK is retired |
| `GeeUIVoiceEmo` sidecar `emo_server.py` | stays a process on the LAN PC, not inside the APK |
| `GeeUIAIAudioService` | not copied. No iFlytek, no DUI, no FMOD |

## Phase 0 — freeze the contract

Before moving code, lock these decisions.

1. One foreground service: `VoiceService`. `EmotionService` is deleted after the move. No second notification, no second binder.
2. Body split. RobotSDK 2.5 (`EmotionBus`) owns face, ears, antenna light, emotion gesture and built-in sound. AIDL (`AidlBus` / `ILetianpaiService`) owns volume, photo, open-app, and walk (`controlMotion`). Both already call a motor-on command. Only `EmotionBus.robotOpenMotor()` may open the rail. `AidlBus` must stop sending `powerControl`.
3. Skill wins the turn. If `SkillLexicon` matches, do not apply an emotion pose on that turn. A walk and a happy stomp must not run together.
4. Neutral does not move and does not play a sound. That rule already lives in `SdkMap`. Keep it.
5. Lemonade stays the only chat, STT and default TTS host. CosyVoice is chosen only by `TtsRoute`.

## Phase 1 — one Gradle tree

In `GeeUIVoice/settings.gradle.kts`:

```kotlin
rootProject.name = "GeeUIVoice"
include(":voice-core")
include(":emotion-core")
include(":app")
```

Copy `GeeUIVoiceEmo/emotion-core` unchanged. It is plain Kotlin (JDK 17) and already tested with `./gradlew :emotion-core:test`. Copy the wrapper only if the Voice repo does not already have one.

`app/build.gradle.kts` gains:

```kotlin
implementation(project(":voice-core"))
implementation(project(":emotion-core"))
```

Put `RobotSdk-release.2.5.aar` in `app/libs/`. It is not committed. The Emo README already says this.

Packages stay `com.geeui.voice` and `com.geeui.voiceemo` for this phase. A rename is optional and not required for one APK.

Exit: `:voice-core:test` and `:emotion-core:test` pass. `:app:assembleDebug` is still done on a machine with SDK 30.

## Phase 2 — one service

Delete the Emo manifest entries once the classes live in `:app`. The fused manifest keeps Voice's permissions (`RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`) and adds Emo's `<queries>` for `com.renhejia.robot.letianpaiservice` and `com.letianpai.robot`.

`VoiceService.onStartCommand` reads the extras it already reads (`host`, `vad_*`) plus the Emo extras (`sidecar`, `lemonade`, `cosyvoice`, `voice_fr`, `voice_en`). Empty extras keep today's defaults:

| Extra | Default |
|---|---|
| `host` | required today; document `http://<pc>:13305/api/v1` |
| `sidecar` | `http://nimbus:13306` |
| `cosyvoice` | empty, CosyVoice stays off |

Replace the Emo poll loop with a call from the voice turn. Do not poll `GET /mood` on a timer in the fused app. The mood is computed on the robot from the transcript. The sidecar remains the place that scores audio and that serves CosyVoice.

Start line after the fusion:

```text
adb shell am startservice -n com.geeui.voice/.VoiceService \
  -e host http://<pc>:13305/api/v1 \
  -e sidecar http://<pc>:13306
```

`com.geeui.voiceemo` is uninstalled once this answers.

## Phase 3 — turn pipeline

Today `LiveTurn` does: VAD end, WAV, Whisper, `VoiceSession.onUserText`, one TTS clip.

Change `VoiceSession.onUserText` to this order:

```text
text
  → SkillLexicon.match
       hit  → SkillRouter.handle, return        (no emotion pose)
       miss → text affect from the transcript
            → optional audio affect from the sidecar
            → Arbiter.merge
            → Mood.observe
            → EmotionBus.apply(mood.emotion)     (skip when NEUTRAL)
            → Chat.stream
            → SentenceSplitter
            → TtsRoute.choose per clause
            → Kokoro or CosyVoice
```

`SentenceSplitter` is already in `voice-core` (`Sentences.kt`) and is unused. `VoiceSession` currently buffers the whole stream and speaks once. Wire the splitter so the first clause can play before the model finishes. `TtsRoute` runs per clause, not per full answer. A clause longer than 180 characters, or a neutral mood, stays on Kokoro.

Barge-in stays in `LiveTurn`: speech start calls `tts.stop()` and drops frames while the speaker is on, plus the echo tail. An emotion pose must not restart TTS.

Text affect for this phase is a small lexicon (happy, sad, angry, fear, surprise words in FR and EN), confidence below the sidecar when both exist. Do not call a second LLM to label emotion.

## Phase 4 — body ownership

| Action | Owner after fusion |
|---|---|
| Face, ears, antenna light, emotion gesture, built-in sound | `EmotionBus` / RobotSDK |
| Walk (`actin` 98 and 64) | `AidlBus.controlMotion` |
| Volume, mute, photo, open app, reboot, shutdown | `AidlBus.speechCmd` |
| `AidlBus.showFace` | unused by the turn. Skills that pass a face id still may call it, but the emotion pose must not follow in the same turn |

`AidlBus` today sends `powerControl` before the first walk. Remove that. `EmotionBus` already calls `robotOpenMotor()` once.

Do not call `robotPlayTTs`. TTS bytes come from Lemonade or CosyVoice and play on the voice thread.

## Phase 5 — cutover

1. Build `com.geeui.voice` with both modules.
2. Install it. Uninstall `com.geeui.voiceemo`.
3. Stop Lex's recorder before starting the loop, or `AudioRecord` fails. Lex still owns the wake word. This APK does not.
4. Checks on device:
   - « avance » walks and the face stays put.
   - A non-skill sentence moves the face only when the mood is not neutral.
   - Neutral after the 45 s half-life returns to `h0059` with no sound and no gesture.
   - Sidecar down: Kokoro still speaks, body stays at the last pose, the service does not crash.
   - CosyVoice unset: every clause uses Kokoro.
5. Archive `GeeUIVoiceEmo`. Leave the repo read-only with a README line pointing at `docs/FUSION.md` in GeeUIVoice.

## Explicitly later

Port these from GeeUIAIAudioService as new code in `voice-core`, not as a copy of that tree:

| Gap | Notes |
|---|---|
| Phrase match is exact | « avance un peu » misses. Match tokens, not the whole string |
| Router ids with no phrase | `volumemax`, `reboot`, `shutdown`, `open`, `face` |
| Charging | `SkillRouter` hook exists and always returns false |
| Music, alarms, weather | new skills, no DUI |
| Wake word | stays on Lex until an openWakeWord « hi rux » model exists |
| Multi-turn memory | `VoiceSession` has no history |

Do not bring across: iFlytek AIUI, DUI DDS, FMOD, Room upload, the Spark / OpenAI `voiceType` switch, or the vendor JARs.

## Test gate

| Module | Command | Must stay green |
|---|---|---|
| `voice-core` | `./gradlew :voice-core:test` | skills, VAD, one live turn |
| `emotion-core` | `./gradlew :emotion-core:test` | arbiter tie, mood decay, TTS route |
| new | one test that a skill hit does not call `EmotionBus.apply` | add with the pipeline |
| new | one test that a neutral mood does not select CosyVoice | `TtsRoute` already covers the rule |

No Android SDK is required for those tests. `assembleDebug` stays a device-side check.
