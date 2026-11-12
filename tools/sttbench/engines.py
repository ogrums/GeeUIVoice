# -*- coding: utf-8 -*-
"""STT engine adapters: OpenAI-compatible HTTP, faster-whisper, Vosk, sherpa-onnx.

Chaque moteur a un `id`, une licence déclarée, `load()` / `transcribe(path)` /
`unload()`. Les moteurs HTTP ne mesurent que le temps de paroi (RAM serveur
inconnue sans docker stats).
"""
from __future__ import print_function

import json
import os
import resource
import time
import traceback

# Reuse the voicetest multipart helper style (stdlib only).


def rss_mb():
    """Resident set of the current process, in MiB (Linux)."""
    try:
        with open("/proc/self/status") as f:
            for line in f:
                if line.startswith("VmRSS:"):
                    return int(line.split()[1]) / 1024.0
    except (OSError, ValueError):
        pass
    # ru_maxrss is kB on Linux, bytes on macOS; this bench runs on Linux.
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


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


def http_get(url, timeout=8, headers=None):
    try:
        from urllib.request import Request, urlopen
        from urllib.error import URLError, HTTPError
    except ImportError:
        from urllib2 import Request, urlopen, URLError, HTTPError  # type: ignore
    req = Request(url, headers=headers or {})
    try:
        resp = urlopen(req, timeout=timeout)
        return resp.getcode(), resp.read()
    except HTTPError as e:
        return e.code, e.read() if e.fp else b""
    except URLError as e:
        return 0, str(e.reason).encode("utf-8", "replace")
    except Exception as e:
        return 0, str(e).encode("utf-8", "replace")


def openai_transcribe(base, wav_path, model="whisper-1", language="fr", timeout=60, extra_fields=None):
    """POST {base}/audio/transcriptions (base already includes /v1 or /api/v1)."""
    url = base.rstrip("/") + "/audio/transcriptions"
    with open(wav_path, "rb") as f:
        wav = f.read()
    boundary = "----sttbench%s" % int(time.time() * 1000000)
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

    if model:
        field("model", model)
    if language:
        field("language", language)
    if extra_fields:
        for k, v in extra_fields.items():
            field(k, v)
    field("file", wav, filename="turn.wav", ctype="audio/wav")
    parts.append(("--%s--\r\n" % boundary).encode("utf-8"))
    body = b"".join(parts)
    headers = {"Content-Type": "multipart/form-data; boundary=%s" % boundary}
    t0 = time.perf_counter()
    code, data, _ = http_post(url, body, headers, timeout=timeout)
    elapsed = time.perf_counter() - t0
    raw = data.decode("utf-8", "replace") if data else ""
    if code not in (200, 201):
        raise RuntimeError("STT HTTP %s %s : %s" % (code, url, raw[:400]))
    text = raw.strip()
    try:
        obj = json.loads(raw)
        if isinstance(obj, dict):
            text = (obj.get("text") or obj.get("transcription") or "").strip()
    except ValueError:
        pass
    return text, elapsed, raw[:500]


class Engine(object):
    def __init__(self, spec):
        self.spec = dict(spec)
        self.id = spec["id"]
        self.name = spec.get("name") or self.id
        self.kind = spec.get("kind") or "openai"
        self.license = spec.get("license") or ""
        self.loaded = False
        self.load_rss_mb = None
        self.load_s = None
        self._impl = None

    def load(self):
        t0 = time.perf_counter()
        rss0 = rss_mb()
        self._load()
        self.load_s = time.perf_counter() - t0
        self.load_rss_mb = max(0.0, rss_mb() - rss0)
        self.loaded = True

    def _load(self):
        kind = self.kind
        if kind in ("openai", "lemonade", "http"):
            return
        if kind == "faster-whisper":
            from faster_whisper import WhisperModel
            model = self.spec.get("model") or "tiny"
            device = self.spec.get("device") or "cpu"
            compute = self.spec.get("compute_type") or "int8"
            self._impl = WhisperModel(model, device=device, compute_type=compute)
            return
        if kind == "vosk":
            import vosk
            model_path = self.spec.get("model_path") or self.spec.get("model")
            if not model_path or not os.path.isdir(model_path):
                raise RuntimeError(
                    "Vosk : dossier modèle manquant (%s). "
                    "Télécharger vosk-model-small-fr-0.22 et passer --model-path."
                    % model_path
                )
            vosk.SetLogLevel(-1)
            self._impl = vosk.Model(model_path)
            return
        if kind == "sherpa-onnx":
            self._impl = _load_sherpa(self.spec)
            return
        if kind == "dummy":
            self._impl = self.spec.get("script") or {}
            return
        raise RuntimeError("kind inconnu : %s" % kind)

    def transcribe(self, wav_path):
        kind = self.kind
        if kind in ("openai", "lemonade", "http"):
            t0 = time.perf_counter()
            try:
                text, elapsed, raw = openai_transcribe(
                    self.spec["base"],
                    wav_path,
                    model=self.spec.get("model") or "",
                    language=self.spec.get("language") or "fr",
                    timeout=int(self.spec.get("timeout") or 90),
                )
                return {"text": text, "elapsed_s": elapsed, "raw": raw, "ok": True}
            except Exception as e:
                return {
                    "text": "",
                    "elapsed_s": time.perf_counter() - t0,
                    "ok": False,
                    "error": "%s: %s" % (type(e).__name__, e),
                }
        t0 = time.perf_counter()
        cpu0 = time.process_time()
        try:
            if kind == "faster-whisper":
                text = _fw_transcribe(self._impl, wav_path, self.spec)
            elif kind == "vosk":
                text = _vosk_transcribe(self._impl, wav_path)
            elif kind == "sherpa-onnx":
                text = _sherpa_transcribe(self._impl, wav_path)
            elif kind == "dummy":
                text = _dummy_transcribe(self._impl, wav_path, self.spec)
            else:
                raise RuntimeError("kind inconnu : %s" % kind)
            elapsed = time.perf_counter() - t0
            cpu = time.process_time() - cpu0
            return {"text": text, "elapsed_s": elapsed, "cpu_s": cpu, "ok": True}
        except Exception as e:
            elapsed = time.perf_counter() - t0
            return {
                "text": "",
                "elapsed_s": elapsed,
                "ok": False,
                "error": "%s: %s" % (type(e).__name__, e),
                "trace": traceback.format_exc()[-400:],
            }

    def unload(self):
        self._impl = None
        self.loaded = False


