"""Tests de tools/catalog/embed.py (stdlib seule, sans modèle).

    python3 -I -m unittest discover -s tools/catalog -p embed_test.py
    (ou python3 -I tools/catalog/embed_test.py ; sous -I, « -m unittest <chemin> » ne trouve pas le paquet tools)
"""

import contextlib
import io
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import embed  # noqa: E402


class EmbedTest(unittest.TestCase):
    def test_quantize_bornes_et_longueur(self):
        vec = embed.l2_normalize([math.sin(i / 3) + 0.1 * i for i in range(embed.DIM)])
        b = embed.quantize(vec)
        self.assertEqual(len(b), 256)
        self.assertEqual(len(embed.encode(b)), 344)
        signed = [v - 256 if v > 127 else v for v in b]
        self.assertTrue(all(-127 <= v <= 127 for v in signed))
        # Formule : x / 0.25 * 127, arrondi au plus proche, borné à ±127.
        self.assertEqual(list(embed.quantize([0.25, -0.25, 1.0, -1.0, 0.0])), [127, 129, 127, 129, 0])
        self.assertEqual(embed.quantize([0.125])[0], 64)  # 63.5 -> 64 (moitié vers le haut, comme Math.round)
        self.assertEqual(embed.quantize([-0.125])[0], (-63) & 0xFF)  # -63.5 -> -63
        self.assertEqual(embed.quantize([0.1])[0], round(0.1 / 0.25 * 127))
        # Vecteur unitaire sur un axe : saturé à 127, le reste à 0.
        unit = [1.0] + [0.0] * 255
        self.assertEqual(embed.quantize(unit), bytes([127]) + bytes(255))
        # Aller-retour : écart au plus d'un demi-pas.
        back = embed.dequantize(embed.decode(embed.encode(embed.quantize([0.05, -0.2]))))
        for x, y in zip([0.05, -0.2], back):
            self.assertLessEqual(abs(x - y), 0.25 / 127 / 2 + 1e-12)

    def test_cache_key_stable(self):
        k = embed.cache_key("task: search result | text: Soupe")
        self.assertEqual(k, embed.cache_key("task: search result | text: Soupe"))
        self.assertEqual(len(k), 40)
        import hashlib
        self.assertEqual(k, hashlib.sha1(b"embeddinggemma-2-740m@256\ntask: search result | text: Soupe").hexdigest())
        self.assertNotEqual(k, embed.cache_key("task: search result | text: Soupe", embed.model_id(128)))
        self.assertNotEqual(k, embed.cache_key("task: search result | text: Soupes"))

    def test_passage_text_format(self):
        e = {"title": "Velouté de carottes", "keys": ["carotte", "poireau"]}
        self.assertEqual(embed.passage_text(e), "task: search result | text: Velouté de carottes. Ingrédients : carotte, poireau")
        self.assertEqual(embed.passage_text({"title": "Pain"}), "task: search result | text: Pain")
        self.assertEqual(embed.passage_text({"title": "Pain", "keys": []}), "task: search result | text: Pain")
        self.assertEqual(embed.query_text("  soupe d'hiver "), "task: search query | text: soupe d'hiver")

    def test_dry_run_reutilise_le_cache(self):
        fiche = {"url": "https://ex.fr/r1", "siteId": "ex", "title": "Soupe", "keys": ["poireau"]}
        emb = embed.encode(embed.quantize([0.01] * 256))
        with tempfile.TemporaryDirectory() as d:
            prev, inp = Path(d, "prev"), Path(d, "in")
            prev.mkdir()
            inp.mkdir()
            embed.write_jsonl(prev / "ex.jsonl.gz", [dict(fiche, embedding=emb)])
            autre = dict(fiche, url="https://ex.fr/r2", title="Tarte")
            embed.write_jsonl(inp / "ex.jsonl", [fiche, autre])
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                code = embed.main(["--in", str(inp), "--previous", str(prev), "--dry-run", "--golden", str(Path(d, "g.json"))])
            self.assertEqual(code, 0)
            self.assertIn("réutilisés 1", out.getvalue())
            self.assertIn("à calculer 1", out.getvalue())
            # --dry-run ne réécrit pas les fiches.
            self.assertEqual(embed.read_jsonl(inp / "ex.jsonl"), [fiche, autre])
            self.assertTrue(Path(d, "g.json").exists())

    def test_golden_coherent(self):
        g = json.loads(embed.GOLDEN_PATH.read_text(encoding="utf-8"))
        self.assertEqual(g["modelId"], embed.MODEL_ID)
        self.assertEqual(g["textVersion"], embed.TEXT_VERSION)
        self.assertEqual(g["scale"], embed.SCALE)
        self.assertEqual(g["dim"], embed.DIM)
        self.assertEqual(len(g["float"]), g["dim"])
        self.assertAlmostEqual(math.sqrt(sum(x * x for x in g["float"])), 1.0, places=5)
        self.assertEqual(embed.encode(embed.quantize(g["float"])), g["int8_base64"])
        self.assertEqual(g, embed.golden())
        self.assertEqual(g["passage_example"], embed.passage_text(embed.GOLDEN_ENTRY))
        self.assertEqual(g["query_example"], "task: search query | text: quelque chose de réconfortant")


if __name__ == "__main__":
    unittest.main()
