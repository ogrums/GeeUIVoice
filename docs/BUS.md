# Bus reference

Two buses. They are not the same API.

| Bus | Type | Package |
|---|---|---|
| LetianpaiService | AIDL binder | `com.renhejia.robot.letianpaiservice.ILetianpaiService` |
| RobotSDK 2.5 | Java facade over that service | `com.leitianpai.robotsdk.RobotService` |

The full AIDL is `GeeUIComponents/CommChannel/src/main/aidl/com/renhejia/robot/letianpaiservice/ILetianpaiService.aidl`. GeeUIVoice ships a four-method subset of it. RobotSDK method names come from `DemoForRobotSDK` and `GeeUIVoiceEmo/docs/ROBOTSDK.md`. The 2.5 AAR was not unpacked again for this file: the vendor CDN certificate is expired.

A command string is the real function. `setMcuCommand("controlMotion", json)` and `setMcuCommand("powerControl", json)` are one Java method and two different actions.

Every `set*` below has a `register*` and an `unregister*` callback with the same prefix. Those pairs are not repeated.

## 1. AIDL methods

| Method | Payload | RobotSDK | Fusion |
|---|---|---|---|
| `getRobotStatus()` | returns `int` | none | keep. SDK cannot read the mode |
| `setRobotStatus(int)` | mode integer | none | launcher only |
| `setCommand(LtpCommand)` | parcelable command | none | unused by Voice |
| `setLongConnectCommand(command, data)` | cloud push | `sendLongCommand(name, data)` is a different, local call | not the same thing. See section 6 |
| `setMcuCommand(command, data)` | body. Section 2 | motor, action, ears, light | split. Section 2 |
| `setAudioEffect(command, data)` | clip id | `robotControlSound(id)` | one owner. Section 4 |
| `setExpression(command, data)` | face id | `robotStartExpression` / `robotChangeExpression` | SDK owns the face in the fusion |
| `setAppCmd(command, data)` | process and mode. Section 5 | none | Lex and the launcher still need it |
| `setRobotStatusCmd(command, data)` | status channel | `robotControlStatusBar` is not this | no proven match |
| `setTTS(command, data)` | `"speakText"` + text | `robotPlayTTs(text)` | do not call either for Lemonade |
| `setSpeechCmd(command, data)` | skills. Section 3 | none | volume, photo, apps stay here |
| `setSensorResponse(command, data)` | cliff and precipice | `robotOpenSensor` plus callbacks | labels disagree. Section 7 |
| `setMiCmd(command, data)` | Mijia | none | out of Voice |
| `setIdentifyCmd(command, data)` | face ident | none | out of Voice |
| `setBleCmd(command, data, needResponse)` | BLE | none | out of Voice |
| `setBleResponse(command, data)` | BLE answer | none | out of Voice |

GeeUIVoice's copy of the AIDL only declares `setMcuCommand`, `setSpeechCmd`, `setTTS`, `setExpression`.

## 2. MCU commands (`setMcuCommand`)

Source: `GeeUIBase/.../MCUCommandConsts.java`. Strings that are commented out in that file are listed in section 5, because other apps still send them on `setAppCmd` or `setLongConnectCommand`.

| Command | Data | RobotSDK | Notes |
|---|---|---|---|
| `controlMotion` | `{"motion","number","speed","desc","id","stepNum"}` | `robotActionCommand(ActionMessage.set(number, speed, stepNum))` | Numbering differs. See below |
| `controlAntennaMotion` | ear payload | `robotAntennaMotion(AntennaMessage.set(cmd, step, speedMs, angle))` | SDK cmd is 1, 2 or 3. AIDL also has the strings below |
| `controlAntennaLight` | `"on"` or `"off"`, plus a color id in older JSON | `robotAntennaLight` / `robotCloseAntennaLight` | SDK colors: `RED GREEN BLUE ORANGE WHITE YELLOW PURPLE CYAN BLACK`. No pattern, no brightness |
| `powerControl` | `{"function":3 or 5,"status":0 or 1}` | `robotOpenMotor` / `robotCloseMotor` | 3 and 5 are two rails. SDK hides the split. Call once |
| `resetMcu` | none documented | none | AIDL only |
| `controllGyroscope` | none documented | none | spelling is missing an `l` |
| `start_gyroscope` | none | none | AIDL only |
| `stop_gyroscope` | none | none | AIDL only |
| `trtc` / `exitTrtc` | video call | none | AIDL only |
| `trtcMonitor` / `exitTrtcMonitor` | monitor call | none | AIDL only |
| `trtcTransform` / `exitTrtcTransform` | transfer | none | AIDL only |
| `open_mcu` / `close_mcu` | MCU power | closest to open/close motor, not proven equal | do not alias them |
| `enter_factory` / `exit_factory` | factory mode | none | AIDL only |
| `updateAwakeConfig` | wake-word config | none | AIDL only |

