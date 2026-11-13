# -*- coding: utf-8 -*-
"""WAV 16 kHz mono PCM16 helpers, plus duration and simple pitch/speed variants."""
from __future__ import print_function

import os
import struct
import subprocess
import wave


def duration_s(path):
    with wave.open(path, "rb") as w:
        frames = w.getnframes()
        rate = w.getframerate() or 1
        return frames / float(rate)


def ensure_16k_mono(src, dest):
    """Convert any audio file to 16 kHz mono PCM16 WAV via ffmpeg."""
    dest_dir = os.path.dirname(dest)
    if dest_dir:
        os.makedirs(dest_dir, exist_ok=True)
    cmd = [
        "ffmpeg", "-y", "-i", src,
        "-ar", "16000", "-ac", "1", "-acodec", "pcm_s16le",
        dest,
    ]
    subprocess.check_call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return dest


def vary(src, dest, speed=1.0, gain=1.0, pitch_semitones=0.0):
    """Speed, gain and pitch via ffmpeg. Duration is preserved for pitch."""
    dest_dir = os.path.dirname(dest)
    if dest_dir:
        os.makedirs(dest_dir, exist_ok=True)
    filters = []
    if abs(gain - 1.0) > 1e-3:
        filters.append("volume=%.4f" % gain)
    if abs(speed - 1.0) > 1e-3:
        # atempo accepts 0.5..2.0; chain if needed.
        s = speed
        while s > 2.0:
            filters.append("atempo=2.0")
            s /= 2.0
        while s < 0.5:
            filters.append("atempo=0.5")
            s /= 0.5
        filters.append("atempo=%.4f" % s)
    if abs(pitch_semitones) > 1e-3:
        ratio = 2 ** (pitch_semitones / 12.0)
        filters.append("asetrate=16000*%.6f,aresample=16000,atempo=%.6f" % (ratio, 1.0 / ratio))
    if not filters:
        if os.path.abspath(src) != os.path.abspath(dest):
            ensure_16k_mono(src, dest)
        return dest
    cmd = [
        "ffmpeg", "-y", "-i", src,
        "-af", ",".join(filters),
        "-ar", "16000", "-ac", "1", "-acodec", "pcm_s16le",
        dest,
    ]
    subprocess.check_call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return dest


def pcm16_info(path):
    with wave.open(path, "rb") as w:
        return {
            "rate": w.getframerate(),
            "channels": w.getnchannels(),
            "sampwidth": w.getsampwidth(),
            "frames": w.getnframes(),
            "duration_s": w.getnframes() / float(w.getframerate() or 1),
        }


def write_silence(dest, seconds=1.0, rate=16000):
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    n = int(seconds * rate)
    with wave.open(dest, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(struct.pack("<" + "h" * n, *([0] * n)))
    return dest
