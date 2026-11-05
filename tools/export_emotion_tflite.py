#!/usr/bin/env python3
"""Train or convert a small emotion model for the robot.

Voice file emotion_voice.tflite
  input  float32 [1, 128, 64]   log-mel, same window as LogMel.kt
  output float32 [1, 6]         neutral, happy, sad, angry, fear, surprise

Face file emotion_face.tflite
  input  float32 [1, 48, 48, 1] grayscale, 0..1, center crop
  output float32 [1, 7]         angry, disgust, fear, happy, sad, surprise, neutral
  The app reads disgust as angry.

A Keras SavedModel or .h5 converts directly. emotion2vec, wav2vec and Whisper
do not: they use ops TensorFlow Lite cannot run. Train this CNN on French
audio instead, or distill those models into it.

French speech: record on the robot mic, or use CaFE (Canadian French
Emotional Speech). One folder per label, 16 kHz wav.

    python tools/export_emotion_tflite.py train voice path/to/wavs emotion_voice.tflite
    python tools/export_emotion_tflite.py convert voice path/to/saved_model emotion_voice.tflite

Push without rebuilding:

    adb shell mkdir -p /sdcard/Android/data/com.geeui.voice/files
    adb push emotion_voice.tflite /sdcard/Android/data/com.geeui.voice/files/
"""

import sys

VOICE = ["neutral", "happy", "sad", "angry", "fear", "surprise"]
FACE = ["angry", "disgust", "fear", "happy", "sad", "surprise", "neutral"]
SR = 16000
N_FFT = 512
WIN = 400
HOP = 160
MELS = 64
FRAMES = 128


def hz_to_mel(hz):
    import math
    return 2595.0 * math.log10(1.0 + hz / 700.0)


def mel_to_hz(mel):
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


def log_mel(pcm):
    """Match LogMel.kt. pcm is float32 in -1..1."""
    import math
    import numpy as np

    samples = np.asarray(pcm, dtype=np.float32)
    window = 0.5 - 0.5 * np.cos(2.0 * math.pi * np.arange(WIN) / (WIN - 1))
    mel_max = hz_to_mel(SR / 2.0)
    points = [mel_to_hz(mel_max * i / (MELS + 1)) for i in range(MELS + 2)]
    edges = [min(N_FFT // 2, max(0, int(math.floor((N_FFT + 1) * p / SR)))) for p in points]
    hops = 0 if len(samples) <= WIN else (len(samples) - WIN) // HOP + 1
    keep = min(hops, FRAMES)
    start = max(0, hops - keep)
    out = np.zeros((FRAMES, MELS), dtype=np.float32)
    for t in range(keep):
        offset = (start + t) * HOP
        frame = np.zeros(N_FFT, dtype=np.float32)
        n = min(WIN, len(samples) - offset)
        frame[:n] = samples[offset:offset + n] * window[:n]
        spec = np.abs(np.fft.rfft(frame, n=N_FFT))
        for m in range(MELS):
            left, center, right = edges[m], edges[m + 1], edges[m + 2]
            acc = 0.0
            for k in range(left, right + 1):
                if center > left and left <= k <= center:
                    weight = (k - left) / (center - left)
                elif right > center and center <= k <= right:
                    weight = (right - k) / (right - center)
                else:
                    weight = 0.0
                acc += weight * spec[k]
            out[FRAMES - keep + t, m] = math.log(max(acc, 1e-10))
    return out


def convert(kind, source, dest):
    import tensorflow as tf

    converter = tf.lite.TFLiteConverter.from_saved_model(source)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    open(dest, "wb").write(converter.convert())
    print("wrote", dest, "from", source, kind)


def train(kind, root, dest):
    import numpy as np
    import tensorflow as tf
    from pathlib import Path

    labels = VOICE if kind == "voice" else FACE
    xs = []
    ys = []
    for index, name in enumerate(labels):
        folder = Path(root) / name
        for wav in folder.glob("*.wav"):
            audio, sr = _read_wav(wav)
            if sr != SR:
                raise SystemExit(f"{wav} is {sr} Hz, need {SR}")
            if kind == "voice":
                xs.append(log_mel(audio))
            else:
                raise SystemExit("face training expects 48x48 npy, not wav")
            ys.append(index)
    if not xs:
        raise SystemExit(f"no wav files under {root}/<label>/*.wav")
    x = np.stack(xs)[..., None]
    y = tf.keras.utils.to_categorical(ys, len(labels))
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(FRAMES, MELS, 1)),
        tf.keras.layers.Conv2D(16, 3, activation="relu", padding="same"),
        tf.keras.layers.MaxPool2D(2),
        tf.keras.layers.Conv2D(32, 3, activation="relu", padding="same"),
        tf.keras.layers.GlobalAveragePooling2D(),
        tf.keras.layers.Dense(len(labels), activation="softmax"),
    ])
    model.compile(optimizer="adam", loss="categorical_crossentropy", metrics=["accuracy"])
    model.fit(x, y, epochs=30, batch_size=16, validation_split=0.1)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    open(dest, "wb").write(converter.convert())
    print("wrote", dest)


def _read_wav(path):
    import wave
    import numpy as np

    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        raw = w.readframes(w.getnframes())
        width = w.getsampwidth()
    if width != 2:
        raise SystemExit(f"{path} is not 16-bit pcm")
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0, sr


def main(argv):
    if len(argv) != 4 or argv[0] not in ("train", "convert") or argv[1] not in ("voice", "face"):
        raise SystemExit(__doc__)
    if argv[0] == "train":
        train(argv[1], argv[2], argv[3])
    else:
        convert(argv[1], argv[2], argv[3])


if __name__ == "__main__":
    main(sys.argv[1:])
