# -*- coding: utf-8 -*-
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))

import wavutil  # noqa: E402
import sttbench  # noqa: E402


class DummyRunTest(unittest.TestCase):
    def test_perfect_and_cut_and_report(self):
        tmp = Path(tempfile.mkdtemp(prefix="sttbench-"))
        wavs = tmp / "wavs"
        wavs.mkdir()
        a = wavs / "a.wav"
        b = wavs / "b.wav"
        wavutil.write_silence(str(a), 0.4)
        wavutil.write_silence(str(b), 0.4)
        manifest = {
            "clips": [
                {"id": "a", "path": str(a), "text": "Un cheval fou dans mon jardin", "condition": "cafe-emotion", "duration_s": 0.4},
                {"id": "b", "path": str(b), "text": "Un cheval fou dans mon jardin", "condition": "cafe-sentences", "duration_s": 0.4},
            ]
        }
        man = tmp / "manifest.json"
        man.write_text(json.dumps(manifest), encoding="utf-8")
        script = {
            "a.wav": "Un cheval fou dans mon jardin",
            "b.wav": "cheval fou dans mon jardin",  # cut start
        }
        out = tmp / "campaign.json"
        rc = sttbench.main([
            "run",
            "--manifest", str(man),
            "--engine-json", json.dumps({
                "id": "dummy",
                "kind": "dummy",
                "script": script,
                "license": "test",
            }),
            "--warmup", "0",
            "--out", str(out),
            "--note", "unit test",
        ])
        self.assertEqual(rc, 0)
        camp = json.loads(out.read_text())
        self.assertEqual(len(camp["rows"]), 2)
        self.assertEqual(camp["rows"][0]["wer"], 0.0)
        self.assertGreater(camp["rows"][1]["wer"], 0.0)
        self.assertEqual(camp["rows"][1]["cut_start"], 1)
        self.assertTrue((tmp / "campaign.md").is_file())
        md = (tmp / "campaign.md").read_text(encoding="utf-8")
        self.assertIn("dummy", md)
        self.assertIn("WER", md)

    def test_inventory_records_unreachable(self):
        tmp = Path(tempfile.mkdtemp(prefix="sttbench-inv-"))
        out = tmp / "inv.json"
        rc = sttbench.main([
            "inventory",
            "--base", "http://127.0.0.1:1",
            "--out", str(out),
            "--timeout", "1",
        ])
        self.assertEqual(rc, 0)
        inv = json.loads(out.read_text())
        self.assertTrue(inv["endpoints"])
        self.assertFalse(inv["endpoints"][-1]["reachable"])


if __name__ == "__main__":
    unittest.main()
