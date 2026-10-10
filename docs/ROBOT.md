# Robot reference

The RUX desktop robot as this project drives it. Command strings and binder methods are in [BUS.md](BUS.md). This file is the machine: screen, body, faces, sounds, sensors, modes, and which process owns each part.

Sources: `GeeUIVoiceEmo/docs/ROBOTSDK.md`, `FACES.md`, `DEV-RULES.md`, `SdkPose.kt`, `RobotRemoteConsts`. The vendor pages are the Letianpai SDK note (p=5915) and the third-party app rules (p=7593). Android 11. A third-party APK is installed on the device. It is not baked into the ROM.

## Screen

| Rule | Value |
|---|---|
| Size | 480×480 px |
| Visible area | circle. Anything outside the circle is clipped |
| Units | `px` only. Do not use `dp` |
| Status bar | top 78 px: battery and Wi-Fi. Do not draw under it |
| Touch | keep controls inside the published tap zone |

GeeUIFace hides the status bar and the navigation bar when it plays a face video. A normal third-party screen should leave the 78 px strip.

## Body

Two servo rails. LetianpaiService calls them `powerControl` function 3 and function 5, status 1 on, status 0 off. RobotSDK folds both into `robotOpenMotor()` / `robotCloseMotor()`. Open the rails once, off the UI thread. Do not send `AT+MOVEW`.

| Part | What it can do | Limit |
|---|---|---|
| Feet | one gesture at a time, by number | SDK list is 1–80. GeeUIVoice walks with **98**, which is not in that list |
| Ears | both ears, one command | angle 0–90°. Past 90 the firmware clamps. cmd 1 and 3 are both documented as "both ears left"; the demo uses 3 |
| Antenna light | one solid color, or off | `RED GREEN BLUE ORANGE WHITE YELLOW PURPLE CYAN BLACK`. No brightness, no blink pattern |
| Screen face | a named clip `hNNNN` | start once, then change. `robotStopExpression` leaves face mode |
| Speaker clip | a built-in `aNNNN` | not our TTS |
| Speaker TTS | vendor voice | `robotPlayTTs` and `setTTS`. Lemonade must not use either |

Ear command arguments are `AntennaMessage.set(cmd, step, speedMs, angle)`.

| Arg | Meaning |
|---|---|
| cmd | 1 both ears left, 2 both ears right, 3 both ears left (same words as 1 on the vendor page) |
| step | repeats |
| speed | gap between repeats, milliseconds |
| angle | amplitude, 0–90 |

## Gestures

`ActionMessage.set(number, speed, stepNum)` then `robotActionCommand`. Defaults below are the published ones. `step=n` means the caller chooses the count. "fixed" means the firmware ignores the requested speed.