def _load_pcm16_mono(wav_path, target_rate=16000):
    """Load a WAV as float32 mono at target_rate (Whisper / sherpa expect 16 kHz).

    Avoids PyAV (`faster_whisper.decode_audio`), whose `metadata_errors=`
    argument broke with av 19.
    """
    import numpy as np
    import wave
    with wave.open(wav_path, "rb") as w:
        rate = w.getframerate()
        ch = w.getnchannels()
        sw = w.getsampwidth()
        n = w.getnframes()
        raw = w.readframes(n)
    if sw == 2:
        audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    elif sw == 4:
        audio = np.frombuffer(raw, dtype=np.int32).astype(np.float32) / 2147483648.0
    else:
        raise RuntimeError("WAV non PCM16/32 : %s (sampwidth=%s)" % (wav_path, sw))
    if ch > 1:
        audio = audio.reshape(-1, ch).mean(axis=1)
    if rate != target_rate:
        # Linear resample, good enough for a bench of already-16 kHz clips.
        import math
        new_n = int(round(len(audio) * float(target_rate) / rate))
        if new_n <= 1:
            return audio.astype(np.float32)
        x = np.linspace(0.0, len(audio) - 1, new_n)
        audio = np.interp(x, np.arange(len(audio)), audio).astype(np.float32)
    return audio


def _fw_transcribe(model, wav_path, spec):
    lang = spec.get("language") or "fr"
    audio = _load_pcm16_mono(wav_path, 16000)
    # condition_on_previous_text=False by default: short isolated
    # utterances (robot turns) otherwise tend to hallucinate.
    cond = spec.get("condition_on_previous_text")
    if cond is None:
        cond = False
    segments, info = model.transcribe(
        audio,
        language=lang,
        beam_size=int(spec.get("beam_size") or 5),
        vad_filter=bool(spec.get("vad_filter", False)),
        condition_on_previous_text=bool(cond),
    )
    return "".join(s.text for s in segments).strip()


def _vosk_transcribe(model, wav_path):
    import json as _json
    import wave
    import vosk
    with wave.open(wav_path, "rb") as w:
        rec = vosk.KaldiRecognizer(model, w.getframerate())
        rec.SetWords(True)
        while True:
            data = w.readframes(4000)
            if not data:
                break
            rec.AcceptWaveform(data)
        out = _json.loads(rec.FinalResult())
    return (out.get("text") or "").strip()


def _load_sherpa(spec):
    import sherpa_onnx
    kind = (spec.get("sherpa") or "nemo").lower()
    threads = int(spec.get("num_threads") or 4)
    if kind in ("nemo", "parakeet"):
        # API sherpa-onnx : OfflineRecognizer.from_transducer works for the
        # NeMo TDT export (encoder/decoder/joiner + tokens). Names follow
        # csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3.
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=spec["encoder"],
            decoder=spec["decoder"],
            joiner=spec["joiner"],
            tokens=spec["tokens"],
            num_threads=threads,
            model_type="nemo_transducer",
        )
    if kind == "whisper":
        return sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=spec["encoder"],
            decoder=spec["decoder"],
            tokens=spec["tokens"],
            language=spec.get("language") or "fr",
            num_threads=threads,
        )
    if kind in ("zipformer", "transducer"):
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            encoder=spec["encoder"],
            decoder=spec["decoder"],
            joiner=spec["joiner"],
            tokens=spec["tokens"],
            num_threads=threads,
        )
    raise RuntimeError("sherpa-onnx type inconnu : %s" % kind)


def _sherpa_transcribe(recognizer, wav_path):
    import wave
    import numpy as np
    with wave.open(wav_path, "rb") as w:
        assert w.getsampwidth() == 2
        samples = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype("float32") / 32768.0
        rate = w.getframerate()
    stream = recognizer.create_stream()
    stream.accept_waveform(rate, samples)
    recognizer.decode_stream(stream)
    return (stream.result.text or "").strip()


def _dummy_transcribe(script, wav_path, spec):
    """For unit tests: script is {basename: text} or a single `text` field."""
    base = os.path.basename(wav_path)
    if isinstance(script, dict) and base in script:
        return script[base]
    return spec.get("text") or script.get("default") or ""


def from_spec(spec):
    return Engine(spec)
