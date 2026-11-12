# -*- coding: utf-8 -*-
"""Normalisation française et WER, sans dépendance externe.

La métrique principale conserve les accents (ânes ≠ anes). Une seconde
passe sans accents (wer_fold) est rapportée à titre indicatif. La
ponctuation et la casse sont ignorées. Les élisions (c'est, t'appelles)
deviennent deux jetons : « c' » + « est ». Les chiffres 0–20 sont
convertis en lettres (un modèle qui écrit « 2 ânes » n'est pas pénalisé).
"""
from __future__ import print_function

import unicodedata
import re

APOSTROPHES = {
    "\u2019": "'",  # ’
    "\u2018": "'",  # ‘
    "`": "'",
    "\u00b4": "'",  # ´
    "\u02bc": "'",
}

# 0–20 : assez pour les phrases CaFE (deux…six) et les petites variantes.
DIGITS_FR = {
    "0": "zéro",
    "1": "un",
    "2": "deux",
    "3": "trois",
    "4": "quatre",
    "5": "cinq",
    "6": "six",
    "7": "sept",
    "8": "huit",
    "9": "neuf",
    "10": "dix",
    "11": "onze",
    "12": "douze",
    "13": "treize",
    "14": "quatorze",
    "15": "quinze",
    "16": "seize",
    "17": "dix-sept",
    "18": "dix-huit",
    "19": "dix-neuf",
    "20": "vingt",
}

# Word chars plus French apostrophe. \w is Unicode-aware in Python 3.
_KEEP = re.compile(r"[^\w']+", re.UNICODE)
_SPACES = re.compile(r"\s+")


def _fold_accents(text):
    decomposed = unicodedata.normalize("NFD", text)
    return "".join(c for c in decomposed if unicodedata.category(c) != "Mn")


def normalize(text, strip_accents=False, map_digits=True):
    """Lowercase, NFC, no punctuation, optional accent folding, digit words."""
    if text is None:
        text = ""
    text = unicodedata.normalize("NFC", text)
    for src, dst in APOSTROPHES.items():
        text = text.replace(src, dst)
    text = text.lower().replace("\u00a0", " ").replace("\u202f", " ")
    if strip_accents:
        text = _fold_accents(text)
    text = _KEEP.sub(" ", text)
    # Élisions courantes en deux jetons (c'est → c' est). On laisse
    # « aujourd'hui » intact : ce n'est pas une élision productive.
    text = re.sub(
        r"\b(l|d|j|n|c|s|t|m|qu|lorsqu|puisqu|jusqu)'\s*",
        r"\1' ",
        text,
    )
    text = _SPACES.sub(" ", text).strip()
    if map_digits:
        out = []
        for tok in text.split():
            # "2" or "02"
            key = tok.lstrip("0") or "0" if tok.isdigit() else tok
            if tok.isdigit() and key in DIGITS_FR:
                out.append(DIGITS_FR[key])
            else:
                out.append(tok)
        text = " ".join(out)
    return text


def tokens(text, strip_accents=False, map_digits=True):
    n = normalize(text, strip_accents=strip_accents, map_digits=map_digits)
    return n.split() if n else []


def align(ref, hyp):
    """Levenshtein alignment. Returns (ops, substitutions, deletions, insertions).

    ops is a list of (op, ref_tok_or_None, hyp_tok_or_None) in order,
    op in {'=', 'sub', 'del', 'ins'}.
    """
    n, m = len(ref), len(hyp)
    # dp[i][j] = cost; 1-row would lose backtrace.
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    bt = [[None] * (m + 1) for _ in range(n + 1)]
    for i in range(1, n + 1):
        dp[i][0] = i
        bt[i][0] = "del"
    for j in range(1, m + 1):
        dp[0][j] = j
        bt[0][j] = "ins"
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if ref[i - 1] == hyp[j - 1]:
                dp[i][j] = dp[i - 1][j - 1]
                bt[i][j] = "="
            else:
                sub = dp[i - 1][j - 1] + 1
                dele = dp[i - 1][j] + 1
                ins = dp[i][j - 1] + 1
                best = sub
                op = "sub"
                if dele < best:
                    best, op = dele, "del"
                if ins < best:
                    best, op = ins, "ins"
                dp[i][j] = best
                bt[i][j] = op
    ops = []
    i, j = n, m
    s = d = ins = 0
    while i > 0 or j > 0:
        op = bt[i][j]
        if op == "=" or op == "sub":
            ops.append((op, ref[i - 1], hyp[j - 1]))
            if op == "sub":
                s += 1
            i -= 1
            j -= 1
        elif op == "del":
            ops.append(("del", ref[i - 1], None))
            d += 1
            i -= 1
        else:
            ops.append(("ins", None, hyp[j - 1]))
            ins += 1
            j -= 1
    ops.reverse()
    return ops, s, d, ins


def score(ref_text, hyp_text, strip_accents=False, map_digits=True):
    """WER and edge-cut diagnostics for one pair."""
    ref = tokens(ref_text, strip_accents=strip_accents, map_digits=map_digits)
    hyp = tokens(hyp_text, strip_accents=strip_accents, map_digits=map_digits)
    n = len(ref)
    ops, s, d, ins = align(ref, hyp)
    wer = 0.0 if n == 0 and not hyp else (1.0 if n == 0 else (s + d + ins) / float(n))
    # Leading / trailing deletions (cut) and insertions (hallucinated edges).
    lead = 0
    for op, _, _ in ops:
        if op == "del":
            lead += 1
        elif op == "=":
            break
        else:
            break
    trail = 0
    for op, _, _ in reversed(ops):
        if op == "del":
            trail += 1
        elif op == "=":
            break
        else:
            break
    lead_ins = 0
    for op, _, _ in ops:
        if op == "ins":
            lead_ins += 1
        elif op == "=":
            break
        else:
            break
    trail_ins = 0
    for op, _, _ in reversed(ops):
        if op == "ins":
            trail_ins += 1
        elif op == "=":
            break
        else:
            break
    return {
        "wer": wer,
        "n": n,
        "sub": s,
        "del": d,
        "ins": ins,
        "ref_tokens": ref,
        "hyp_tokens": hyp,
        "cut_start": lead,
        "cut_end": trail,
        "extra_start": lead_ins,
        "extra_end": trail_ins,
        "ops": ops,
    }


def aggregate(rows):
    """Micro-average WER over already-scored rows (each has n, sub, del, ins)."""
    n = sub = dele = ins = 0
    cuts_s = cuts_e = 0
    extras_s = extras_e = 0
    for r in rows:
        n += r["n"]
        sub += r["sub"]
        dele += r["del"]
        ins += r["ins"]
        if r["cut_start"]:
            cuts_s += 1
        if r["cut_end"]:
            cuts_e += 1
        if r["extra_start"]:
            extras_s += 1
        if r["extra_end"]:
            extras_e += 1
    wer = 0.0 if n == 0 else (sub + dele + ins) / float(n)
    k = len(rows) or 1
    return {
        "wer": wer,
        "n": n,
        "sub": sub,
        "del": dele,
        "ins": ins,
        "utterances": len(rows),
        "cut_start_rate": cuts_s / float(k),
        "cut_end_rate": cuts_e / float(k),
        "extra_start_rate": extras_s / float(k),
        "extra_end_rate": extras_e / float(k),
        "cut_start_n": cuts_s,
        "cut_end_n": cuts_e,
    }