| n | Gesture | Default |
|---|---|---|
| 1 | walk forward | step=n, speed=3 |
| 2 | walk back | step=n, speed=3 |
| 3 | turn left | step=n, speed=3 |
| 4 | turn right | step=n, speed=3 |
| 5 | crab left | step=n, speed=3 |
| 6 | crab right | step=n, speed=3 |
| 7 | shake left leg | step=1, delay=2 |
| 8 | shake right leg | step=1, delay=2 |
| 9 | shake left foot | step=2, delay=3 |
| 10 | shake right foot | step=2, delay=3 |
| 11 | left foot up | step=1, delay=1 |
| 12 | right foot up | step=1, delay=1 |
| 13 | lean left | step=1, delay=6 |
| 14 | lean right | step=1, delay=6 |
| 15 | stomp left | step=1, delay=3 |
| 16 | stomp right | step=1, delay=3 |
| 17 | body up and down | step=1, delay=1 |
| 18 | body left and right | step=1, delay=1 |
| 19 | head left and right | step=2, delay=1 |
| 20 | rest | step=1, delay=1 |
| 21 | spin left in place, about 20° | step=1, delay=3 |
| 22 | spin right in place, about 20° | step=1, delay=3 |
| 23 | both feet shake | step=3, delay=1 |
| 24 | small shake | step=1, delay=3 |
| 25 | small turn left, about 5° | step=1, delay=3 |
| 26 | small turn right, about 5° | step=1, delay=3 |
| 27 | sway | step=1, speed=3 |
| 28 | nod | step=1, speed=3 |
| 29–33 | random | step=1, speed=3 (33 at least 3) |
| 34 | small foot spin | at least 3, speed=3 |
| 35–42 | random or small foot shake | step=1, speed=3 |
| 43 | small nod | step=1, speed=3 |
| 44 | dodge left | step=1, speed=3 |
| 45 | dodge right | step=1, speed=3 |
| 46 | small dodge left | step=1, speed=3 |
| 47 | small dodge right | step=1, speed=3 |
| 48 | both feet out, fast | step=1, fixed speed |
| 49 | double shake, fast | step=1, fixed speed |
| 50 | twist forward and back | step=1, speed=3 |
| 51 | small shake, left foot | step=1, speed=3 |
| 52 | small shake, right foot | step=1, speed=3 |
| 53 | left foot outward | step=1, speed=3 |
| 54 | right foot outward | step=1, speed=3 |
| 55 | turn left, about −3° | step=1, speed=3 |
| 56 | turn right, about −3° | step=1, speed=3 |
| 57 | small spin, right foot | step=1, speed=3 |
| 58 | large dodge right | step=1, fixed speed |
| 59 | rub front, middle, back | step=1, speed=3 |
| 60 | rub front and back | step=1, speed=3 |
| 61 | leg up | step=1, fixed speed |
| 62 | leg up and shake | step=1, fixed speed |
| 63 | forward, variant 2 | step=1, speed=3 |
| 64 | back, variant 2 | step=1, speed=3 |
| 65 | left foot spin, fast | at least 3, fixed speed |
| 66 | right foot spin, fast | at least 3, fixed speed |
| 67 | fast shake | step=1, fixed speed |
| 68 | fast outward shake | step=1, fixed speed |
| 69 | step onto pad 2 | step=1, speed=1 |
| 70 | step onto pad 3 | step=1, speed=1 |
| 71–75 | spin on pads 1 through 5 | step=1, speed=2 |
| 76 | nod yes | step=1, speed=3 |
| 77 | yeah | step=1, speed=3 |
| 78 | fast twist | step=1, speed=1 |
| 79 | roll | step=1, speed=1 |
| 80 | dance twist | step=2, speed=6 |

GeeUIVoice "avance" sends number 98, step 3, speed 2, on the AIDL bus, not through this table. "recule" sends 64. 64 is the SDK neighbour of 63. It is not confirmed on this device. Do not pass 98 to `robotActionCommand`.

## Emotion pose

One pose is a face, an ear move, a light, an optional gesture, and an optional clip. Neutral does none of the last three. From `SdkMap`:

| Emotion | Face | Ears | Light | Action | Sound |
|---|---|---|---|---|---|
| happy | `h0006` 大笑 | cmd 3, 2 steps, 250 ms, 60° | `YELLOW` | 77 yeah | `a0032` |
| sad | `h0119` 哭泣 | cmd 1, 1 step, 500 ms, 40° | `BLUE` | 20 rest | `a0086` |
| angry | `h0001` 愤怒 | cmd 2, 2 steps, 200 ms, 70° | `RED` | 15 stomp | `a0020` |
| fear | `h0133` 害怕 | cmd 1, 1 step, 400 ms, 30° | `CYAN` | 44 dodge | `a0037` |
| surprise | `h0046` 惊讶 | cmd 3, 1 step, 150 ms, 90° | `WHITE` | 76 nod | `a0095` |
| neutral | `h0059` 常规环 | cmd 1, angle 0 | off | none | none |

Mood half-life is 45 s. A tie between the audio vote and the text vote (gap under 0.15) stays neutral. A skill turn, such as "avance", must not also play this pose.

Other face ids that look like emotions but are not used:

| id | Name | Why it is not in the table |
|---|---|---|
| `h0134` | 听歌星光 | music screen, not fear |
| `h0189` | 摸头 | head pat |
| `h0190` | 拍头 | head tap |
| `h0191` | 长按头 | long press |
| `h0079`–`h0114`, `h0228`–`h0233` | GPT / Spark / Wenxin skins | vendor chat skins |
| `h0250`–`h0284` | animal cries | not an affect |
| `h0211` | 伤心2 | a second sad, not the one we send |
| `h0018` | 狂怒 | a stronger angry |

The public face sheet stops at `h0302`. Ids `h0290`–`h0302` are empty. The full named subset we care about is in `GeeUIVoiceEmo/docs/FACES.md`.

GeeUIFace plays `assets/video/<id>.mp4` in a loop. The automatic robot mode picks a script at random, and every 5th cycle it runs face recognition instead of a video. That loop is GeeUIFace, not GeeUIVoice.

## Sounds

Clips GeeUIVoiceEmo may play:

