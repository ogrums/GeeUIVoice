# GeeUIVoice

Kotlin service for the RUX robot. `com.geeui.lex` stays installed and keeps the wake word. Speech, answers and TTS go to a Lemonade server on the LAN. Nothing in this repo calls AWS, Azure or iFlytek.

## Docs

- [docs/AGENT.md](docs/AGENT.md) : nanobot comme cerveau du robot (mémoire, skills, soul, MCP)
- [docs/PERSONNALISATION.md](docs/PERSONNALISATION.md) : reconnaître qui parle, prendre l'initiative, souvenirs utiles
- [docs/ROADMAP.md](docs/ROADMAP.md) : la suite (humeur, vision, skills appris, maison, mode enfant/invité)

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
| STT | `POST /audio/transcriptions` | Whisper-Small |
| TTS | `POST /audio/speech` | kokoro, voice `ff_siwis` (FR) or `af_heart` (EN) |

`LemonadeClient` speaks that API. `VoiceSession` still does skill-first: « avance » is `AT+MOVEW,98,3,2` and never hits the LLM.

## Wake

openWakeWord has a Kotlin Android port ([openwakeword-android-kt](https://github.com/Re-MENTIA/openwakeword-android-kt), Apache-2.0, ONNX). There is no ready « hi rux » model. Official openWakeWord weights are non-commercial. Training a word is a later step.

Until that model exists, Lex / iFlytek VTN can still wake the robot. Opening GeeUIVoice from its icon starts the microphone and loops the idle face `h0059`. An emotion clip replaces it for the reply, then `h0059` comes back. Pass `--es mode debug` to keep the buttons and the status lines.

## Real-time loop

Lemonade does not stream the microphone. The robot does the endpointing.

```text
mic 16 kHz, 20 ms
  → EnergyVad (start 60 ms, hangover 500 ms)
  → on speech start: stop TTS (barge-in)
  → on speech end: WAV 16 kHz mono PCM16 → POST sidecar:13306/v1/audio/transcriptions
  → échec du sidecar : reprise directe sur Lemonade /audio/transcriptions
  → skill, or SSE /chat/completions split on . ! ?
  → POST /audio/speech per clause → speaker
```

The mic thread never waits on HTTP. STT, chat and playback run on a second thread, so a new word can cut the current answer.

`VoiceService` (module `app`, needs the Android SDK to build):

```text
adb shell am startservice -n com.geeui.voice/.VoiceService \
  -e host http://<pc>:13305/api/v1
```

### Vision à la demande

« Qu'est-ce que tu vois ? », « lis ça », « regarde ça », « c'est quoi ça ? » (liste dans `VisionIntent`) : l'app prend **une** photo avec la caméra du robot (Camera2, le SDK Letianpai n'expose pas la caméra) et l'ajoute au tour au format OpenAI (`image_url` en data URL). Le sidecar la fait décrire par un modèle vision (`VISION_MODEL`, section « Vision à la demande » de GeeUIVoiceSidecar) et nanobot reçoit « (RUX voit : …) ». Pas de flux continu, rien n'est gardé sur le robot.

- `host` doit être le sidecar (`http://<pc>:13306/v1`) : c'est lui qui transforme l'image en texte.
- Permission caméra : demandée à l'ouverture de l'app ; service lancé seul par adb : `adb shell pm grant com.geeui.voice android.permission.CAMERA`.
- `-e vision 0` : jamais de photo.
- Caméra occupée (reconnaissance faciale du fabricant) ou refusée : nanobot reçoit « (RUX a voulu regarder mais n'a pas pu : caméra indisponible) » ; la cause est dans la ligne de diagnostic (`caméra : …`).

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

Kokoro stays the default, on Lemonade. A short line that is not neutral goes to one FastAPI process. The weights are `FunAudioLLM/Fun-CosyVoice3-0.5B-2512`. If that snapshot has no `asset/zero_shot_prompt.wav`, `run.sh` fetches it from the CosyVoice git repo. The speech runtime is still that repo, cloned next to this script (`sidecar/CosyVoice`) and installed with `pip install -r requirements.txt` in a Python 3.10 venv. It has no `setup.py`: `run.sh` puts it on `PYTHONPATH`. Do not `pip install -e .`. The sidecar writes the WAV itself and does not import torchaudio. Without the runtime, `/health` stays up and `/v1/audio/speech` returns 503, and the robot falls back to Kokoro.

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

## Passerelle émotionnelle

`sidecar/emo_server.py` expose la passerelle OpenAI sur le port **13306**. La STT de l'application y passe pour récupérer `emotion` et `emotion_confidence` d'emotion2vec, avec le modèle `Whisper-Small`. En cas d'indisponibilité, les échanges continuent directement avec Lemonade, sans émotion audio.

Le LLM reçoit l'instruction de commencer sa réponse par une balise émotionnelle. `EmotionTagParser` accepte les balises divisées entre plusieurs morceaux SSE et les retire avant la synthèse. `EmotionFusion` pondère l'audio à 0,6 et le texte à 0,4, avec un seuil de 0,45 et un délai de 4 secondes. `VoiceSession` applique ensuite la `SdkMap` existante (visage, oreilles, lumière et geste) via `RobotBus`.

Lancer le service avec `python emo_server.py` (ONNX et ses dépendances sont configurés par variables d'environnement). Le serveur CosyVoice FastAPI reste le service lancé par `run.sh`; les deux services ne doivent pas être démarrés simultanément sur 13306 sans autre intégration. Sur nimbus, utiliser la passerelle pour la conversation, et configurer le port FastAPI CosyVoice séparément si nécessaire.

Tests : `python -m unittest sidecar/test_emo_server.py`, puis `python sidecar/tests/run_emotion_tests.py` pour la campagne simulée. Les échantillons RAVDESS et leur licence restent dans `tests/samples/ravdess`.
