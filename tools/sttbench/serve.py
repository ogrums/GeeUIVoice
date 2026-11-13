#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Petit serveur OpenAI-compatible (/v1/audio/transcriptions) autour d'un moteur local.

Utile pour exposer Vosk / faster-whisper / sherpa-onnx sur un port libre de
nimbus (13310–13314), sans toucher à 8080, 8880, 8900, 13305, 13306, 49112.
Stdlib HTTP + les adapters de engines.py.
"""
from __future__ import print_function

import argparse
import cgi
import json
import os
import sys
import tempfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from engines import Engine  # noqa: E402

PROTECTED = {8080, 8880, 8900, 13305, 13306, 49112}

ENGINE = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("serve: " + (fmt % args) + "\n")

    def _json(self, code, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path in ("/health", "/v1/health"):
            self._json(200, {"status": "ok", "engine": ENGINE.id if ENGINE else None})
            return
        if path in ("/v1/models", "/models"):
            mid = ENGINE.spec.get("model") or ENGINE.id
            self._json(200, {"object": "list", "data": [{"id": mid, "object": "model"}]})
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        path = self.path.split("?")[0]
        if path not in ("/v1/audio/transcriptions", "/audio/transcriptions"):
            self._json(404, {"error": "not found"})
            return
        ctype, pdict = cgi.parse_header(self.headers.get("Content-Type") or "")
        if ctype != "multipart/form-data":
            self._json(400, {"error": "expected multipart/form-data"})
            return
        pdict = {k: v.encode("utf-8") if isinstance(v, str) else v for k, v in pdict.items()}
        try:
            form = cgi.FieldStorage(
                fp=self.rfile,
                headers=self.headers,
                environ={
                    "REQUEST_METHOD": "POST",
                    "CONTENT_TYPE": self.headers.get("Content-Type"),
                    "CONTENT_LENGTH": self.headers.get("Content-Length") or "0",
                },
                keep_blank_values=True,
            )
        except Exception as e:
            self._json(400, {"error": str(e)})
            return
        field = form["file"] if "file" in form else None
        if field is None or not getattr(field, "file", None):
            self._json(400, {"error": "missing audio file"})
            return
        data = field.file.read()
        suffix = ".wav"
        name = getattr(field, "filename", "") or ""
        if "." in name:
            suffix = "." + name.rsplit(".", 1)[-1]
        tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
        try:
            tmp.write(data)
            tmp.close()
            result = ENGINE.transcribe(tmp.name)
        finally:
            try:
                os.unlink(tmp.name)
            except OSError:
                pass
        if not result.get("ok"):
            self._json(500, {"error": result.get("error") or "transcription failed"})
            return
        self._json(200, {"text": result.get("text") or ""})


def main():
    global ENGINE
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine-json", required=True, help="spec JSON d'un moteur local (kind vosk/faster-whisper/sherpa-onnx)")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=13310)
    args = ap.parse_args()
    if args.port in PROTECTED:
        sys.stderr.write("port protégé (%s) — choisir 13310–13314\n" % args.port)
        return 2
    spec = json.loads(args.engine_json) if args.engine_json.lstrip().startswith("{") else json.loads(Path(args.engine_json).read_text())
    ENGINE = Engine(spec)
    sys.stderr.write("chargement %s…\n" % ENGINE.id)
    ENGINE.load()
    sys.stderr.write("écoute %s:%s\n" % (args.host, args.port))
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    sys.exit(main() or 0)