| id | Meaning |
|---|---|
| `a0020` | angry |
| `a0032` | laugh |
| `a0037` | fear |
| `a0039` | pleasant surprise |
| `a0070` | panic |
| `a0086` | sad |
| `a0095` | surprise |
| `a0107` | embarrassed |
| `a0133` | disappointed |

The firmware also accepts symbolic names (`happy`, `sad`, `angry`, `cry`, `wake`, `sleep`, `charge`, `lowPower`, and others listed in [BUS.md](BUS.md)). Those names are not proven to be the same files as `aNNNN`. Do not mix them in one call.

`speechDance` and `speechMusic`, argument `from_third`, start a long vendor routine. A head tap stops it. Our TTS does not go through that path.

## Sensors

Open with `robotOpenSensor`, then `robotRegisterSensorCallback`. Close with `robotCloseSensor` and `robotUnregisterSensor`.

| Callback | Vendor page says | Caution |
|---|---|---|
| `onTapResponse` | one tap on the head | |
| `onDoubleTapResponse` | double tap | |
| `onLongPressResponse` | long press | |
| `onFallBackend` | front cliff | page says front for Backend |
| `onFallForward` | back cliff | page says back for Forward |
| `onFallRight` | left cliff | left and right are swapped on the page |
| `onFallLeft` | right cliff | |
| `onTof` | obstacle | |

Do not bind a behaviour to left versus right until someone checks it on the robot. The SDK can show or hide a charging icon. It does not return a charging boolean. GeeUIVoice's skill router still assumes "not charging".

## Modes

`changeMode` values from `RobotRemoteConsts`:

| Value | Meaning | Voice loop |
|---|---|---|
| `robot` | robot mode | GeeUIFace auto-gestures start. GeeUIVoice is separate and only runs if Lex released the mic |
| `sleep` | sleep | GeeUIFace drops the servo rails. Voice does not start |
| `static` | still | no auto loop |
| `demo` | demo | no auto loop |
| `show` | show | |
| `auto` | auto | |
| `transform` | transform | |
| `reset` | restore | |

Display pages, via `controlDisplayMode`: `time`, `weather`, `countdown`, `fans`, `schedule`, `empty`, `darkScreen`, `exitDarkScreen`.

Auto-move values: `follow`, `exitFollow`, `random`.

## Processes

| Package | Role |
|---|---|
| `com.renhejia.robot.letianpaiservice` | the binder every app uses |
| `com.letianpai.robot` | robot host named in the SDK manifest queries |
| `com.geeui.face` | face videos and the idle gesture loop |
| `com.geeui.lex` | wake word. Keeps the microphone until we have our own model |
| `com.ltp.ident` | face recognition. Threshold 0.32, 20 tries, 45 s |
| `com.geeui.voice` | this app. Mic loop, Lemonade, skills. Surviving package after the fusion |
| `com.geeui.voiceemo` | emotion APK. Retired once it is a module of `com.geeui.voice` |
| `com.rhj.aduioandvideo` | video call |
| `com.geeui.videoplayer` | video player |

Lex and GeeUIVoice cannot hold the microphone together. Stop Lex's recorder before starting `VoiceService`, or `AudioRecord` fails.

## Voice path on this robot

```text
Lex wakes
  → GeeUIVoice mic, 16 kHz, 20 ms frames
  → energy VAD
  → WAV to Lemonade Whisper
  → exact skill, or Lemonade chat
  → Kokoro, or CosyVoice if the sidecar is healthy and the line is short and not neutral
  → speaker
```

Lemonade default base is `http://<pc>:13305/api/v1`. Emotion sidecar default is `http://nimbus:13306`. The PC is on the LAN. The robot does not run the models.

VAD defaults: RMS 800, 80 ms to start, 600 ms hangover, drop under 280 ms, cut at 8000 ms.

Skills that hit the body today:

| Phrase | Effect |
|---|---|
| avance / marche / walk forward | motion 98, step 3, speed 2, unless we later treat charging as true |
| recule / walk back | motion 64, step 3, speed 2 |
| plus fort / volume up | volume `+` |
| moins fort / volume down | volume `-` |
| tais-toi / mute | volume `0` |
| photo / take a photo | `rhj.controller.takephoto` |
| rentre / go home | open the app named `自动回充` |

Match is the whole string, lowercased. "avance un peu" does not match.

## What this robot file does not decide

The binder map is [BUS.md](BUS.md). The move from two APKs to one is [FUSION.md](FUSION.md). Wake-word training, music, alarms, and weather are not implemented here.