Antenna motion values on the AIDL side, not on the SDK:

| Value | Meaning in source |
|---|---|
| `turn` | one swing |
| `mturn` | repeated swing |
| `sturn` | short swing |
| `setStraight` | ears straight |
| `diy` | custom |

### Walk numbers

SDK actions published in `GeeUIVoiceEmo/docs/ROBOTSDK.md` are **1 through 80**. Selected ones:

| Number | SDK meaning | AIDL use |
|---|---|---|
| 1 | walk forward | not what Voice sends |
| 2 | walk back | |
| 15 | left stomp | Emo angry |
| 20 | rest | Emo sad |
| 44 | dodge left | Emo fear |
| 63 | forward, variant 2 | demo walk. Not used by GeeUIVoice |
| 64 | back, variant 2 | Voice "recule". Not confirmed on this robot |
| 76 | nod | Emo surprise |
| 77 | yeah | Emo happy |
| 98 | not in the SDK list | GeeUIVoice "avance". Do not pass 98 to `robotActionCommand` |

## 3. Speech commands (`setSpeechCmd`)

Source: `GeeUIAIAudioService/.../Const.java` and `SpeechConst.java`. RobotSDK has no method in this column. GeeUIVoice implements only the rows marked "wired".

### Volume and system

| Command | Data | Wired |
|---|---|---|
| `DUI.MediaController.SetVolume` | `+`, `-`, `0`, `100`, or a number. `SpeechConst` also has `max`, `min`, `%` | `+` and `-` only. `0` is wired as mute but no phrase reaches it |
| `DUI.System.Shutdown` | `"1"` | router only |
| `DUI.System.Reboot` | `"1"` | router only |
| `DUI.System.Connectivity.OpenBluetooth` | | no |
| `DUI.System.Connectivity.CloseBluetooth` | | no |
| `DUI.System.OpenSettings` / `CloseSettings` | | no |
| `DUI.System.Sounds.OpenMode` / `CloseMode` | mute mode | no |
| `DUI.System.GoBack` / `GoHome` | | no. "go home" is mapped to open-app, not `GoHome` |
| `DUI.System.UserMode.OpenMode` | | no |

### Media

All unwired. Player lives in GeeUIAIAudioService, not in Voice.

`DUI.MediaController.Play`, `Pause`, `Stop`, `Replay`, `Prev`, `Next`, `Switch`, `SwitchPlayMode`, `SetPlayMode`, `Progress`, `AddCollectionList`, `RemoveCollectionList`, `PlayCollectionList`, `OpenCollectionList`, `CloseCollectionList`.

### Robot skills

