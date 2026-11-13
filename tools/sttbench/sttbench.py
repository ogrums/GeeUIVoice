#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Banc d'essai STT français pour RUX / GeeUIVoice.

Sous-commandes :
  inventory  — lister les STT déjà sur nimbus, sans rien modifier
  prepare    — constituer le jeu (manifest + WAV 16 kHz), audio hors git
  run        — transcrire, mesurer WER / coupures / latence / RTF / RAM
  report     — table Markdown à partir d'un JSON de campagne

Aucun audio n'est commité. CaFE est CC BY-NC-SA 4.0 ; l'acteur 07 (15 ans)
est exclu. Voir README.md.
"""
from __future__ import print_function

import argparse
import json
import os
import statistics
import sys
import time
import zipfile
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from normalize import aggregate, score  # noqa: E402
from wavutil import duration_s, ensure_16k_mono, vary  # noqa: E402
from engines import Engine, http_get, openai_transcribe, rss_mb  # noqa: E402

# Phrases officielles, Readme.txt CaFE v1.1 (48 kHz), encodage Latin-1.
# Acteur 07 = 15 ans : jamais utilisé.
CAFE_SENTENCES = {
    "1": "Un cheval fou dans mon jardin",
    "2": "Deux ânes aigris au pelage brun",
    "3": "Trois cygnes aveugles au bord du lac",
    "4": "Quatre vieilles truies éléphantesques",
    "5": "Cinq pumas fiers et passionnés",
    "6": "Six ours aimants domestiqués",
}
CAFE_EMOTION = {
    "C": "angry", "D": "disgust", "J": "happy", "N": "neutral",
    "P": "fear", "S": "surprise", "T": "sad",
}
SKIP_CAFE_MINORS = {"07"}
DEFAULT_CAFE_ACTORS = ["01", "02", "09", "12"]
CAFE_LICENSE = "CC BY-NC-SA 4.0"

# Ports / services à ne jamais arrêter pendant l'inventaire ou un déploiement temporaire.
PROTECTED_PORTS = {8080, 8880, 8900, 13305, 13306, 49112}


def log(msg):
    sys.stderr.write(msg.rstrip() + "\n")
    sys.stderr.flush()


def load_catalog(path=None):
    p = Path(path) if path else HERE / "candidates.json"
    return json.loads(p.read_text(encoding="utf-8"))


def now_iso():
    return datetime.now().astimezone().isoformat(timespec="seconds")


# ----- inventory -------------------------------------------------------------

def _decode(raw):
    if raw is None:
        return ""
    if isinstance(raw, bytes):
        return raw.decode("utf-8", "replace")
    return raw


def _try_json(raw):
    try:
        return json.loads(_decode(raw) or "null")
    except ValueError:
        return None


def probe_endpoint(item, timeout=8):
    """Read-only probe. Never POST /load. Never stops a container."""
    base = (item.get("base") or "").rstrip("/")
    out = {
        "id": item.get("id"),
        "name": item.get("name"),
        "base": base,
        "kind": item.get("kind"),
        "license": item.get("license"),
        "notes": item.get("notes"),
        "reachable": False,
        "urls": {},
        "models": [],
        "models_all": [],
        "error": None,
    }
    if not base:
        out["error"] = "pas d'URL"
        return out

    # Strip a trailing /v1 or /api/v1 to also try the host root.
    roots = [base]
    for suffix in ("/api/v1", "/v1"):
        if base.endswith(suffix):
            roots.append(base[: -len(suffix)] or base)
            break
    seen = set()
    for root in roots + [base + "/v1"]:
        if root in seen:
            continue
        seen.add(root)
        for path in (
            "/health",
            "/api/v1/health",
            "/v1/models",
            "/api/v1/models",
            "/models",
            "/api/v1/models?show_all=true",
        ):
            url = root + path if path.startswith("/") else root + "/" + path
            # When root already ends with /api/v1, path /api/v1/models would double. Skip.
            if "/api/v1/api/v1" in url or "/v1/v1/" in url:
                continue
            if path.startswith("/api/v1") and root.endswith("/api/v1"):
                url = root + path[len("/api/v1"):]
            if path.startswith("/v1") and root.endswith("/v1") and not path.startswith("/v1/models"):
                pass
            code, raw = http_get(url, timeout=timeout)
            body = _decode(raw)[:800]
            out["urls"][url] = {"status": code, "body_preview": body}
            if code and code < 500:
                out["reachable"] = True
            obj = _try_json(raw)
            if isinstance(obj, dict) and isinstance(obj.get("data"), list):
                models = []
                for m in obj["data"]:
                    if not isinstance(m, dict):
                        continue
                    models.append({
                        "id": m.get("id"),
                        "downloaded": m.get("downloaded"),
                        "labels": m.get("labels"),
                        "checkpoint": m.get("checkpoint"),
                        "recipe": m.get("recipe"),
                        "size": m.get("size"),
                    })
                if "show_all=true" in url:
                    out["models_all"] = models
                elif not out["models"]:
                    out["models"] = models
    if not out["reachable"] and not out["error"]:
        out["error"] = "injoignable (timeout, refus, ou HTTP 5xx)"
    return out


def cmd_inventory(args):
    catalog = load_catalog(args.catalog)
    items = list(catalog.get("inventory") or [])
    if args.base:
        for i, base in enumerate(args.base):
            items.append({
                "id": "extra-%d" % i,
                "name": base,
                "kind": "openai",
                "base": base,
                "notes": "ajouté en ligne de commande",
            })
    report = {
        "at": now_iso(),
        "host_note": (
            "Inventaire en lecture seule. Aucun POST /load, aucun docker stop, "
            "aucun changement de conteneur (pockettts, kyutai, nanobot de bob)."
        ),
        "endpoints": [],
    }
    for item in items:
        log("probe %s %s" % (item.get("id"), item.get("base")))
        report["endpoints"].append(probe_endpoint(item, timeout=args.timeout))
    dest = Path(args.out) if args.out else Path("inventory.json")
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log("écrit %s" % dest)
    # Short stdout table
    print("# Inventaire STT\n")
    print("- Date : %s" % report["at"])
    print("- %s\n" % report["host_note"])
    for e in report["endpoints"]:
        flag = "OK" if e["reachable"] else "INJOIGNABLE"
        print("## %s — %s" % (e["id"], flag))
        print("- URL : `%s`" % e["base"])
        if e.get("license"):
            print("- Licence : %s" % e["license"])
        if e.get("error"):
            print("- Erreur : %s" % e["error"])
        models = e.get("models") or []
        if models:
            print("- Modèles chargés / téléchargés :")
            for m in models:
                labels = m.get("labels") or []
                flag_t = " [transcription]" if (
                    "transcription" in labels or str(m.get("id") or "").lower().startswith("whisper")
                ) else ""
                dl = m.get("downloaded")
                print("  - `%s`%s%s" % (
                    m.get("id"),
                    flag_t,
                    " (downloaded=%s)" % dl if dl is not None else "",
                ))
        allm = e.get("models_all") or []
        whisper_all = [
            m for m in allm
            if "transcription" in (m.get("labels") or [])
            or str(m.get("id") or "").lower().startswith("whisper")
        ]
        if whisper_all:
            print("- Variantes Whisper du catalogue (show_all) : " + ", ".join(
                "`%s`%s" % (m["id"], "" if m.get("downloaded") else " non téléchargé")
                for m in whisper_all
            ))
        print()
    return 0


# ----- prepare ---------------------------------------------------------------

def _cafe_from_name(name):
    """Parse AA-E-I-S.wav (possibly under a folder). Returns dict or None."""
    base = os.path.basename(name).replace(".wav", "").replace(".WAV", "")
    parts = base.split("-")
    if len(parts) != 4:
        return None
    actor, emo, intensity, sent = parts
    if actor in SKIP_CAFE_MINORS:
        return None
    if emo not in CAFE_EMOTION or sent not in CAFE_SENTENCES:
        return None
    return {
        "actor": actor,
        "emotion": CAFE_EMOTION[emo],
        "emotion_code": emo,
        "intensity": intensity,
        "sentence": sent,
        "text": CAFE_SENTENCES[sent],
        "gender": "M" if int(actor) % 2 == 1 else "F",
    }


def extract_cafe(zip_path, dest_dir, predicate, already=None):
    """Extract matching CaFE wavs as 16 kHz mono. Returns list of clip dicts."""
    dest_dir = Path(dest_dir)
    dest_dir.mkdir(parents=True, exist_ok=True)
    clips = []
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            if info.is_dir() or not info.filename.lower().endswith(".wav"):
                continue
            meta = _cafe_from_name(info.filename)
            if not meta or not predicate(meta):
                continue
            cid = "cafe-%s-%s-%s-s%s" % (
                meta["actor"], meta["emotion"],
                "n" if meta["emotion"] == "neutral" else meta["intensity"],
                meta["sentence"],
            )
            dest = dest_dir / (cid + ".wav")
            if already and dest.exists() and dest.stat().st_size > 44:
                pass
            else:
                import tempfile
                with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
                    tmp.write(z.read(info.filename))
                    tmp_path = tmp.name
                try:
                    ensure_16k_mono(tmp_path, str(dest))
                finally:
                    os.unlink(tmp_path)
            clips.append({
                "id": cid,
                "path": str(dest),
                "text": meta["text"],
                "condition": "cafe",
                "language": "fr",
                "actor": meta["actor"],
                "gender": meta["gender"],
                "emotion": meta["emotion"],
                "intensity": meta["intensity"],
                "sentence": meta["sentence"],
                "duration_s": duration_s(str(dest)),
                "dataset": "CaFE",
                "license": CAFE_LICENSE,
            })
    return clips


def cmd_prepare(args):
    out_wavs = Path(args.out_wavs)
    out_wavs.mkdir(parents=True, exist_ok=True)
    clips = []

    if args.cafe_zip:
        zpath = Path(args.cafe_zip)
        actors = set(args.actors.split(",")) if args.actors else set(DEFAULT_CAFE_ACTORS)

        def want_emotion(m):
            # Sentence 1, selected actors, high intensity (2) + all neutrals.
            if m["actor"] not in actors:
                return False
            if m["sentence"] != "1":
                return False
            if m["emotion"] == "neutral":
                return True
            return m["intensity"] == "2"

        def want_sentences(m):
            # All 6 sentences, selected actors, neutral + one strong (joie, intensité 2).
            if m["actor"] not in actors:
                return False
            if m["emotion"] == "neutral":
                return True
            return m["emotion"] == "happy" and m["intensity"] == "2"

        if not args.skip_cafe_emotion:
            got = extract_cafe(zpath, out_wavs, want_emotion)
            for c in got:
                c["condition"] = "cafe-emotion"
            clips.extend(got)
            log("CaFE émotion (phrase 1) : %d clips" % len(got))
        if not args.skip_cafe_sentences:
            got = extract_cafe(zpath, out_wavs, want_sentences)
            # avoid duplicates already in cafe-emotion (s1 happy/neutral)
            have = {c["id"] for c in clips}
            n = 0
            for c in got:
                if c["id"] in have:
                    # keep the first condition (emotion) for s1
                    continue
                c["condition"] = "cafe-sentences"
                clips.append(c)
                n += 1
            log("CaFE phrases 1–6 (neutre + joie) : %d clips nouveaux" % n)

    if args.wav_dir:
        # Adopt already-converted 16 kHz files if they match cafe-*.wav and we know the text.
        for p in sorted(Path(args.wav_dir).glob("cafe-*.wav")):
            if any(c["id"] == p.stem for c in clips):
                continue
            # cafe-01-angry-2-s1
            parts = p.stem.split("-")
            sent = "1"
            if len(parts) >= 5 and parts[-1].startswith("s"):
                sent = parts[-1][1:]
            text = CAFE_SENTENCES.get(sent)
            if not text:
                continue
            clips.append({
                "id": p.stem,
                "path": str(p.resolve()),
                "text": text,
                "condition": "cafe-existing",
                "language": "fr",
                "duration_s": duration_s(str(p)),
                "dataset": "CaFE",
                "license": CAFE_LICENSE,
            })

    if args.variations and clips:
        # A few speed / gain / pitch variants from a short clear clip (neutral s1).
        bases = [c for c in clips if c.get("condition") == "cafe-emotion" and c.get("emotion") == "neutral"]
        if not bases:
            bases = clips[:2]
        specs = [
            ("speed-0.8", dict(speed=0.8)),
            ("speed-1.3", dict(speed=1.3)),
            ("amp-quiet", dict(gain=0.3)),
            ("pitch-low", dict(pitch_semitones=-3)),
            ("pitch-high", dict(pitch_semitones=3)),
        ]
        n = 0
        for b in bases[:2]:
            for tag, kw in specs:
                cid = "%s-%s" % (b["id"], tag)
                dest = out_wavs / (cid + ".wav")
                vary(b["path"], str(dest), **kw)
                clips.append({
                    "id": cid,
                    "path": str(dest),
                    "text": b["text"],
                    "condition": "variation",
                    "language": "fr",
                    "variation": tag,
                    "source": b["id"],
                    "duration_s": duration_s(str(dest)),
                    "dataset": b.get("dataset"),
                    "license": b.get("license"),
                })
                n += 1
        log("variations (ffmpeg, pas Phonon) : %d clips" % n)

    if args.phrases:
        phrases = json.loads(Path(args.phrases).read_text(encoding="utf-8"))
        # Only used as a list of references; actual Phonon generation is --phonon.
        Path(args.out_manifest).parent.mkdir(parents=True, exist_ok=True)
        (Path(args.out_manifest).parent / "phrases-ref.json").write_text(
            json.dumps(phrases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        log("phrases de référence : %d (génération Phonon : --phonon URL)" % len(phrases))

    if args.phonon:
        # Generate voicetest-style French phrases through Phonon when nimbus is up.
        try:
            phrases = json.loads(Path(args.phrases or HERE.parent / "voicetest" / "phrases.json").read_text(encoding="utf-8"))
        except Exception as e:
            log("Phonon : impossible de lire les phrases (%s)" % e)
            phrases = []
        sys.path.insert(0, str(HERE.parent / "voicetest"))
        try:
            import voicetest as vt
            voice = args.phonon_voice or "Hélène"
            for ph in phrases:
                if ph.get("suite") not in (None, "dialogue", "voice", "emotion"):
                    continue
                text = ph.get("text") or ""
                if not text:
                    continue
                cid = "phonon-%s" % ph["id"]
                dest = out_wavs / (cid + ".wav")
                data, kind = vt.phonon_speech(args.phonon, text, voice, emotion=None)
                dest.write_bytes(data)
                if kind != "wav" or data[:4] != b"RIFF":
                    # try ffmpeg
                    tmp = str(dest) + ".bin"
                    Path(tmp).write_bytes(data)
                    ensure_16k_mono(tmp, str(dest))
                    os.unlink(tmp)
                else:
                    ensure_16k_mono(str(dest), str(dest) + ".16k.wav")
                    os.replace(str(dest) + ".16k.wav", str(dest))
                clips.append({
                    "id": cid,
                    "path": str(dest),
                    "text": text,
                    "condition": "phonon",
                    "language": "fr",
                    "duration_s": duration_s(str(dest)),
                    "dataset": "Phonon",
                    "suite": ph.get("suite"),
                })
            log("Phonon : %d clips" % sum(1 for c in clips if c["condition"] == "phonon"))
        except Exception as e:
            log("Phonon indisponible (%s) — les clips phonon sont sautés." % e)

    if args.chaine_dir:
        # Accept {id}.wav next to a phrases file, or recursive wavs whose stem is the id.
        chaine = Path(args.chaine_dir)
        refs = {}
        if args.phrases:
            for ph in json.loads(Path(args.phrases).read_text(encoding="utf-8")):
                refs[ph["id"]] = ph.get("text") or ""
        for wav in sorted(chaine.rglob("*.wav")):
            text = refs.get(wav.stem) or refs.get(wav.parent.name)
            if not text:
                # Convention : un fichier ref.txt à côté, ou on saute.
                ref_txt = wav.with_suffix(".txt")
                if ref_txt.is_file():
                    text = ref_txt.read_text(encoding="utf-8").strip()
            if not text:
                continue
            dest = out_wavs / ("chaine-%s.wav" % wav.stem)
            ensure_16k_mono(str(wav), str(dest))
            clips.append({
                "id": "chaine-%s" % wav.stem,
                "path": str(dest),
                "text": text,
                "condition": "chaine",
                "language": "fr",
                "duration_s": duration_s(str(dest)),
                "dataset": "chaine",
                "source": str(wav),
                "notes": "Enregistrement micro (chaîne), réalisme acoustique",
            })
        log("chaîne : %d clips" % sum(1 for c in clips if c["condition"] == "chaine"))

    manifest = {
        "created": now_iso(),
        "notes": (
            "Audio non versionné. CaFE CC BY-NC-SA 4.0, acteur 07 exclu. "
            "Les variations ffmpeg ne remplacent pas Phonon (nimbus:8880)."
        ),
        "clips": clips,
    }
    dest = Path(args.out_manifest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log("manifest : %d clips → %s" % (len(clips), dest))
    by = {}
    for c in clips:
        by[c["condition"]] = by.get(c["condition"], 0) + 1
    for k, v in sorted(by.items()):
        print("%s\t%d" % (k, v))
    return 0


# ----- run -------------------------------------------------------------------

def median(xs):
    xs = list(xs)
    if not xs:
        return None
    return statistics.median(xs)


def pick_engines(catalog, ids, extras):
    specs = []
    by_id = {}
    for s in (catalog.get("inventory") or []) + (catalog.get("candidates") or []):
        if s.get("kind") == "optional":
            continue
        by_id[s["id"]] = s
    if ids:
        for i in ids:
            if i not in by_id:
                raise SystemExit("moteur inconnu : %s (connus : %s)" % (i, ", ".join(sorted(by_id))))
            specs.append(dict(by_id[i]))
    if extras:
        for raw in extras:
            specs.append(json.loads(raw))
    if not specs:
        for s in (catalog.get("inventory") or []) + (catalog.get("candidates") or []):
            if s.get("default") and s.get("kind") not in ("optional",):
                specs.append(dict(s))
    return specs


def cmd_run(args):
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    clips = manifest["clips"]
    if args.condition:
        want = set(args.condition.split(","))
        clips = [c for c in clips if c["condition"] in want]
    if args.limit:
        clips = clips[: args.limit]
    if not clips:
        log("aucun clip")
        return 1
    catalog = load_catalog(args.catalog)
    specs = pick_engines(catalog, args.engine, args.engine_json)
    # Resolve relative Vosk / sherpa paths against --model-root.
    root = Path(args.model_root) if args.model_root else Path("models")
    for s in specs:
        for key in ("model", "model_path", "encoder", "decoder", "joiner", "tokens"):
            v = s.get(key)
            if not isinstance(v, str) or os.path.isabs(v):
                continue
            if v.startswith("models/"):
                s[key] = str(root / v[len("models/"):])
            elif key in ("model_path", "encoder", "decoder", "joiner", "tokens"):
                s[key] = str(root / v)

    campaign = {
        "started": now_iso(),
        "host": os.uname().nodename if hasattr(os, "uname") else "",
        "note": args.note or "",
        "warmup": args.warmup,
        "repeats": args.repeats,
        "n_clips": len(clips),
        "engines": [],
        "rows": [],
    }
    for spec in specs:
        eng = Engine(spec)
        entry = {
            "id": eng.id,
            "name": eng.name,
            "kind": eng.kind,
            "license": eng.license,
            "spec": {k: v for k, v in spec.items() if k not in ("encoder",)},
        }
        log("=== %s (%s) ===" % (eng.id, eng.kind))
        try:
            eng.load()
            entry["load_s"] = eng.load_s
            entry["load_rss_mb"] = eng.load_rss_mb
            entry["rss_after_load_mb"] = rss_mb()
            log("  chargé en %.2f s, ΔRSS %.0f Mo (RSS total %.0f Mo)" % (
                eng.load_s or 0, eng.load_rss_mb or 0, rss_mb(),
            ))
        except Exception as e:
            entry["error"] = "%s: %s" % (type(e).__name__, e)
            log("  chargement échoué : %s" % entry["error"])
            campaign["engines"].append(entry)
            continue

        if args.warmup:
            try:
                r = eng.transcribe(clips[0]["path"])
                entry["cold_s"] = r.get("elapsed_s")
                entry["cold_ok"] = r.get("ok")
                log("  warmup (froid) %.2f s ok=%s" % (r.get("elapsed_s") or 0, r.get("ok")))
                if not r.get("ok"):
                    entry["error"] = r.get("error") or "warmup failed"
                    log("  warmup échoué, moteur sauté : %s" % entry["error"])
                    campaign["engines"].append(entry)
                    try:
                        eng.unload()
                    except Exception:
                        pass
                    continue
            except Exception as e:
                entry["cold_error"] = str(e)
                entry["error"] = str(e)
                campaign["engines"].append(entry)
                continue

        scores = []
        scores_fold = []
        times = []
        rtfs = []
        for c in clips:
            result = eng.transcribe(c["path"])
            extra = []
            for _ in range(max(0, args.repeats - 1)):
                extra.append(eng.transcribe(c["path"]))
            warm = [result] + [r for r in extra if r.get("ok")]
            elapsed = median([r["elapsed_s"] for r in warm if r.get("elapsed_s") is not None])
            dur = c.get("duration_s") or duration_s(c["path"])
            rtf = (elapsed / dur) if elapsed and dur else None
            sc = score(c["text"], result.get("text") or "")
            sc_fold = score(c["text"], result.get("text") or "", strip_accents=True)
            scores.append(sc)
            scores_fold.append(sc_fold)
            if result.get("ok") and elapsed is not None:
                times.append(elapsed)
                if rtf is not None:
                    rtfs.append(rtf)
            row = {
                "engine": eng.id,
                "clip": c["id"],
                "condition": c["condition"],
                "ref": c["text"],
                "hyp": result.get("text") or "",
                "ok": result.get("ok"),
                "error": result.get("error"),
                "elapsed_s": result.get("elapsed_s"),
                "elapsed_median_s": elapsed,
                "duration_s": dur,
                "rtf": rtf,
                "wer": sc["wer"],
                "wer_fold": sc_fold["wer"],
                "n": sc["n"],
                "sub": sc["sub"],
                "del": sc["del"],
                "ins": sc["ins"],
                "cut_start": sc["cut_start"],
                "cut_end": sc["cut_end"],
                "extra_start": sc["extra_start"],
                "extra_end": sc["extra_end"],
            }
            campaign["rows"].append(row)
            flag = "ok" if result.get("ok") and sc["wer"] == 0 else (
                "WER=%.2f" % sc["wer"] if result.get("ok") else "ERR"
            )
            log("  %s %s  %s" % (c["id"], flag, (result.get("text") or result.get("error") or "")[:80]))

        summary = aggregate(scores)
        summary_fold = aggregate(scores_fold)
        by_cond = {}
        for row, sc in zip(
            [r for r in campaign["rows"] if r["engine"] == eng.id],
            scores,
        ):
            by_cond.setdefault(row["condition"], []).append(sc)
        entry["wer"] = summary["wer"]
        entry["wer_fold"] = summary_fold["wer"]
        entry["n"] = summary["n"]
        entry["sub"] = summary["sub"]
        entry["del"] = summary["del"]
        entry["ins"] = summary["ins"]
        entry["cut_start_rate"] = summary["cut_start_rate"]
        entry["cut_end_rate"] = summary["cut_end_rate"]
        entry["extra_start_rate"] = summary["extra_start_rate"]
        entry["extra_end_rate"] = summary["extra_end_rate"]
        entry["latency_median_s"] = median(times)
        entry["latency_p95_s"] = (
            statistics.quantiles(times, n=20)[18] if len(times) >= 20 else (max(times) if times else None)
        )
        entry["rtf_median"] = median(rtfs)
        entry["wer_by_condition"] = {
            k: aggregate(v)["wer"] for k, v in by_cond.items()
        }
        entry["rss_after_run_mb"] = rss_mb()
        campaign["engines"].append(entry)
        try:
            eng.unload()
        except Exception:
            pass

    campaign["finished"] = now_iso()
    dest = Path(args.out)
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(campaign, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log("campagne : %s" % dest)
    md = dest.with_suffix(".md")
    md.write_text(render_report(campaign), encoding="utf-8")
    log("rapport : %s" % md)
    print(render_report(campaign))
    return 0


def render_report(campaign):
    lines = []
    a = lines.append
    a("# Rapport banc STT")
    a("")
    a("- Début : %s" % campaign.get("started"))
    a("- Fin : %s" % campaign.get("finished", ""))
    a("- Hôte : `%s`" % campaign.get("host", ""))
    if campaign.get("note"):
        a("- Note : %s" % campaign["note"])
    a("- Clips : %s" % campaign.get("n_clips"))
    a("- Warmup : %s ; répétitions par clip : %s" % (
        campaign.get("warmup"), campaign.get("repeats"),
    ))
    a("")
    a("WER principal : casse et ponctuation ignorées, **accents conservés**, chiffres 0–20 en lettres, élisions séparées. `wer_fold` est la même mesure sans accents. Latence = médiane des passages *chauds* (après warmup). RTF = temps / durée audio. Les temps mesurés sur une autre machine que nimbus ne sont pas comparables.")
    a("")
    a("| moteur | licence | WER | WER fold | coupures début / fin | extra début / fin | latence méd. (s) | RTF méd. | ΔRSS chargement (Mo) | froid (s) |")
    a("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for e in campaign.get("engines") or []:
        if e.get("error") and "wer" not in e:
            a("| `%s` | %s | — | — | — | — | — | — | — | *%s* |" % (
                e["id"], e.get("license") or "", e["error"][:80],
            ))
            continue
        a("| `%s` | %s | %.1f %% | %.1f %% | %.0f %% / %.0f %% | %.0f %% / %.0f %% | %s | %s | %s | %s |" % (
            e["id"],
            e.get("license") or "",
            100 * (e.get("wer") or 0),
            100 * (e.get("wer_fold") or 0),
            100 * (e.get("cut_start_rate") or 0),
            100 * (e.get("cut_end_rate") or 0),
            100 * (e.get("extra_start_rate") or 0),
            100 * (e.get("extra_end_rate") or 0),
            "—" if e.get("latency_median_s") is None else "%.3f" % e["latency_median_s"],
            "—" if e.get("rtf_median") is None else "%.3f" % e["rtf_median"],
            "—" if e.get("load_rss_mb") is None else "%.0f" % e["load_rss_mb"],
            "—" if e.get("cold_s") is None else "%.3f" % e["cold_s"],
        ))
    a("")
    # Per-condition WER
    conds = sorted({r["condition"] for r in campaign.get("rows") or []})
    if conds:
        a("## WER par condition")
        a("")
        a("| moteur | " + " | ".join(conds) + " |")
        a("|" + "---|" * (1 + len(conds)))
        for e in campaign.get("engines") or []:
            cells = [e["id"]]
            by = e.get("wer_by_condition") or {}
            for c in conds:
                cells.append("—" if c not in by else "%.1f %%" % (100 * by[c]))
            a("| " + " | ".join(cells) + " |")
        a("")
    a("## Détail des erreurs")
    a("")
    for e in campaign.get("engines") or []:
        errs = [
            r for r in campaign.get("rows") or []
            if r["engine"] == e["id"] and (not r.get("ok") or (r.get("wer") or 0) > 0)
        ]
        a("### `%s`" % e["id"])
        a("")
        if e.get("error"):
            a("Chargement : `%s`" % e["error"])
            a("")
            continue
        if not errs:
            a("Aucune erreur.")
            a("")
            continue
        a("| clip | condition | WER | référence | hypothèse | coupure |")
        a("|---|---|---:|---|---|---|")
        for r in errs:
            cuts = []
            if r.get("cut_start"):
                cuts.append("début %s" % r["cut_start"])
            if r.get("cut_end"):
                cuts.append("fin %s" % r["cut_end"])
            a("| `%s` | %s | %.2f | %s | %s | %s |" % (
                r["clip"], r["condition"], r.get("wer") or 0,
                (r.get("ref") or "").replace("|", "/"),
                (r.get("hyp") or r.get("error") or "").replace("|", "/"),
                ", ".join(cuts) or "—",
            ))
        a("")
    return "\n".join(lines) + "\n"


def cmd_report(args):
    campaign = json.loads(Path(args.input).read_text(encoding="utf-8"))
    md = render_report(campaign)
    if args.out:
        Path(args.out).write_text(md, encoding="utf-8")
    print(md, end="")
    return 0


# ----- CLI -------------------------------------------------------------------

def build_parser():
    p = argparse.ArgumentParser(description="Banc d'essai STT français (GeeUIVoice)")
    p.add_argument("--catalog", default=str(HERE / "candidates.json"))
    sub = p.add_subparsers(dest="cmd", required=True)

    inv = sub.add_parser("inventory", help="Lister les STT sur nimbus, sans rien modifier")
    inv.add_argument("--base", action="append", help="URL de base supplémentaire (répétable)")
    inv.add_argument("--out", default="inventory.json")
    inv.add_argument("--timeout", type=int, default=8)
    inv.set_defaults(func=cmd_inventory)

    pr = sub.add_parser("prepare", help="Constituer le jeu de test (audio hors git)")
    pr.add_argument("--cafe-zip", help="CaFE_48k.zip (local, pas nimbus si le disque est plein)")
    pr.add_argument("--wav-dir", help="Dossier de WAV 16 kHz déjà convertis (ex. wav16/)")
    pr.add_argument("--actors", default=",".join(DEFAULT_CAFE_ACTORS))
    pr.add_argument("--skip-cafe-emotion", action="store_true")
    pr.add_argument("--skip-cafe-sentences", action="store_true")
    pr.add_argument("--variations", action="store_true", help="speed/gain/pitch ffmpeg à partir des clips CaFE")
    pr.add_argument("--phrases", help="phrases.json voicetest (référence + Phonon)")
    pr.add_argument("--phonon", help="URL Phonon (http://nimbus:8880) pour générer les phrases FR")
    pr.add_argument("--phonon-voice", default="Hélène")
    pr.add_argument("--chaine-dir", help="Enregistrements micro d'une campagne chaîne + texte connu")
    pr.add_argument("--out-wavs", default="wavs")
    pr.add_argument("--out-manifest", default="manifest.json")
    pr.set_defaults(func=cmd_prepare)

    rn = sub.add_parser("run", help="Lancer le banc sur un manifest")
    rn.add_argument("--manifest", required=True)
    rn.add_argument("--engine", action="append", help="id du catalogue (répétable). Défaut : default=true")
    rn.add_argument("--engine-json", action="append", help="spec JSON additionnelle")
    rn.add_argument("--model-root", default="models")
    rn.add_argument("--condition", help="filtrer par condition, séparée par des virgules")
    rn.add_argument("--limit", type=int, default=0)
    rn.add_argument("--warmup", type=int, default=1)
    rn.add_argument("--repeats", type=int, default=1, help="passages par clip (médiane des temps)")
    rn.add_argument("--out", default="out/campaign.json")
    rn.add_argument("--note", default="")
    rn.set_defaults(func=cmd_run)

    rp = sub.add_parser("report", help="Regénérer le Markdown depuis un JSON")
    rp.add_argument("--input", required=True)
    rp.add_argument("--out")
    rp.set_defaults(func=cmd_report)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main() or 0)
