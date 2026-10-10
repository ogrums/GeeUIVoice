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

## Real-time loop

Lemonade does not stream the microphone. The robot does the endpointing.

```text
mic 16 kHz, 20 ms
  → EnergyVad (start 60 ms, hangover 500 ms)
  → on speech start: stop TTS (barge-in)
  → on speech end: WAV → POST /audio/transcriptions
  → skill, or SSE /chat/completions split on . ! ?
  → POST /audio/speech per clause → speaker
```

The mic thread never waits on HTTP. STT, chat and playback run on a second thread, so a new word can cut the current answer.

`VoiceService` (module `app`, needs the Android SDK to build):

```text
adb shell am startservice -n com.geeui.voice/.VoiceService \
  -e host http://<pc>:13305/api/v1
```

Lex and this service cannot share the mic. Stop Lex's recorder before starting the loop, or the `AudioRecord` fails.

## VAD (desk)

Defaults in `VadConfig`, overridable at start:

| extra | default | meaning |
|---|---|---|
| `vad_threshold` | 800 | RMS. Raise if the fan opens turns alone |
| `vad_start_ms` | 80 | loud time before a turn starts |
| `vad_hangover_ms` | 600 | quiet time before the turn is sent |
| `vad_min_ms` | 280 | shorter than this is dropped |
| `vad_max_ms` | 8000 | force-cut |

```text
adb shell am startservice -n com.geeui.voice/.VoiceService \
  -e host http://<pc>:13305/api/v1 \
  -e vad_threshold 800 -e vad_hangover_ms 600
```

`voice-core` tests: 9 OK (skills, VAD, one live turn). The APK is not built here: no Android SDK in this environment. `app/` is the service, the foreground mic notification, and the AIDL bus. Build it on a machine with SDK 30.

## CosyVoice3

Kokoro stays the default, on Lemonade. A short line that is not neutral goes to one FastAPI process. The weights are `FunAudioLLM/Fun-CosyVoice3-0.5B-2512`. If that snapshot has no `asset/zero_shot_prompt.wav`, `run.sh` fetches it from the CosyVoice git repo. The speech runtime is still that repo (`pip install -e .`). Without it, `/health` stays up and `/v1/audio/speech` returns 503, and the robot falls back to Kokoro.

```text
cd sidecar
./run.sh
```

An empty `sidecar` extra means `http://nimbus:13306`. Pass another URL only to override it.

```text
adb shell am startservice -n com.geeui.voice/.VoiceService \
  -e host http://nimbus:13305/api/v1
```

`POST /v1/audio/speech` takes `input`, `voice` (`happy`, `sad`, `angry`, `fear`, `surprise`) and returns WAV. `POST /route` is the same rule as `TtsRoute`.

## Layout

`voice-core` is plain Kotlin (JDK 17). `emotion-core` decides the pose and whether the line is CosyVoice or Kokoro. `app/` is the Android service.

```text
./gradlew :voice-core:test
```