| Command | Data | Meaning | Wired |
|---|---|---|---|
| `rhj.controller.takephoto` | `"1"` | photo | yes |
| `rhj.controller.openApp` | app name, or `"自动回充"` for go-home | open an app | yes |
| `rhj.controller.closeApp` | app name | close an app | no |
| `rhj.controller.open` / `rhj.controller.close` | older open/close names in `SpeechConst` | same idea, different string | no. Do not mix with `openApp` |
| `rhj.controller.navigation` | direction and number | walk | no. Voice uses `controlMotion` |
| `rhj.controller.turn` | direction and number | turn | no |
| `rhj.controller.motion` | gesture id | motion library | no |
| `rhj.controller.show` | face id | show a face | no. Voice uses `setExpression` |
| `com.controller.earmotion` | ear payload | ears from a skill | no |
| `com.controller.earlightcolor` | color | light on | no |
| `com.controller.earlightcolor.off` | | light off | no |
| `com.controller.dance` | | dance | no |
| `rhj.controller.chatgpt.happy` / `.sad` | | one-shot emotion | no. Replaced by the Emo pose table |
| `rhj.controller.ai.enter` / `.exit` | | AI app | no |
| `rhj.controller.handscontroller` / `cancelhands` | | hand control | no |
| `rhj.controller.fingerguess` / `fingerguessclose` | | rock-paper-scissors | no |
| `rhj.controller.body.enter` / `.exit` | | body follow | no |
| `rhj.controller.searchPeople` | `"1"` or `"0"` | face search | no |
| `rhj.controller.remind` | | reminder | no |
| `rhj.motion.thinking` / `rhj.motion.who` | | thinking pose, "who" pose | no |
| `rhj.controller.videocall` / `urgentcall` | | video call, urgent call | no |
| `rhj.controller.congraturation` | | birthday | no |
| `rhj.controller.followme` | | follow | no |
| `rhj.controller.openweather` | | weather screen | no |
| `rhj.controller.openthings` | | countdown | no |
| `rhj.controller.openstock` | | stocks | no |
| `rhj.controller.openmyFans` | | fans | no |
| `rhj.controller.openinformation` | | news | no |
| `rhj.controller.opentime` | | clock | no |
| `rhj.controller.openmessage` | | messages | no |
| `rhj.controller.openBot` | | robot mode | no |
| `rhj.controller.openSleep` | | sleep mode | no |

### Speech-channel strings that are not DUI skills

From `SpeechConst`. Sent on the speech callback, not on RobotSDK.

| Command | Meaning |
|---|---|
| `wakeup_status` / `wakeup_doa` | wake and direction |
| `enter_chat_gpt` / `quit_chat_gpt` | cloud chat mode |
| `chat_gpt_speaking` / `chat_gpt_listening` | avatar substate |
| `add_clock` / `remove_clock` / `add_reminder` / `add_notice` | alarms |
| `hand_enter` / `hand_exit` | forwarded hand mode |
| `finger_guess_enter` / `finger_guess_exit` | forwarded game |
| `motion_happy` / `motion_sad` | forwarded emotion |
| `close_speech_audio` / `close_speech_audio_and_listen` | stop DUI audio |
| `shut_down_audio_service` | stop the audio process |
| `start_alarm_action` / `stop_alarm_action` | alarm gesture |
| `avatar.silence` / `listening` / `understanding` / `speaking` | dialogue state |

DUI reminder insert and remove use different strings, on the command callback rather than `setSpeechCmd`:

| Command | Meaning |
|---|---|
| `ai.dui.dskdm.reminder.insert` | add a reminder |
| `ai.dui.dskdm.reminder.remove` | remove a reminder |

## 4. Sound ids

Two vocabularies. They are not proven to be the same clip.

SDK and `setAudioEffect("controlSound", id)` use file ids: `a0020` angry, `a0032` laugh, `a0037` fear, `a0086` sad, `a0095` surprise. The longer list is in `GeeUIVoiceEmo/docs/ROBOTSDK.md`.

`MCUCommandConsts` also defines symbolic names: `lose`, `angry`, `funny`, `anger`, `cry`, `spoiledChild`, `happy`, `wrySmile`, `sad`, `avoidance`, `click`, `mistake`, `finish`, `findPerson`, `clock`, `shutdown`, `startUp`, `lowPower`, `charge`, `mainToFace`, `dizziness`, `wake`, `sleep`, `pant`. Do not pass these to `robotControlSound` unless a device log shows the firmware accepts them.

`robotPlayTTs` and `setTTS("speakText", text)` are spoken TTS, not a clip. Lemonade and CosyVoice must not call them.

## 5. App, status, and cloud commands

`setAppCmd` strings from `AppCmdConsts`:

| Command | Data | Meaning |
|---|---|---|
| `setRobotMode` | | mode change |
| `factory_in` / `factory_out` | | factory |
| `ota_in` / `ota_out` | | OTA |
| `previous_mode` | | back |
| `clock_start` / `clock_stop` | | alarm window |
| `go_sleep` | | sleep |
| `identHandResult` | `10101` in, `10102` out | hand ident |
| `start_audio_service` / `stop_audio_service` / `shut_down_audio_service` | | audio process |
| `cliff_trigger` | | cliff event |
| `take_photo` | | photo, distinct from `rhj.controller.takephoto` |
| `stop_app` | | stop an app |
| `stop_video_call` | | hang up |
| `openRobotReminder` / `openPreviewRobotReminder` / `closeRobotReminder` | | reminder UI |
| `killProcess` | package name or `all` | used by GeeUIFace, not in `AppCmdConsts` |

