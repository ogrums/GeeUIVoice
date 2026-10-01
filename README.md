# GeeUIVoice

Kotlin service for the RUX robot. `com.geeui.lex` stays installed and keeps the wake word. Speech, answers and TTS go to a Lemonade server on the LAN. Nothing in this repo calls AWS, Azure or iFlytek.

## Decisions

| Choice | Taken |
|---|---|
| Repo | `ogrums/GeeUIVoice`, package `com.geeui.voice` |
| Lex | stays. It owns the mic and « hi rux » until we have our own wake model |
| STT / TTS / chat | HTTP, OpenAI shape, Lemonade on port **13305** |
| Wake | not in this build. See below |

## Lemonade

Server on a PC of the LAN, not on the robot. Base URL:

```text
http://<pc>:13305/api/v1
```

| Call | Path | Model |
|---|---|---|
| Chat | `POST /chat/completions` | llama (GGUF) |
| STT | `POST /audio/transcriptions` | whisper |
| TTS | `POST /audio/speech` | kokoro, voice `ff_siwis` (FR) or `af_heart` (EN) |

`LemonadeClient` speaks that API. `VoiceSession` still does skill-first: « avance » is `AT+MOVEW,98,3,2` and never hits the LLM.

## Wake

openWakeWord has a Kotlin Android port ([openwakeword-android-kt](https://github.com/Re-MENTIA/openwakeword-android-kt), Apache-2.0, ONNX). There is no ready « hi rux » model. Official openWakeWord weights are non-commercial. Training a word is a later step.

Until that model exists, option A stands: Lex / iFlytek VTN wakes the robot. GeeUIVoice does not open the microphone.

## Layout

`voice-core` is plain Kotlin (JDK 17). The Android service that binds `ILetianpaiService` comes after this module. It will play the WAV bytes from `LemonadeTts` and send `controlMotion` for a skill.

```text
./gradlew :voice-core:test
```
