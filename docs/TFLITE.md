# TFLite emotion

Branch `experiment/tflite-emotion`. The fused app is unchanged.

`sense` chooses the source. `voice` scores the utterance already captured for Whisper. `face` reads the camera, center-cropped, with no preview. `off` leaves the keyword list alone.

```
adb shell am start -n com.geeui.voice/.VoiceActivity --es mode debug --es sense voice
adb shell am start -n com.geeui.voice/.VoiceActivity --es mode debug --es sense face
```

The debug line shows `tflite voice: modèle absent` until a file is present. Drop it here, then relaunch. No rebuild.

```
adb shell mkdir -p /sdcard/Android/data/com.geeui.voice/files
adb push emotion_voice.tflite /sdcard/Android/data/com.geeui.voice/files/
adb push emotion_face.tflite /sdcard/Android/data/com.geeui.voice/files/
```

Voice input is float32 `[1, 128, 64]`, labels `neutral happy sad angry fear surprise`. Face input is float32 `[1, 48, 48, 1]`, FER order `angry disgust fear happy sad surprise neutral`. Disgust is shown as angry. A score under 0.45 stays neutral. Audio and the keyword list still vote in `Arbiter`.

`tools/export_emotion_tflite.py` trains that voice CNN from 16 kHz wav folders, or converts a Keras SavedModel. emotion2vec and wav2vec do not convert: their ops are not in TensorFlow Lite. Record French on this microphone, or start from CaFE, and train the small net. Distill a big model into it if you need its accuracy on device.