Reminder values: `water`, `sed`, `site`, `keep`, `open_search`, `close_search`.

`setLongConnectCommand` and `setRobotStatusCmd` strings from `RobotRemoteConsts`. None of these exist on RobotSDK:

`otaUpgrade`, `updateWifiConfig`, `updateBleConfig`, `updateShowModeConfig`, `updateSleepModeConfig`, `updateAwakeConfig`, `updateGeneralConfig`, `updateDateConfig`, `updateCalendarConfig`, `updateFansConfig`, `updateCountDownConfig`, `updateEventData`, `updateCustomContentData`, `updateCustomPhotoData`, `updateLampContentData`, `updateDisplaySwitchConfig`, `updateWeatherConfig`, `updateClockData`, `controlSendPic`, `controlSendWord`, `resetDeviceInfo`, `changeMode`, `addFaceFeature`, `controlSoundVolume`, `controlDisplayMode`, `controlAutoMode`, `updateDeviceAppMode`, `uninstall`, `updateRemindInfoData`.

Auto-mode values seen next to `controlAutoMode`: `follow`, `exitFollow`, `random`.

Precipice strings, also used as sensor data: `controlStartPrecipice`, `controlStopPrecipice`, `fallBackend`, `fallForward`.

## 6. RobotSDK methods

| Method | AIDL counterpart | In the fusion |
|---|---|---|
| `RobotService.getInstance(context)` | `bindService` on `android.intent.action.LETIANPAI` | yes, for the body |
| `unbindService()` | unbind | yes, on destroy |
| `robotOpenMotor()` | `powerControl` status 1 | once |
| `robotCloseMotor()` | `powerControl` status 0 | on shutdown only |
| `robotActionCommand(ActionMessage)` | `controlMotion` | emotion gestures only, numbers from the 1–80 list |
| `robotAntennaMotion(AntennaMessage)` | `controlAntennaMotion` | emotion |
| `robotAntennaLight(AntennaLightMessage)` | `controlAntennaLight` on | emotion |
| `robotCloseAntennaLight()` | light off | emotion |
| `robotStartExpression(id)` | `setExpression("controlFace", id)` | first face |
| `robotChangeExpression(id)` | same | later faces |
| `robotStopExpression()` | none | leave the face mode |
| `robotControlSound(id)` | `setAudioEffect` | emotion clips only |
| `robotPlayTTs(text)` | `setTTS("speakText", text)` | never for our voice |
| `robotOpenSensor()` / `robotCloseSensor()` | precipice start/stop, not a direct alias | not in v1 |
| `robotRegisterSensorCallback` / `robotUnregisterSensor` | `registerSensorResponseCallback` | not in v1 |
| `robotControlStatusBar(SHOW_CHARGING / HIDE_CHARGING)` | none | display only. Not a charging boolean |
| `sendLongCommand("speechDance" \| "speechMusic", "from_third")` | none on the AIDL | not `setLongConnectCommand` |

Sensor callbacks on the SDK: `onTapResponse`, `onDoubleTapResponse`, `onLongPressResponse`, `onFallBackend`, `onFallForward`, `onFallLeft`, `onFallRight`, `onTof`. The published page swaps front/back and left/right. Do not trust the labels until a device check.

## 7. What is still missing

| Need | Where a piece exists | Hole |
|---|---|---|
| Walk 98 | AIDL `controlMotion` | SDK list stops at 80 |
| One emotion pose | SDK, four calls | AIDL has the pieces but no single call |
| Volume, photo, open app | AIDL `setSpeechCmd` | SDK has nothing |
| Charging boolean | SDK can show an icon | neither bus returns a boolean |
| Cliff and tap | SDK callbacks, AIDL strings | names disagree |
| Music, alarm, weather, people search | speech strings only | no implementation in Voice or in the SDK |
| Wake word and DOA | `wakeup_status`, `wakeup_doa` | Lex, not either bus |
| Stop expression | `robotStopExpression` | no AIDL command |
| Sound name vs `a00xx` | both lists | no mapping table in source |
