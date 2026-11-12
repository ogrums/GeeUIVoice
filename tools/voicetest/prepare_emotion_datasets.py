#!/usr/bin/env python3
"""Prépare un sous-ensemble CaFE (français) et/ou RAVDESS (anglais) pour voicetest.

Les archives ne sont pas dans le dépôt (licences CC BY-NC-SA 4.0, usage non commercial).
Téléchargez-les vous-même, puis :

    python prepare_emotion_datasets.py \\
        --cafe-zip CaFE_48k.zip \\
        --ravdess-zip Audio_Speech_Actors_01-24.zip \\
        --out-wavs wavs --out-phrases .

CaFE (v1.1) : https://doi.org/10.5281/zenodo.1478765
    Gournay, Lahaie, Lefebvre (2018), ACM MMSys.
    Phrase 1 : « Un cheval fou dans mon jardin ».
    Fichier AA-E-I-S.wav : acteur (impair = homme), émotion
    C=colère, D=dégoût, J=joie, N=neutre, P=peur, S=surprise, T=tristesse,
    intensité 1=faible 2=fort, phrase 1–6.
    L'acteur 07 (15 ans) est exclu par défaut.

RAVDESS v1.0.0 : https://doi.org/10.5281/zenodo.1188976
    Livingstone & Russo (2018), PLoS ONE. Parole seule, ~208 Mo.
    03-01-EE-II-SS-RR-AA.wav : émotion 01=neutre 02=calme 03=joie 04=triste
    05=colère 06=peur 07=dégoût 08=surprise ; intensité 01=normale 02=forte
    (pas de forte pour neutre) ; phrase 01 = « Kids are talking by the door ».
    Acteurs impairs = hommes.

Les phrases générées n'ont PAS de champ « emotion » : ce champ déclencherait
la post-prosodie artificielle de voicetest, qu'on veut éviter ici.

L'audio n'est jamais écrit dans le dépôt. Ne commitez pas les WAV.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

CAFE_EMO = {
    "C": "angry", "D": "disgust", "J": "happy", "N": "neutral",
    "P": "fear", "S": "surprise", "T": "sad",
}
RAV_EMO = {
    "01": "neutral", "02": "calm", "03": "happy", "04": "sad",
    "05": "angry", "06": "fear", "07": "disgust", "08": "surprise",
}
FACE = {
    "happy": "h0006", "sad": "h0119", "angry": "h0001",
    "fear": "h0133", "surprise": "h0046", "neutral": "h0059",
    "disgust": "h0033", "disgusted": "h0033", "calm": "h0059",
}
# Sidecar keeps the ONNX label `disgusted` (face h0033 抗拒). calm → neutral.
APP_EXPECT = {"calm": "neutral", "disgust": "disgusted"}
SENTENCES_FR = {
    "1": ("Un cheval fou dans mon jardin", ["cheval", "jardin"]),
}
SENT_EN = ("Kids are talking by the door", ["kids", "door"])
# Acteur CaFE 07 = 15 ans : exclu.
DEFAULT_CAFE_ACTORS = ["01", "02", "09", "12"]
DEFAULT_RAV_ACTORS = ["01", "02", "13", "14"]
SKIP_CAFE_MINORS = {"07"}


def convert(src: Path, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        "ffmpeg", "-y", "-i", str(src),
        "-ar", "16000", "-ac", "1", "-acodec", "pcm_s16le", str(dest),
    ]
    subprocess.check_call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def extract_matching(zpath: Path, dest_dir: Path, predicate) -> list[Path]:
    dest_dir.mkdir(parents=True, exist_ok=True)
    out = []
    with zipfile.ZipFile(zpath) as z:
        for info in z.infolist():
            base = info.filename.replace("\\", "/").split("/")[-1]
            if not predicate(base):
                continue
            target = dest_dir / base
            with z.open(info) as src, open(target, "wb") as dst:
                shutil.copyfileobj(src, dst)
            out.append(target)
    return out


def phrase(pid, text, heard, dataset, lang, actor, gender, gold, intensity, notes):
    expect = APP_EXPECT.get(gold, gold)
    return {
        "id": pid,
        "suite": "emotion",
        "text": text,
        "expect_heard": heard,
        "expect_audio_emotion": expect,
        "expect_face_code": FACE.get(expect, "h0059"),
        "speed": 1.0,
        "gain": 1.0,
        "pitch_semitones": 0,
        "dataset": dataset,
        "language": lang,
        "actor": actor,
        "gender": gender,
        "gold_emotion": gold,
        "intensity": intensity,
        "notes": notes,
        # Pas de champ "emotion" : il activerait la post-prosodie artificielle.
    }


def prepare_cafe(zpath: Path, raw_dir: Path, wav_dir: Path, actors: list[str], sentence: str, intensity: str):
    actors = [a for a in actors if a not in SKIP_CAFE_MINORS]

    def pred(base):
        m = re.match(r"^(\d{2})-([A-Z])(?:-(\d))?-(\d)\.wav$", base)
        if not m:
            return False
        actor, emo, inten, sent = m.group(1), m.group(2), m.group(3), m.group(4)
        if actor not in actors or sent != sentence:
            return False
        if emo == "N":
            return True
        return inten == intensity

    files = extract_matching(zpath, raw_dir, pred)
    items = []
    for src in sorted(files):
        parts = src.stem.split("-")
        actor, emo = parts[0], parts[1]
        if emo == "N":
            inten, sent = "n", parts[-1]
        else:
            inten, sent = parts[2], parts[3]
        gold = CAFE_EMO[emo]
        gender = "M" if int(actor) % 2 == 1 else "F"
        text, heard = SENTENCES_FR[sent]
        pid = f"cafe-{actor}-{gold}-{inten}-s{sent}"
        convert(src, wav_dir / f"{pid}.wav")
        items.append(phrase(
            pid, text, heard, "CaFE", "fr", actor, gender, gold,
            "fort" if inten == "2" else "neutre",
            "CaFE v1.1 (Gournay et al., 2018), CC BY-NC-SA 4.0, https://doi.org/10.5281/zenodo.1478765",
        ))
    return items


def prepare_ravdess(zpath: Path, raw_dir: Path, wav_dir: Path, actors: list[str], stmt: str, intensity: str):
    def pred(base):
        m = re.match(r"^03-01-(\d{2})-(\d{2})-(\d{2})-(\d{2})-(\d{2})\.wav$", base)
        if not m:
            return False
        emo, inten, st, rep, actor = m.groups()
        if actor not in actors or st != stmt or rep != "01":
            return False
        if emo == "01":
            return inten == "01"
        return inten == intensity

    files = extract_matching(zpath, raw_dir, pred)
    items = []
    for src in sorted(files):
        parts = src.stem.split("-")
        emo, inten, st, _rep, actor = parts[2], parts[3], parts[4], parts[5], parts[6]
        gold = RAV_EMO[emo]
        gender = "M" if int(actor) % 2 == 1 else "F"
        text, heard = SENT_EN
        pid = f"ravdess-{actor}-{gold}-i{inten}-s{st}"
        convert(src, wav_dir / f"{pid}.wav")
        items.append(phrase(
            pid, text, heard, "RAVDESS", "en", actor, gender, gold,
            "strong" if inten == "02" else "normal",
            "RAVDESS v1.0.0 (Livingstone & Russo, 2018), CC BY-NC-SA 4.0, https://doi.org/10.5281/zenodo.1188976",
        ))
    return items


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cafe-zip", type=Path, help="CaFE_48k.zip (Zenodo 1478765)")
    ap.add_argument("--ravdess-zip", type=Path, help="Audio_Speech_Actors_01-24.zip (Zenodo 1188976)")
    ap.add_argument("--out-wavs", type=Path, default=Path(__file__).resolve().parent / "wavs")
    ap.add_argument("--out-phrases", type=Path, default=Path(__file__).resolve().parent)
    ap.add_argument("--work-dir", type=Path, default=Path(__file__).resolve().parent / "_datasets")
    ap.add_argument("--cafe-actors", default=",".join(DEFAULT_CAFE_ACTORS))
    ap.add_argument("--ravdess-actors", default=",".join(DEFAULT_RAV_ACTORS))
    args = ap.parse_args(argv)
    if not args.cafe_zip and not args.ravdess_zip:
        ap.error("indiquez --cafe-zip et/ou --ravdess-zip")
    if not shutil.which("ffmpeg"):
        print("ffmpeg est requis pour convertir en 16 kHz mono", file=sys.stderr)
        return 2

    args.out_wavs.mkdir(parents=True, exist_ok=True)
    args.out_phrases.mkdir(parents=True, exist_ok=True)
    all_items = []
    if args.cafe_zip:
        cafe = prepare_cafe(
            args.cafe_zip, args.work_dir / "cafe-raw", args.out_wavs,
            [a.strip() for a in args.cafe_actors.split(",") if a.strip()],
            "1", "2",
        )
        (args.out_phrases / "phrases-cafe.json").write_text(
            json.dumps(cafe, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
        )
        all_items.extend(cafe)
        print("CaFE : %d phrases" % len(cafe))
    if args.ravdess_zip:
        rav = prepare_ravdess(
            args.ravdess_zip, args.work_dir / "ravdess-raw", args.out_wavs,
            [a.strip() for a in args.ravdess_actors.split(",") if a.strip()],
            "01", "02",
        )
        (args.out_phrases / "phrases-ravdess.json").write_text(
            json.dumps(rav, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
        )
        all_items.extend(rav)
        print("RAVDESS : %d phrases" % len(rav))

    core = {"happy", "sad", "angry", "neutral"}
    chain = [p for p in all_items if p["dataset"] == "CaFE" and p["gold_emotion"] in core and p["actor"] in ("01", "02")]
    if chain:
        (args.out_phrases / "phrases-chain-fr.json").write_text(
            json.dumps(chain, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
        )
        print("chaîne FR : %d phrases" % len(chain))
    (args.out_phrases / "phrases-emotion-real.json").write_text(
        json.dumps(all_items, ensure_ascii=False, indent=2) + "\n", encoding="utf-8",
    )
    print("WAV 16 kHz dans %s" % args.out_wavs)
    print("Ensuite, depuis tools/voicetest :")
    print("  python voicetest.py --mode wav --suite emotion --phrases phrases-cafe.json --wav-dir wavs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
