# -*- coding: utf-8 -*-
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

from normalize import aggregate, align, normalize, score, tokens  # noqa: E402


class NormalizeTest(unittest.TestCase):
    def test_case_punct_and_apostrophe(self):
        self.assertEqual(
            normalize("C'est quoi la météo à Paris aujourd'hui ?"),
            "c' est quoi la météo à paris aujourd'hui",
        )
        self.assertEqual(
            normalize("Comment tu t'appelles ?"),
            "comment tu t' appelles",
        )
        # curly apostrophe
        self.assertEqual(normalize("s\u2019il te plaît."), "s' il te plaît")

    def test_accents_kept_or_folded(self):
        self.assertEqual(normalize("Deux ânes aigris"), "deux ânes aigris")
        self.assertNotEqual(normalize("ânes"), normalize("anes"))
        self.assertEqual(
            normalize("Deux ânes", strip_accents=True),
            normalize("Deux anes", strip_accents=True),
        )

    def test_digits_become_french_words(self):
        self.assertEqual(normalize("2 ânes"), "deux ânes")
        self.assertEqual(normalize("6 ours"), "six ours")

    def test_perfect_wer_is_zero(self):
        s = score("Un cheval fou dans mon jardin", "un cheval fou dans mon jardin.")
        self.assertEqual(s["wer"], 0.0)
        self.assertEqual(s["n"], 6)
        self.assertEqual(s["cut_start"], 0)
        self.assertEqual(s["cut_end"], 0)

    def test_substitution(self):
        s = score("Un cheval fou dans mon jardin", "Un cheval fou dans mon jardin x")
        # trailing insertion
        self.assertEqual(s["ins"], 1)
        self.assertAlmostEqual(s["wer"], 1 / 6)
        self.assertEqual(s["extra_end"], 1)

    def test_cut_start_and_end(self):
        s = score("Un cheval fou dans mon jardin", "cheval fou dans mon")
        self.assertEqual(s["cut_start"], 1)
        self.assertEqual(s["cut_end"], 1)
        self.assertEqual(s["del"], 2)
        self.assertAlmostEqual(s["wer"], 2 / 6)

    def test_total_miss(self):
        s = score("bonjour", "")
        self.assertEqual(s["wer"], 1.0)
        self.assertEqual(s["del"], 1)

    def test_empty_ref_with_hyp(self):
        s = score("", "bonjour")
        self.assertEqual(s["wer"], 1.0)
        self.assertEqual(s["ins"], 1)

    def test_cafe_sentence_four(self):
        ref = "Quatre vieilles truies éléphantesques"
        self.assertEqual(len(tokens(ref)), 4)
        s = score(ref, "quatre vieilles truies elephantesques")
        # accent-sensitive: éléphantesques ≠ elephantesques
        self.assertEqual(s["sub"], 1)
        s2 = score(ref, "quatre vieilles truies elephantesques", strip_accents=True)
        self.assertEqual(s2["wer"], 0.0)

    def test_aggregate_micro_average(self):
        rows = [
            score("a b c", "a b c"),
            score("a b", "a"),
        ]
        a = aggregate(rows)
        # (0 + 1 del) / (3+2) = 0.2
        self.assertAlmostEqual(a["wer"], 0.2)
        self.assertEqual(a["utterances"], 2)
        self.assertEqual(a["cut_end_n"], 1)


if __name__ == "__main__":
    unittest.main()
