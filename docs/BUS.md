# RobotSDK and LetianpaiService

Two buses, not two copies of the same API. RobotSDK 2.5 (`com.leitianpai.robotsdk.RobotService`) is a third-party facade. LetianpaiService (`ILetianpaiService`) is the in-process bus every GeeUI app binds. The fusion uses both until a row below says otherwise.

SDK names come from `GeeUIVoiceEmo/docs/ROBOTSDK.md` and `DemoForRobotSDK`. The 2.5 AAR was not re-downloaded for this table (the CDN certificate is expired). AIDL names come from `GeeUIComponents/CommChannel/.../ILetianpaiService.aidl`. Command strings come from `MCUCommandConsts` and `GeeUIAIAudioService` `Const.java`.

`setX(command, data)` is one method. The command string is the real function. A register/unregister pair exists for every channel and is not repeated in the table.

## Body

| Function | RobotSDK | LetianpaiService | Gap |
|---|---|---|---|
| Open servos | `robotOpenMotor()` | `setMcuCommand("powerControl", {"function":3 or 5,"status":1})` | Both open the rail. Fusion must call this once. |
| Close servos | `robotCloseMotor()` | `powerControl` with `status:0` | Same split: function 3 and 5 are two rails. SDK hides that. |
| Walk / gesture | `robotActionCommand(ActionMessage)` numbers **1–80**. 63 = forward 2, 64 = back 2 | `setMcuCommand("controlMotion", {number, stepNum, speed})` | GeeUIVoice walks with number **98**. That id is not in the SDK table. Do not send 98 through `robotActionCommand`. |
| Ears | `robotAntennaMotion(AntennaMessage)` cmd 1–3, step, speed ms, angle 0–90 | `setMcuCommand("controlAntennaMotion", …)` | Same idea. Field names differ. |
| Antenna light on | `robotAntennaLight(AntennaLightMessage)` colors `RED GREEN BLUE ORANGE WHITE YELLOW PURPLE CYAN BLACK` | `setMcuCommand("controlAntennaLight", "on" + color id)` | SDK has no brightness or pattern, only a color. |
| Antenna light off | `robotCloseAntennaLight()` | light value `"off"` | Match. |
| Face start | `robotStartExpression(id)` once | `setExpression("controlFace", id)` | SDK needs start, then change. AIDL is a single set. |
| Face change | `robotChangeExpression(id)` | same `setExpression` | |
| Face stop | `robotStopExpression()` | no dedicated stop | Missing on the AIDL side. |
| Built-in sound | `robotControlSound("a0020")` | `setAudioEffect("controlSound", id)` | Same asset ids. |
| SDK TTS | `robotPlayTTs(text)` | `setTTS("speakText", text)` | Do not use either for Lemonade. Both would speak with the vendor voice. |
| Long command | `sendLongCommand("speechDance" \| "speechMusic", "from_third")` | no equivalent in the AIDL we ship | Dance and music stay outside both buses. |
| Status bar | `robotControlStatusBar(SHOW/HIDE_CHARGING)` | no match in the AIDL | SDK only. |
| Raw AT | removed. Demo comments still show `robotControlCommand` | `powerControl` is the leftover | Do not add `AT+MOVEW` back. |

## Sensors

| Function | RobotSDK | LetianpaiService | Gap |
|---|---|---|---|
| Open / close | `robotOpenSensor` / `robotCloseSensor` | `setSensorResponse` plus the command strings `controlStartPrecipice` / `controlStopPrecipice` | Not the same call shape. |
| Listen | `robotRegisterSensorCallback` | `registerSensorResponseCallback` | |
| Tap / double tap / long press | `onTapResponse`, `onDoubleTapResponse`, `onLongPressResponse` | delivered as sensor command strings, not typed callbacks | AIDL has no typed tap methods. |
| Cliff | `onFallBackend`, `onFallForward`, `onFallLeft`, `onFallRight` | `fallBackend`, `fallForward` | The SDK page swaps left/right and front/back labels. Confirm on the robot. |
| Obstacle | `onTof` | no named command in the consts we have | SDK only, until verified. |
| Unregister | `robotUnregisterSensor` | `unregisterSensorResponseCallback` | |

## Speech and apps

These exist only on LetianpaiService. RobotSDK has no method for them.

| Function | LetianpaiService | Used by GeeUIVoice |
|---|---|---|
| Volume | `setSpeechCmd("DUI.MediaController.SetVolume", "+" \| "-" \| "0" \| "100" \| number)` | yes |
| Play / pause / next / prev | `DUI.MediaController.Play`, `Pause`, `Stop`, `Next`, `Prev` | no |
| Reboot / shutdown | `DUI.System.Reboot`, `DUI.System.Shutdown` | router only, no phrase |
| Open / close app | `setSpeechCmd("rhj.controller.openApp" \| "closeApp", name)` | open only |
| Photo | `rhj.controller.takephoto` | yes |
| Navigation / turn | `rhj.controller.navigation`, `rhj.controller.turn` | no. Voice uses `controlMotion` instead |
| Show a face by skill | `rhj.controller.show` | no. Voice uses `setExpression` |
| Happy / sad motion | `rhj.controller.chatgpt.happy`, `.sad` | no. Emo replaces this with the pose table |
| Search people | `rhj.controller.searchPeople` | no |
| Reminder | `rhj.controller.remind` | no |
| Video call / urgent call | `rhj.controller.videocall`, `urgentcall` | no |
| Games | `fingerguess`, `handscontroller`, `body.enter` | no |
| Birthday | `rhj.controller.congraturation` | no |

## Channels with no SDK equivalent

| AIDL method | What it carries | Fusion |
|---|---|---|
| `getRobotStatus` / `setRobotStatus` | mode integer | keep, SDK cannot read it |
| `setCommand` / `LtpCommand` | generic command object | unused by Voice |
| `setLongConnectCommand` | cloud push (`selfIntroduction`, `remoteStroll`) | not in the fused loop |
| `setAppCmd` | mode changes, `killProcess`, sleep | Lex / launcher still need it |
| `setRobotStatusCmd` | status bus | not used by Voice |
| `setIdentifyCmd` | face-ident results | not in Voice |
| `setMiCmd` | Mijia | not in Voice |
| `setBleCmd` / `setBleResponse` | BLE | not in Voice |
| `setAudioEffect` | `a00xx` clips | overlap with `robotControlSound`. Pick one |
| `setTTS` | vendor or forwarded TTS | do not call for Lemonade |

## What the fusion still lacks

| Need | Present on | Missing |
|---|---|---|
| Walk that matches the real robot (98) | AIDL `controlMotion` | SDK action list stops at 80 |
| Emotion pose (face, ears, light, sound) | SDK `EmotionBus` | AIDL can do each piece, but not as one call |
| Volume, photo, open app | AIDL `setSpeechCmd` | SDK |
| Charging flag for the skill router | SDK status bar show/hide only | no boolean API on either bus |
| Cliff / tap | SDK callbacks | AIDL strings, labels unverified |
| Music, alarm, weather, people search | AIDL speech commands | neither Voice nor the SDK implements them |
| Wake word | neither | Lex |
