#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Campagne de tests vocaux pour le robot RUX (GeeUIVoice).

Modes :
  wav     — hook adb TEST_WAV (pas d'acoustique) : STT → chat → TTS sur le robot
  chaine  — haut-parleur + micro du PC, près du robot

Suites ( --suite ) : dialogue, emotion, voice, all.
"""
from __future__ import print_function

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import wave
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SERIAL = "2006014140527"
DEFAULT_REMOTE = "/sdcard/Android/data/com.geeui.voice/files/voicetest"
DEFAULT_PHONON = "http://nimbus:8880"
DEFAULT_WHISPER = "http://nimbus:13305/api/v1"
DEFAULT_VOICE = "Hélène"
PACKAGE = "com.geeui.voice"
ACTION = "com.geeui.voice.TEST_WAV"
RECEIVER = "com.geeui.voice/.TestWavReceiver"

# Visages SdkMap (main), si la fusion dépasse le seuil 0,45.
FACE = {
    "happy": "h0020",
    "sad": "h0119",
    "angry": "h0001",
    "fear": "h0133",
    "surprise": "h0046",
    "neutral": "h0059",
    "disgust": "h0033",
    "disgusted": "h0033",
}
VALENCE = {
    "happy": "pos",
    "surprise": "pos",
    "sad": "neg",
    "angry": "neg",
    "fear": "neg",
    "disgust": "neg",
    "disgusted": "neg",
    "neutral": "neu",
}
# Sidecar keeps the ONNX class `disgusted`; older phrase files may still say `disgust`.
LABEL_ALIASES = {
    "disgust": "disgusted",
    "disgusted": "disgusted",
    "fearful": "fear",
    "surprised": "surprise",
}
# Post-prosodie si Phonon n'a pas de style d'émotion (vitesse, gain, demi-tons).
EMOTION_PROSODY = {
    "happy": (1.12, 1.00, 2),
    "sad": (0.85, 0.80, -3),
    "angry": (1.15, 1.30, -1),
    "neutral": (1.00, 1.00, 0),
    "fear": (1.10, 0.95, 2),
    "surprise": (1.08, 1.10, 3),
}


def log(msg):
    sys.stdout.write(msg + "\n")
    sys.stdout.flush()


def which(name):
    return shutil.which(name)


def run(cmd, timeout=120, check=True, input_data=None):
    p = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        input=input_data,
    )
    if check and p.returncode != 0:
        err = (p.stderr or p.stdout or b"").decode("utf-8", "replace")
        raise RuntimeError("commande échouée (%s): %s\n%s" % (p.returncode, " ".join(cmd), err[-2000:]))
    return p


def adb(serial, args, timeout=60, check=True):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    return run(cmd, timeout=timeout, check=check)


def fold_text(s):
    if not s:
        return ""
    s = s.lower().replace("é", "e").replace("è", "e").replace("ê", "e")
    s = s.replace("à", "a").replace("â", "a").replace("î", "i").replace("ï", "i")
    s = s.replace("ô", "o").replace("ù", "u").replace("û", "u").replace("ç", "c")
    s = s.replace("'", " ").replace("'", " ").replace("-", " ")
    s = re.sub(r"[^a-z0-9°\s]", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def has_keyword(text, word):
    t = fold_text(text)
    w = fold_text(word)
    if not w:
        return True
    if w in ("°", "degre"):
        return ("degre" in t) or ("°" in (text or ""))
    return w in t


def first_word(text):
    parts = fold_text(text).split()
    return parts[0] if parts else ""


def cut_start(heard, expected_text):
    """True when the first expected word is missing or only a suffix (début coupé)."""
    exp = first_word(expected_text)
    got = first_word(heard)
    if not exp:
        return False
    if not got:
        return True
    if got == exp or got.startswith(exp) or (exp.startswith(got) and len(got) >= max(3, len(exp) - 1)):
        return False
    if exp.endswith(got) and len(got) >= 3:
        return True
    return got != exp


# --- WAV I/O -----------------------------------------------------------------

def read_wav(path):
    with wave.open(path, "rb") as w:
        ch = w.getnchannels()
        sw = w.getsampwidth()
        rate = w.getframerate()
        n = w.getnframes()
        raw = w.readframes(n)
    if sw != 2:
        raise RuntimeError("WAV %s : seuls les 16 bits sont gérés ici (%d bytes/sample)" % (path, sw))
    if ch == 2:
        # downmix
        import array
        s = array.array("h")
        s.frombytes(raw)
        mono = array.array("h")
        for i in range(0, len(s), 2):
            mono.append(int((s[i] + s[i + 1]) / 2))
        raw = mono.tobytes()
        ch = 1
    return raw, rate


def write_wav(path, pcm, rate, channels=1):
    with wave.open(path, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)


def duration_s(pcm, rate, channels=1):
    if rate <= 0:
        return 0.0
    return len(pcm) / 2.0 / channels / float(rate)


def fix_wav_header(data):
    """Phonon peut renvoyer un WAV streamé avec des tailles à 0."""
    if len(data) < 44 or data[0:4] != b"RIFF" or data[8:12] != b"WAVE":
        return data
    out = bytearray(data)
    riff_size = len(data) - 8
    out[4:8] = riff_size.to_bytes(4, "little")
    # patch the last data chunk size if we can find it
    i = 12
    while i + 8 <= len(out):
        cid = bytes(out[i:i + 4])
        sz = int.from_bytes(out[i + 4:i + 8], "little")
        start = i + 8
        if cid == b"data":
            real = len(out) - start
            out[i + 4:i + 8] = real.to_bytes(4, "little")
            break
        if sz < 0 or start > len(out):
            break
        i = start + sz + (sz & 1)
    return bytes(out)


# --- Audio transforms (stdlib audioop, ffmpeg if present) --------------------

def _audioop():
    try:
        import audioop
        return audioop
    except ImportError:
        try:
            import audioop as audioop  # noqa
        except ImportError:
            return None


def apply_gain(pcm, gain):
    if gain == 1.0:
        return pcm
    ao = _audioop()
    if ao is None:
        import array
        s = array.array("h")
        s.frombytes(pcm)
        for i in range(len(s)):
            v = int(s[i] * gain)
            s[i] = max(-32768, min(32767, v))
        return s.tobytes()
    # audioop.mul uses a factor; clip is built-in
    return ao.mul(pcm, 2, float(gain))


def apply_speed(pcm, rate, speed):
    if abs(speed - 1.0) < 1e-6:
        return pcm, rate
    ao = _audioop()
    if ao is None:
        raise RuntimeError("audioop indisponible : installez Python < 3.13 ou ffmpeg")
    # Interpréter un ré-échantillonnage vers rate/speed à la fréquence d'origine = changement de débit.
    new_rate = max(1000, int(rate / speed))
    converted, _ = ao.ratecv(pcm, 2, 1, rate, new_rate, None)
    return converted, rate


def apply_pitch(pcm, rate, semitones, wav_path=None, out_path=None):
    """Change the pitch. Prefers ffmpeg (duration kept). Fallback couples pitch and speed."""
    if abs(semitones) < 1e-6:
        return pcm, rate
    factor = 2 ** (semitones / 12.0)
    ff = which("ffmpeg")
    if ff and wav_path and out_path:
        # asetrate changes pitch+tempo, atempo restores duration (0.5..2.0).
        tempo = 1.0 / factor
        filters = ["asetrate=%d" % int(rate * factor), "aresample=%d" % rate]
        # atempo must be 0.5-2.0; chain if needed
        t = tempo
        while t < 0.5:
            filters.append("atempo=0.5")
            t /= 0.5
        while t > 2.0:
            filters.append("atempo=2.0")
            t /= 2.0
        filters.append("atempo=%s" % t)
        cmd = [ff, "-y", "-i", wav_path, "-af", ",".join(filters), "-ar", str(rate), "-ac", "1", out_path]
        try:
            run(cmd, timeout=60)
            return read_wav(out_path)
        except Exception as e:
            log("  (ffmpeg pitch a échoué : %s — repli Python, durée changée)" % e)
    ao = _audioop()
    if ao is None:
        raise RuntimeError("impossible de changer la hauteur (ni ffmpeg ni audioop)")
    new_rate = max(1000, int(rate / factor))
    converted, _ = ao.ratecv(pcm, 2, 1, rate, new_rate, None)
    return converted, rate


# --- HTTP : Phonon + Whisper -------------------------------------------------

def http_post(url, body, headers, timeout=60):
    try:
        from urllib.request import Request, urlopen
        from urllib.error import URLError, HTTPError
    except ImportError:
        from urllib2 import Request, urlopen, URLError, HTTPError  # type: ignore
    req = Request(url, data=body, headers=headers)
    try:
        resp = urlopen(req, timeout=timeout)
        return resp.getcode(), resp.read(), dict(resp.headers)
    except HTTPError as e:
        return e.code, e.read() if e.fp else b"", {}
    except URLError as e:
        raise RuntimeError("HTTP %s : %s" % (url, e.reason))


def json_escape(s):
    return json.dumps(s, ensure_ascii=False)


def phonon_speech(base, text, voice, emotion=None, timeout=60):
    """OpenAI-compatible POST /v1/audio/speech. Prefers WAV. Tries an emotion field."""
    url = base.rstrip("/") + "/v1/audio/speech"
    payload = {
        "model": "tts-1",
        "input": text,
        "voice": voice or "default",
        "response_format": "wav",
    }
    if emotion and emotion != "neutral":
        payload["emotion"] = emotion
        payload["style"] = emotion
    body = json.dumps(payload).encode("utf-8")
    code, data, _ = http_post(url, body, {"Content-Type": "application/json"}, timeout=timeout)
    if code not in (200, 201) or not data or data[:1] == b"{":
        # retry without style fields
        if emotion:
            return phonon_speech(base, text, voice, emotion=None, timeout=timeout)
        raise RuntimeError("Phonon HTTP %s : %s" % (code, (data or b"")[:300]))
    if data[:4] == b"RIFF":
        return fix_wav_header(data), "wav"
    return data, "bin"


def whisper_transcribe(base, wav_path, model="Whisper-Small", timeout=60):
    url = base.rstrip("/") + "/audio/transcriptions"
    with open(wav_path, "rb") as f:
        wav = f.read()
    boundary = "----geeui%s" % int(time.time() * 1000)
    parts = []
    def field(name, value, filename=None, ctype=None):
        line = "--%s\r\nContent-Disposition: form-data; name=\"%s\"" % (boundary, name)
        if filename:
            line += "; filename=\"%s\"" % filename
        line += "\r\n"
        if ctype:
            line += "Content-Type: %s\r\n" % ctype
        line += "\r\n"
        parts.append(line.encode("utf-8"))
        parts.append(value if isinstance(value, bytes) else value.encode("utf-8"))
        parts.append(b"\r\n")
    field("model", model)
    field("file", wav, filename="turn.wav", ctype="audio/wav")
    parts.append(("--%s--\r\n" % boundary).encode("utf-8"))
    body = b"".join(parts)
    headers = {"Content-Type": "multipart/form-data; boundary=%s" % boundary}
    code, data, _ = http_post(url, body, headers, timeout=timeout)
    raw = data.decode("utf-8", "replace")
    if code not in (200, 201):
        raise RuntimeError("Whisper HTTP %s : %s" % (code, raw[:400]))
    try:
        obj = json.loads(raw)
        return (obj.get("text") or "").strip()
    except ValueError:
        m = re.search(r'"text"\s*:\s*"(.*)"', raw, re.S)
        return (m.group(1) if m else raw).strip()


# --- Play / record (Windows + Linux) ----------------------------------------

def play_wav(path, volume=1.0):
    """Play a WAV on the PC speaker. volume 0..1 is applied to a temp copy when < 1."""
    play_path = path
    tmp = None
    if volume < 0.99:
        pcm, rate = read_wav(path)
        pcm = apply_gain(pcm, volume)
        tmp = path + ".vol.wav"
        write_wav(tmp, pcm, rate)
        play_path = tmp
    try:
        if os.name == "nt":
            import winsound
            winsound.PlaySound(play_path, winsound.SND_FILENAME)
            return
        # paplay / aplay / ffplay
        for cmd in (
            ["paplay", play_path],
            ["aplay", "-q", play_path],
            ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", play_path],
        ):
            if which(cmd[0]):
                run(cmd, timeout=120)
                return
        raise RuntimeError("aucun lecteur audio (winsound, paplay, aplay, ffplay)")
    finally:
        if tmp and os.path.exists(tmp):
            try:
                os.remove(tmp)
            except OSError:
                pass


def record_wav(path, seconds, rate=16000):
    try:
        import sounddevice as sd
        import numpy as np
    except ImportError:
        raise RuntimeError(
            "mode chaine : installez les dépendances avec\n"
            "  pip install -r %s" % os.path.join(HERE, "requirements.txt")
        )
    log("  enregistrement %.1f s…" % seconds)
    rec = sd.rec(int(seconds * rate), samplerate=rate, channels=1, dtype="int16")
    sd.wait()
    write_wav(path, rec.tobytes(), rate)
    return rec.flatten(), rate


def first_voiced(pcm_or_array, rate, start_s, thresh=800, frame_ms=20):
    """Return time of first voiced frame after start_s, or None."""
    import array
    if hasattr(pcm_or_array, "flatten"):
        # sounddevice returns shape (N, 1); flatten so we get ints, not [v] lists.
        samples = [int(x) for x in pcm_or_array.flatten().tolist()]
    elif isinstance(pcm_or_array, (bytes, bytearray)):
        a = array.array("h")
        a.frombytes(bytes(pcm_or_array))
        samples = list(a)
    else:
        samples = list(pcm_or_array)
    start = int(start_s * rate)
    frame = max(1, int(rate * frame_ms / 1000.0))
    # thresh is RMS-like; use mean absolute as a simple energy
    for i in range(start, max(start, len(samples) - frame), frame):
        chunk = samples[i:i + frame]
        if not chunk:
            break
        acc = 0.0
        for s in chunk:
            acc += s * s
        rms = (acc / len(chunk)) ** 0.5
        if rms >= thresh:
            return i / float(rate)
    return None


# --- Phrase shaping ----------------------------------------------------------

def load_phrases(path, suite):
    with open(path, "r", encoding="utf-8") as f:
        items = json.load(f)
    if suite and suite != "all":
        items = [p for p in items if p.get("suite", "dialogue") == suite]
    return items


def shape_phrase_wav(src, dest, phrase, tmpdir):
    pcm, rate = read_wav(src)
    emotion = phrase.get("emotion")
    if emotion:
        speed, gain, semi = EMOTION_PROSODY.get(emotion, (1.0, 1.0, 0))
        # explicit overrides win
        speed = phrase.get("speed", speed)
        gain = phrase.get("gain", gain)
        semi = phrase.get("pitch_semitones", semi)
    else:
        speed = phrase.get("speed", 1.0)
        gain = phrase.get("gain", 1.0)
        semi = phrase.get("pitch_semitones", 0)

    if abs(semi) >= 0.1:
        pre = os.path.join(tmpdir, "pre-pitch-%s.wav" % phrase["id"])
        pitched = os.path.join(tmpdir, "pitch-%s.wav" % phrase["id"])
        write_wav(pre, pcm, rate)
        pcm, rate = apply_pitch(pcm, rate, semi, wav_path=pre, out_path=pitched)
        if os.path.exists(pitched) and os.path.getsize(pitched) > 44:
            pcm, rate = read_wav(pitched)

    if abs(speed - 1.0) >= 1e-6:
        pcm, rate = apply_speed(pcm, rate, speed)
    if abs(gain - 1.0) >= 1e-6:
        pcm = apply_gain(pcm, gain)
    write_wav(dest, pcm, rate)
    return dest


def generate_base_wav(phrase, dest, phonon, voice, wav_dir, regen):
    ready = os.path.join(wav_dir, phrase["id"] + ".wav")
    # A pre-made file for this exact id always wins unless --regen.
    if os.path.isfile(ready) and not regen:
        shutil.copy(ready, dest)
        return dest, "fichier"
    # Reuse a cache of the raw text (no prosody) so variants share one Phonon call.
    cache = os.path.join(wav_dir, "_cache")
    os.makedirs(cache, exist_ok=True)
    key = re.sub(r"[^a-z0-9]+", "-", fold_text(phrase["text"]))[:80] or "phrase"
    raw = os.path.join(cache, key + ".wav")
    if not os.path.isfile(raw) or regen:
        log("  Phonon : « %s »" % phrase["text"])
        data, kind = phonon_speech(phonon, phrase["text"], voice, emotion=None)
        if kind != "wav":
            # try to wrap / fail clearly
            tmp_bin = raw + ".bin"
            with open(tmp_bin, "wb") as f:
                f.write(data)
            ff = which("ffmpeg")
            if not ff:
                raise RuntimeError("Phonon n'a pas renvoyé de WAV (début %r). Placez un WAV dans wavs/%s.wav ou installez ffmpeg." % (data[:8], phrase["id"]))
            run([ff, "-y", "-i", tmp_bin, "-ar", "24000", "-ac", "1", raw], timeout=60)
        else:
            with open(raw, "wb") as f:
                f.write(data)
    shutil.copy(raw, dest)
    return dest, "phonon"


# --- Robot hook --------------------------------------------------------------

STAGING = "/data/local/tmp/geeui-voicetest-in.wav"
MIRROR = "/data/local/tmp/geeui"


def app_read(serial, path):
    """Lit un fichier de test : miroir /data/local/tmp/geeui, sinon cat, sinon run-as."""
    name = path.rstrip("/").split("/")[-1]
    candidates = [MIRROR + "/" + name, path]
    seen = set()
    for cand in candidates:
        if cand in seen:
            continue
        seen.add(cand)
        for args in (["exec-out", "cat", cand], ["exec-out", "run-as", PACKAGE, "cat", cand]):
            p = adb(serial, args, check=False)
            data = p.stdout or b""
            if p.returncode == 0 and data and not data.startswith(b"cat:") and b"Permission denied" not in data[:200]:
                return data
    return b""


def remote_dir(serial):
    # Canonical path the app writes to (getExternalFilesDir("voicetest")).
    return DEFAULT_REMOTE


def wait_status(serial, remote, timeout, prev_mtime=None):
    status_path = remote.rstrip("/") + "/status.txt"
    result_path = remote.rstrip("/") + "/result.json"
    t0 = time.time()
    saw_running = False
    while time.time() - t0 < timeout:
        text = app_read(serial, status_path).decode("utf-8", "replace").strip().replace("\r", "")
        if text in ("running", "done", "error"):
            if text == "running":
                saw_running = True
            elif saw_running or text in ("done", "error"):
                # If we never saw running, still accept a fresh done after a short wait
                return text
        time.sleep(0.4)
    return "timeout"


def pull_results(serial, remote, dest_dir):
    os.makedirs(dest_dir, exist_ok=True)
    out = {}
    for name in ("result.json", "status.txt", "last-turn.json", "tts.mp3", "tts.wav", "tts.ogg"):
        data = app_read(serial, remote.rstrip("/") + "/" + name)
        if data:
            with open(os.path.join(dest_dir, name), "wb") as f:
                f.write(data)
            out[name] = os.path.join(dest_dir, name)
    return out


def parse_result(path):
    if not path or not os.path.isfile(path):
        return {}
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def broadcast_wav(serial, local_wav, remote):
    remote_wav = remote.rstrip("/") + "/in.wav"
    # Android 11 : `adb push` direct dans Android/data échoue (remote fchown) et
    # les fichiers écrits par l'app sont en 600. On passe par /data/local/tmp,
    # on copie avec le shell, on rend lisible, et on lit les résultats via run-as.
    adb(serial, ["shell", "mkdir", "-p", remote, MIRROR], check=False)
    adb(serial, ["shell", "chmod", "777", MIRROR], check=False)
    adb(serial, ["push", local_wav, STAGING], timeout=30)
    adb(serial, ["shell", "cp %s %s && chmod 666 %s" % (STAGING, remote_wav, remote_wav)], timeout=30)
    for name in ("status.txt", "result.json", "tts.mp3", "tts.wav", "tts.ogg", "last-turn.json"):
        adb(serial, ["shell", "rm", "-f", remote.rstrip("/") + "/" + name, MIRROR + "/" + name], check=False)
    p = adb(
        serial,
        [
            "shell", "am", "broadcast",
            "-a", ACTION,
            "-n", RECEIVER,
            "--es", "path", remote_wav,
        ],
        timeout=30,
    )
    return (p.stdout or b"").decode("utf-8", "replace")


def dump_logcat(serial, dest):
    p = adb(serial, ["logcat", "-d", "-s", "GeeUIVoiceTest:I", "GeeUIVoice:I"], check=False)
    with open(dest, "wb") as f:
        f.write(p.stdout or b"")
    return dest


# --- Judging -----------------------------------------------------------------

def judge(phrase, heard, reply, result, last_turn, mode, latency=None, reply_transcript=None):
    checks = []

    def add(name, ok, detail=""):
        checks.append({"name": name, "ok": bool(ok), "detail": detail})

    if result.get("error") and not result.get("ok", True) and not heard:
        add("exécution", False, result.get("error") or "erreur")
        return checks

    add("stt_non_vide", bool(heard), heard or (result.get("error") or "vide"))

    for w in phrase.get("expect_heard") or []:
        add("entendu:%s" % w, has_keyword(heard, w), heard)

    for w in phrase.get("expect_reply") or []:
        text = " ".join(x for x in (reply, reply_transcript) if x)
        add("réponse:%s" % w, has_keyword(text, w), text)

    if phrase.get("loud_start") or phrase.get("suite") == "dialogue" and phrase["id"] == "preroll-voiture":
        cut = cut_start(heard, phrase["text"])
        add("debut_coupé", not cut, "entendu=%r attendu=%r" % (first_word(heard), first_word(phrase["text"])))

    expect_emo = (phrase.get("expect_audio_emotion") or "").lower()
    audio = (result.get("audio") or {})
    if not audio and last_turn:
        audio = {
            "emotion": last_turn.get("audio_emotion") or "",
            "confidence": last_turn.get("audio_confidence") or 0,
        }
    got_emo = (audio.get("emotion") or "").lower()
    conf = float(audio.get("confidence") or 0)
    if expect_emo:
        if expect_emo == "neutral":
            # Neutral: accept explicit neutral, or any label with low confidence.
            ok = (got_emo in ("", "neutral") or conf < 0.45)
            add("emotion2vec:%s" % expect_emo, ok, "audio=%s confiance=%.2f" % (got_emo or "?", conf))
        else:
            exact = got_emo == expect_emo or LABEL_ALIASES.get(got_emo, got_emo) == LABEL_ALIASES.get(expect_emo, expect_emo)
            same = VALENCE.get(got_emo) == VALENCE.get(expect_emo) and got_emo != ""
            if exact:
                add("emotion2vec:%s" % expect_emo, True, "confiance=%.2f" % conf)
            elif same:
                add("emotion2vec:%s" % expect_emo, False, "proche (%s, confiance=%.2f) — valence ok, label différent" % (got_emo, conf))
            else:
                add("emotion2vec:%s" % expect_emo, False, "audio=%s confiance=%.2f" % (got_emo or "?", conf))
            if conf < 0.3 and exact:
                checks[-1]["detail"] += " (confiance faible)"

    expect_face = phrase.get("expect_face_code") or ""
    if phrase.get("expect_face") and not expect_face:
        expect_face = "*"
    face = (
        result.get("face_during_speech")
        or (result.get("tags") or {}).get("face")
        or (last_turn or {}).get("face_during_speech")
        or (last_turn or {}).get("face")
        or ""
    )
    face_events = result.get("faces") or (last_turn or {}).get("faces") or []
    if expect_face == "*":
        add("visage", bool(face) and face != "h0059", "face=%s" % (face or "aucun"))
    elif expect_face:
        # Neutral rest face may stay empty or h0059.
        if expect_face == "h0059":
            ok = face in ("", "h0059")
        else:
            ok = face == expect_face
        add("visage:%s" % expect_face, ok, "face=%s pendant la parole (audio ≥ 0,70 prioritaire ; événements=%s)" % (face or "aucun", face_events or "—"))

    tag_emo = (result.get("tags") or {}).get("emotion") or ""
    if expect_emo and tag_emo:
        add("balise_reponse:%s" % tag_emo, True, "ton de la réponse (informatif)")

    actions = (result.get("tags") or {}).get("actions") or []
    if actions:
        add("balises_action", True, ", ".join(actions))

    if mode == "chaine" and phrase.get("gain", 1.0) <= 0.3:
        add("vad_déclenché", bool(heard), "amplitude faible / distance")

    if latency is not None:
        add("latence_ms", latency < 8000, "%s ms (fin de phrase → voix du robot)" % int(latency))

    return checks


def passed(checks):
    # Informative checks (balise_reponse, balises_action) always ok=True.
    return all(c["ok"] for c in checks if not c["name"].startswith("balise"))


# --- Campaign ---------------------------------------------------------------

def run_wav_mode(phrase, wav, args, shot_dir):
    remote = remote_dir(args.serial)
    adb(args.serial, ["logcat", "-c"], check=False)
    broadcast_wav(args.serial, wav, remote)
    status = wait_status(args.serial, remote, args.timeout)
    pulled = pull_results(args.serial, remote, shot_dir)
    dump_logcat(args.serial, os.path.join(shot_dir, "logcat.txt"))
    result = parse_result(pulled.get("result.json"))
    last_turn = parse_result(pulled.get("last-turn.json"))
    if status == "timeout" and not result:
        result = {"ok": False, "error": "timeout en attendant result.json (%ss)" % args.timeout}
    heard = result.get("transcript") or (last_turn or {}).get("heard") or ""
    reply = result.get("reply") or (last_turn or {}).get("answer") or ""
    return result, last_turn, heard, reply, None, None


def run_chaine_mode(phrase, wav, args, shot_dir):
    pcm, rate = read_wav(wav)
    play_dur = duration_s(pcm, rate)
    # Tours lents observés jusqu'à ~17 s (1er jeton parfois 6-7 s) : garder 18 s après la lecture.
    rec_s = min(args.timeout, max(18.0, play_dur + 18.0))
    rec_path = os.path.join(shot_dir, "pc-record.wav")
    remote = remote_dir(args.serial)
    adb(args.serial, ["logcat", "-c"], check=False)
    # Start recording, then play (small pre-roll on the recording).
    preroll = 0.4
    try:
        import sounddevice as sd
        import numpy as np
    except ImportError:
        raise RuntimeError(
            "mode chaine : pip install -r %s" % os.path.join(HERE, "requirements.txt")
        )
    total = preroll + rec_s
    log("  lecture PC (%.1f s) + enregistrement (%.1f s)" % (play_dur, total))
    rec = sd.rec(int(total * 16000), samplerate=16000, channels=1, dtype="int16")
    time.sleep(preroll)
    t_play = time.time()
    play_wav(wav, volume=float(phrase.get("play_volume", 1.0)))
    t_end_play = time.time()
    # Wait for the remainder of the recording window.
    leftover = total - (time.time() - (t_play - preroll))
    if leftover > 0:
        time.sleep(leftover)
    sd.wait()
    write_wav(rec_path, rec.tobytes(), 16000)

    play_end_in_rec = preroll + (t_end_play - t_play)
    onset = first_voiced(rec, 16000, start_s=play_end_in_rec + 0.05)
    latency = None if onset is None else max(0.0, (onset - play_end_in_rec) * 1000.0)

    # Transcribe the robot reply (segment after play end).
    reply_transcript = ""
    try:
        import array
        samples = array.array("h")
        samples.frombytes(rec.tobytes())
        start = int((play_end_in_rec + 0.05) * 16000)
        tail = samples[start:]
        if len(tail) > 16000 * 0.3:
            tail_path = os.path.join(shot_dir, "pc-reply.wav")
            write_wav(tail_path, tail.tobytes(), 16000)
            reply_transcript = whisper_transcribe(args.whisper, tail_path)
    except Exception as e:
        log("  (transcription de la réponse : %s)" % e)

    # result.json n'est écrit que par le hook WAV : en chaîne il est périmé.
    # last-turn.json est mis à jour à chaque tour, mais parfois pendant "think"
    # (réponse encore vide). On attend un peu que la réponse arrive.
    last_turn = {}
    t_wait = time.time()
    while time.time() - t_wait < 15:
        pulled = pull_results(args.serial, remote, shot_dir)
        last_turn = parse_result(pulled.get("last-turn.json")) or {}
        if last_turn.get("answer") or last_turn.get("mode") in ("talk", "idle"):
            break
        time.sleep(0.6)
    dump_logcat(args.serial, os.path.join(shot_dir, "logcat.txt"))
    # Ne pas lire result.json (stale). Reconstruire depuis last-turn.
    result = {
        "ok": bool(last_turn.get("heard")),
        "transcript": last_turn.get("heard") or "",
        "reply": last_turn.get("answer") or "",
        "audio": {
            "emotion": last_turn.get("audio_emotion") or "",
            "confidence": last_turn.get("audio_confidence") or 0,
        },
        "tags": {"face": last_turn.get("face_during_speech") or last_turn.get("face") or ""},
        "face_during_speech": last_turn.get("face_during_speech") or "",
        "faces": last_turn.get("faces") or [],
    }
    heard = result["transcript"]
    reply = result["reply"]
    return result, last_turn, heard, reply, latency, reply_transcript


def emotion_stability(rows):
    """Among voice-suite rows, labels with confidence >= 0.45 should not wander too much."""
    labels = []
    for r in rows:
        if r.get("suite") != "voice":
            continue
        audio = (r.get("result") or {}).get("audio") or {}
        emo = (audio.get("emotion") or "").lower()
        conf = float(audio.get("confidence") or 0)
        if conf >= 0.45 and emo and emo != "neutral":
            labels.append(emo)
    uniq = sorted(set(labels))
    return uniq, len(uniq) <= 2


def write_report(campaign, dest_json, dest_md):
    with open(dest_json, "w", encoding="utf-8") as f:
        json.dump(campaign, f, ensure_ascii=False, indent=2)
        f.write("\n")
    lines = []
    a = lines.append
    a("# Rapport tests vocaux RUX")
    a("")
    a("- Date : %s (Europe/Paris)" % campaign["started"])
    a("- Mode : `%s`" % campaign["mode"])
    a("- Suite : `%s`" % campaign["suite"])
    a("- Série adb : `%s`" % (campaign.get("serial") or ""))
    a("")
    summary = campaign["summary"]
    a("## Synthèse pass/fail")
    a("")
    a("| ok | total | échoués |")
    a("|---|---|---|")
    a("| %s | %s | %s |" % (summary["passed"], summary["total"], summary["failed"]))
    a("")
    if campaign.get("stability"):
        st = campaign["stability"]
        a("Stabilité émotion (suite voice, confiance ≥ 0,45) : %s — labels %s" % (
            "ok" if st["ok"] else "instable",
            ", ".join(st["labels"]) or "(aucun label franc)",
        ))
        a("")
    a("## Détail")
    a("")
    for row in campaign["rows"]:
        flag = "PASS" if row["pass"] else "FAIL"
        a("### `%s` — %s" % (row["id"], flag))
        a("")
        a(row.get("notes") or "")
        a("")
        a("- Phrase : « %s »" % row.get("text", ""))
        a("- Entendu (robot) : « %s »" % (row.get("heard") or ""))
        a("- Réponse : « %s »" % (row.get("reply") or ""))
        if row.get("reply_transcript"):
            a("- Réponse transrite (micro PC) : « %s »" % row["reply_transcript"])
        audio = (row.get("result") or {}).get("audio") or {}
        tags = (row.get("result") or {}).get("tags") or {}
        if audio or tags:
            faces = (row.get("result") or {}).get("faces") or (row.get("last_turn") or {}).get("faces") or []
            during = (row.get("result") or {}).get("face_during_speech") or (row.get("last_turn") or {}).get("face_during_speech") or tags.get("face") or "—"
            raw = audio.get("raw") or (row.get("last_turn") or {}).get("audio_emotion_raw") or ""
            a("- emotion2vec : %s (confiance %s%s) ; balise réponse : %s ; visage pendant la parole : %s ; événements : %s ; actions : %s" % (
                audio.get("emotion") or "?",
                audio.get("confidence") if audio else "?",
                (", raw %s %.2f" % (raw, float((audio.get("raw_confidence") or (row.get("last_turn") or {}).get("audio_emotion_raw_confidence") or 0)))) if raw else "",
                tags.get("emotion") or "—",
                during,
                faces or "—",
                ", ".join(tags.get("actions") or []) or "—",
            ))
        timings = (row.get("result") or {}).get("timings_ms") or {}
        if timings:
            a("- Temps (ms) : STT %s, 1er jeton %s, 1er audio TTS %s, total %s" % (
                timings.get("stt"), timings.get("first_token"),
                timings.get("tts_first_audio"), timings.get("total"),
            ))
        if row.get("latency_ms") is not None:
            a("- Latence fin de phrase → voix du robot : %s ms" % int(row["latency_ms"]))
        a("")
        a("| contrôle | résultat | détail |")
        a("|---|---|---|")
        for c in row.get("checks") or []:
            a("| %s | %s | %s |" % (c["name"], "ok" if c["ok"] else "échec", (c.get("detail") or "").replace("|", "/")))
        a("")
    with open(dest_md, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description="Tests vocaux autonomes GeeUIVoice / RUX")
    ap.add_argument("--mode", choices=("wav", "chaine"), default="wav")
    ap.add_argument("--suite", choices=("all", "dialogue", "emotion", "voice"), default="all")
    ap.add_argument("--phrases", default=os.path.join(HERE, "phrases.json"))
    ap.add_argument("--adb-serial", dest="serial", default=DEFAULT_SERIAL)
    ap.add_argument("--phonon", default=DEFAULT_PHONON, help="http://nimbus:8880")
    ap.add_argument("--whisper", default=DEFAULT_WHISPER, help="http://nimbus:13305/api/v1")
    ap.add_argument("--phonon-voice", default=DEFAULT_VOICE)
    ap.add_argument("--wav-dir", default=os.path.join(HERE, "wavs"))
    ap.add_argument("--out-dir", default=os.path.join(HERE, "out"))
    ap.add_argument("--timeout", type=int, default=90, help="secondes max par phrase")
    ap.add_argument("--regen", action="store_true", help="regénérer les WAV Phonon même s'ils existent")
    ap.add_argument("--ids", default="", help="sous-ensemble d'id, séparés par des virgules")
    args = ap.parse_args(argv)

    phrases = load_phrases(args.phrases, args.suite)
    if args.ids:
        wanted = {x.strip() for x in args.ids.split(",") if x.strip()}
        phrases = [p for p in phrases if p["id"] in wanted]
    if args.mode == "wav":
        phrases = [p for p in phrases if not p.get("chain_only")]
    if not phrases:
        log("aucune phrase à jouer")
        return 2

    if not which("adb"):
        log("adb introuvable dans le PATH")
        return 2
    # Quick adb visibility (do not fail generate-only later).
    p = adb(args.serial, ["get-state"], check=False)
    state = (p.stdout or b"").decode().strip()
    if state != "device":
        log("attention : adb -s %s get-state → %r (le robot doit être connecté pour les tests)" % (args.serial, state))

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = os.path.join(args.out_dir, "campaign-%s" % stamp)
    os.makedirs(out, exist_ok=True)
    os.makedirs(args.wav_dir, exist_ok=True)
    tmp = os.path.join(out, "_tmp")
    os.makedirs(tmp, exist_ok=True)

    rows = []
    log("campagne %s  mode=%s  suite=%s  (%d phrases)" % (stamp, args.mode, args.suite, len(phrases)))
    for phrase in phrases:
        pid = phrase["id"]
        log("— %s  « %s »" % (pid, phrase["text"]))
        shot = os.path.join(out, pid)
        os.makedirs(shot, exist_ok=True)
        row = {
            "id": pid,
            "suite": phrase.get("suite", "dialogue"),
            "text": phrase["text"],
            "notes": phrase.get("notes") or "",
            "pass": False,
            "checks": [],
            "error": None,
        }
        try:
            base = os.path.join(tmp, pid + "-base.wav")
            generate_base_wav(phrase, base, args.phonon, args.phonon_voice, args.wav_dir, args.regen)
            shaped = os.path.join(shot, "prompt.wav")
            shape_phrase_wav(base, shaped, phrase, tmp)
            if args.mode == "wav":
                result, last_turn, heard, reply, latency, reply_tr = run_wav_mode(phrase, shaped, args, shot)
            else:
                result, last_turn, heard, reply, latency, reply_tr = run_chaine_mode(phrase, shaped, args, shot)
            # Fill audio/tags from last-turn when result.json is missing (chaine).
            if last_turn and not (result or {}).get("audio"):
                result = dict(result or {})
                result.setdefault("audio", {
                    "emotion": last_turn.get("audio_emotion") or "",
                    "confidence": last_turn.get("audio_confidence") or 0,
                })
                result.setdefault("face_during_speech", last_turn.get("face_during_speech") or "")
                result.setdefault("faces", last_turn.get("faces") or [])
                result.setdefault("tags", {
                    "face": last_turn.get("face_during_speech") or last_turn.get("face") or "",
                    "emotion": "",
                    "actions": [],
                })
                if last_turn.get("audio_emotion_raw"):
                    result.setdefault("audio", {})
                    if isinstance(result.get("audio"), dict):
                        result["audio"].setdefault("raw", last_turn.get("audio_emotion_raw"))
                        result["audio"].setdefault("raw_confidence", last_turn.get("audio_emotion_raw_confidence") or 0)
            row["result"] = result
            row["last_turn"] = last_turn
            row["heard"] = heard
            row["reply"] = reply
            row["reply_transcript"] = reply_tr
            row["latency_ms"] = latency
            row["checks"] = judge(phrase, heard, reply, result or {}, last_turn, args.mode, latency, reply_tr)
            row["pass"] = passed(row["checks"]) and not (result or {}).get("error")
            if (result or {}).get("error") and not heard:
                row["pass"] = False
                row["error"] = result.get("error")
        except Exception as e:
            row["error"] = str(e)
            row["checks"] = [{"name": "exécution", "ok": False, "detail": str(e)}]
            row["pass"] = False
            log("  ERREUR : %s" % e)
        flag = "PASS" if row["pass"] else "FAIL"
        log("  %s" % flag)
        rows.append(row)
        # brief pause so the robot returns to idle
        time.sleep(1.0)

    uniq, stable = emotion_stability(rows)
    campaign = {
        "started": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "tz": "Europe/Paris",
        "mode": args.mode,
        "suite": args.suite,
        "serial": args.serial,
        "rows": rows,
        "summary": {
            "total": len(rows),
            "passed": sum(1 for r in rows if r["pass"]),
            "failed": sum(1 for r in rows if not r["pass"]),
        },
        "stability": {"ok": stable, "labels": uniq},
    }
    dest_json = os.path.join(out, "report.json")
    dest_md = os.path.join(out, "report.md")
    write_report(campaign, dest_json, dest_md)
    log("")
    log("terminé : %s/%s ok" % (campaign["summary"]["passed"], campaign["summary"]["total"]))
    log("rapport : %s" % dest_md)
    return 0 if campaign["summary"]["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
