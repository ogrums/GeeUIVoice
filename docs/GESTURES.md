# Gestures

Every foot gesture this project can name. The published RobotSDK list is 1–80. GeeUIVoice also sends 98, which is not in that list. GeeUIFace sometimes sends 0, which has no published name.

`ActionMessage.set(number, speed, stepNum)` then `robotActionCommand`. On the AIDL bus the same number goes in `setMcuCommand("controlMotion", ...)`. `step=n` means the caller picks the count. "fixed" means the firmware ignores the requested speed.

Do not send 98 through `robotActionCommand`.

| n | Gesture | Default | Used by |
|---|---|---|---|
| 0 | no published name | | GeeUIFace, as a placeholder step |
| 1 | walk forward | step=n, speed=3 | |
| 2 | walk back | step=n, speed=3 | |
| 3 | turn left | step=n, speed=3 | |
| 4 | turn right | step=n, speed=3 | |
| 5 | crab left | step=n, speed=3 | |
| 6 | crab right | step=n, speed=3 | |
| 7 | shake left leg | step=1, delay=2 | |
| 8 | shake right leg | step=1, delay=2 | |
| 9 | shake left foot | step=2, delay=3 | |
| 10 | shake right foot | step=2, delay=3 | |
| 11 | left foot up | step=1, delay=1 | |
| 12 | right foot up | step=1, delay=1 | |
| 13 | lean left | step=1, delay=6 | |
| 14 | lean right | step=1, delay=6 | |
| 15 | stomp left | step=1, delay=3 | angry pose |
| 16 | stomp right | step=1, delay=3 | |
| 17 | body up and down | step=1, delay=1 | |
| 18 | body left and right | step=1, delay=1 | |
| 19 | head left and right | step=2, delay=1 | |
| 20 | rest | step=1, delay=1 | sad pose |
| 21 | spin left in place, about 20° | step=1, delay=3 | |
| 22 | spin right in place, about 20° | step=1, delay=3 | |
| 23 | both feet shake | step=3, delay=1 | |
| 24 | small shake | step=1, delay=3 | |
| 25 | small turn left, about 5° | step=1, delay=3 | |
| 26 | small turn right, about 5° | step=1, delay=3 | |
| 27 | sway | step=1, speed=3 | |
| 28 | nod | step=1, speed=3 | |
| 29 | random | step=1, speed=3 | |
| 30 | random | step=1, speed=3 | |
| 31 | random | step=1, speed=3 | |
| 32 | random | step=1, speed=3 | |
| 33 | random | at least 3, speed=3 | |
| 34 | small foot spin | at least 3, speed=3 | |
| 35 | random or small foot shake | step=1, speed=3 | |
| 36 | random or small foot shake | step=1, speed=3 | |
| 37 | random or small foot shake | step=1, speed=3 | |
| 38 | random or small foot shake | step=1, speed=3 | |
| 39 | random or small foot shake | step=1, speed=3 | |
| 40 | random or small foot shake | step=1, speed=3 | |
| 41 | random or small foot shake | step=1, speed=3 | |
| 42 | random or small foot shake | step=1, speed=3 | |
| 43 | small nod | step=1, speed=3 | |
| 44 | dodge left | step=1, speed=3 | fear pose |
| 45 | dodge right | step=1, speed=3 | |
| 46 | small dodge left | step=1, speed=3 | |
| 47 | small dodge right | step=1, speed=3 | |
| 48 | both feet out, fast | step=1, fixed speed | |
| 49 | double shake, fast | step=1, fixed speed | |
| 50 | twist forward and back | step=1, speed=3 | |
| 51 | small shake, left foot | step=1, speed=3 | |
| 52 | small shake, right foot | step=1, speed=3 | |
| 53 | left foot outward | step=1, speed=3 | |
| 54 | right foot outward | step=1, speed=3 | |
| 55 | turn left, about −3° | step=1, speed=3 | |
| 56 | turn right, about −3° | step=1, speed=3 | |
| 57 | small spin, right foot | step=1, speed=3 | |
| 58 | large dodge right | step=1, fixed speed | |
| 59 | rub front, middle, back | step=1, speed=3 | |
| 60 | rub front and back | step=1, speed=3 | |
| 61 | leg up | step=1, fixed speed | |
| 62 | leg up and shake | step=1, fixed speed | |
| 63 | forward, variant 2 | step=1, speed=3 | SDK demo walk |
| 64 | back, variant 2 | step=1, speed=3 | GeeUIVoice "recule". Not confirmed on this device |
| 65 | left foot spin, fast | at least 3, fixed speed | |
| 66 | right foot spin, fast | at least 3, fixed speed | |
| 67 | fast shake | step=1, fixed speed | |
| 68 | fast outward shake | step=1, fixed speed | |
| 69 | step onto pad 2 | step=1, speed=1 | |
| 70 | step onto pad 3 | step=1, speed=1 | |
| 71 | spin on pad 1 | step=1, speed=2 | |
| 72 | spin on pad 2 | step=1, speed=2 | |
| 73 | spin on pad 3 | step=1, speed=2 | |
| 74 | spin on pad 4 | step=1, speed=2 | |
| 75 | spin on pad 5 | step=1, speed=2 | |
| 76 | nod yes | step=1, speed=3 | surprise pose |
| 77 | yeah | step=1, speed=3 | happy pose |
| 78 | fast twist | step=1, speed=1 | |
| 79 | roll | step=1, speed=1 | |
| 80 | dance twist | step=2, speed=6 | |
| 98 | walk forward, validated on this robot | step=3, speed=2 | GeeUIVoice "avance". AIDL only |

Ears, lights, and face ids are not foot gestures. They are in [ROBOT.md](ROBOT.md).
