#!/usr/bin/env python3
"""Vectorisation du catalogue local : remplit le champ `embedding` des fiches `<site>.jsonl`.

Usage (toujours avec `-I`, depuis un dossier différent de celui des données) :

    python3 -I tools/catalog/embed.py --in <dir> [--model <file.litertlm>] [--previous <dir>]
        [--sites a,b] [--batch 32] [--dim 256] [--threads N] [--dry-run] [--limit N] [--golden <file>]

Contrat partagé avec Kotlin (`core/.../catalog/Vectors.kt`, `EmbeddingText`) :
- passage : "task: search result | text: {title}. Ingrédients : {keys joints par ', '}"
  (fiche sans `keys` : "task: search result | text: {title}") ;
- requête : "task: search query | text: {texte sans espaces autour}" ;
- vecteur : `--dim` (256) dimensions (Matryoshka), L2-normalisé, quantifié INT8 signé par
  q = clamp(round_half_up(x / SCALE * 127), -127, 127) avec SCALE = 0.25 (arrondi comme `Math.round` de Kotlin),
  encodé en Base64 standard (256 octets -> 344 caractères).

Cache incrémental : les fiches de `--previous` (mêmes noms de site, `.jsonl` ou `.jsonl.gz`) qui ont un
`embedding` sont indexées par `cache_key(passage_text(fiche))` = sha1(MODEL_ID + "\\n" + texte) ; une fiche dont
la clé coïncide reprend le vecteur sans calcul. Sans fichier précédent pour un site, une fiche qui porte déjà un
`embedding` le garde (compté « réutilisé ») ; avec un fichier précédent, seul le cache fait foi.

Modèle : `embeddinggemma-2-740m.litertlm` (Hugging Face `litert-community/embeddinggemma-2-740m-litert-lm`).

API du paquet `litert-lm` constatée (version 0.19.2, roue py3-none-any embarquant `liblitert-lm.so` x86_64,
module `litert_lm`, testée sous Linux x86_64 / Python 3.13) :
- `litert_lm.EmbeddingEngine(model_path, backend=litert_lm.Backend.CPU(thread_count=None), cache_dir=None,
  min_input_length=None, max_input_length=None, ...)` ; gestionnaire de contexte, `close()`.
  Il n'y a pas de classe `EmbeddingEngineConfig` : les réglages sont des arguments du constructeur.
- `engine.compute_embedding(contents, options=EmbeddingOptions()) -> EmbeddingResponse` : `contents` est une
  chaîne ou une liste de morceaux formant UNE seule entrée (pas un lot).
- `engine.compute_embedding_batch(contents_batch, options) -> list[EmbeddingResponse]` : un résultat par entrée.
- `litert_lm.EmbeddingOptions(*, normalize=None, insert_special_tokens=None, input_overflow_strategy=None,
  output_size=None, vision_tokens_per_image=None)` (dataclass figée, arguments nommés).
- `litert_lm.EmbeddingResponse.embedding : list[float]`.
- `litert_lm.InputOverflowStrategy` : CHUNK_AND_AVERAGE, TRUNCATE, ERROR.
- `litert_lm.set_min_log_severity(litert_lm.LogSeverity.…)` pour faire taire les journaux natifs.

Dépendances : stdlib seule ; `litert-lm` (tools/catalog/requirements-embed.txt) pour le mode réel uniquement.
"""

from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import io
import json
import math
import os
import sys
import tempfile
import time
from pathlib import Path

MODEL_NAME = "embeddinggemma-2-740m"
DIM = 256
MODEL_ID = f"{MODEL_NAME}@{DIM}"
TEXT_VERSION = 1
SCALE = 0.25

GOLDEN_PATH = Path(__file__).resolve().parent / "embedder-golden.json"
GOLDEN_ENTRY = {
    "title": "Velouté de carottes aux poireaux et au curry doux",
    "keys": ["carotte", "poireau", "oignon", "creme", "pain de campagne", "persil plat", "graine de courge"],
}
GOLDEN_QUERY = "quelque chose de réconfortant"


def model_id(dim: int = DIM) -> str:
    return f"{MODEL_NAME}@{dim}"


# --- Fonctions pures -------------------------------------------------------------------------------------------


def passage_text(entry: dict) -> str:
    title = entry.get("title") or ""
    keys = entry.get("keys") or []
    if not keys:
        return f"task: search result | text: {title}"
    return f"task: search result | text: {title}. Ingrédients : {', '.join(keys)}"


def query_text(s: str) -> str:
    return f"task: search query | text: {s.strip()}"


def l2_normalize(vec) -> list[float]:
    norm = math.sqrt(sum(x * x for x in vec))
    if norm == 0.0:
        return [0.0 for _ in vec]
    return [x / norm for x in vec]


def quantize(vec: list[float]) -> bytes:
    """INT8 signé : q = clamp(round(x / SCALE * 127), -127, 127), arrondi au plus proche, moitiés vers +inf."""
    out = bytearray()
    for x in vec:
        q = math.floor(x / SCALE * 127 + 0.5)
        q = max(-127, min(127, q))
        out.append(q & 0xFF)
    return bytes(out)


def dequantize(b: bytes) -> list[float]:
    return [(v - 256 if v > 127 else v) * SCALE / 127 for v in b]


def encode(b: bytes) -> str:
    return base64.b64encode(b).decode("ascii")


def decode(s: str) -> bytes:
    return base64.b64decode(s)


def cache_key(text: str, mid: str = MODEL_ID) -> str:
    return hashlib.sha1((mid + "\n" + text).encode("utf-8")).hexdigest()


def _is_gzip(path: Path) -> bool:
    with open(path, "rb") as f:
        return f.read(2) == b"\x1f\x8b"


def read_jsonl(path) -> list[dict]:
    """Lit un `.jsonl` ou `.jsonl.gz` (compression reconnue à la signature, pas au nom)."""
    path = Path(path)
    opener = gzip.open if _is_gzip(path) else open
    entries = []
    with opener(path, "rt", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                entries.append(json.loads(line))
    return entries


def write_jsonl(path, entries, compress: bool | None = None) -> None:
    """Écrit en place : fichier temporaire dans le même dossier puis rename (atomique)."""
    path = Path(path)
    if compress is None:
        compress = path.name.endswith(".gz")
    fd, tmp = tempfile.mkstemp(prefix="." + path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as raw:
            stream = gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) if compress else raw
            with io.TextIOWrapper(stream, encoding="utf-8", newline="\n") as f:
                for e in entries:
                    f.write(json.dumps(e, ensure_ascii=False, separators=(",", ":")))
                    f.write("\n")
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


def golden(dim: int = DIM) -> dict:
    # Flottants ramenés en float32 (via struct) : Kotlin les relit sans écart de représentation.
    import struct

    raw = l2_normalize([math.sin(i / 7) for i in range(dim)])
    floats = [struct.unpack("f", struct.pack("f", x))[0] for x in raw]
    return {
        "modelId": model_id(dim),
        "textVersion": TEXT_VERSION,
        "scale": SCALE,
        "dim": dim,
        "float": floats,
        "int8_base64": encode(quantize(floats)),
        "passage_example": passage_text(GOLDEN_ENTRY),
        "query_example": query_text(GOLDEN_QUERY),
    }


# --- Fichiers et cache ------------------------------------------------------------------------------------------


def site_files(directory: Path) -> dict[str, Path]:
    """`<site>.jsonl` ou `<site>.jsonl.gz` -> chemin (le `.jsonl` l'emporte si les deux existent)."""
    found: dict[str, Path] = {}
    for p in sorted(Path(directory).iterdir()):
        if not p.is_file():
            continue
        if p.name.endswith(".jsonl.gz"):
            found.setdefault(p.name[: -len(".jsonl.gz")], p)
        elif p.name.endswith(".jsonl"):
            found[p.name[: -len(".jsonl")]] = p
    return found


def load_cache(path: Path | None, mid: str) -> dict[str, str]:
    cache: dict[str, str] = {}
    if path is None:
        return cache
    for e in read_jsonl(path):
        emb = e.get("embedding")
        if emb:
            cache[cache_key(passage_text(e), mid)] = emb
    return cache


# --- Moteur -----------------------------------------------------------------------------------------------------


class Embedder:
    """Enveloppe `litert_lm.EmbeddingEngine` : lots, repli entrée par entrée en cas d'erreur, quantification."""

    def __init__(self, model: str, dim: int, threads: int | None = None):
        import litert_lm  # dépendance du seul mode réel

        try:
            litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)
        except Exception:  # noqa: BLE001 - journaux natifs : simple confort
            pass
        self.dim = dim
        self.engine = litert_lm.EmbeddingEngine(model, backend=litert_lm.Backend.CPU(thread_count=threads))
        self.options = litert_lm.EmbeddingOptions(normalize=True, output_size=dim)
        self.has_batch = hasattr(self.engine, "compute_embedding_batch")

    def _to_b64(self, values) -> str:
        if len(values) != self.dim:
            raise ValueError(f"vecteur de {len(values)} dimensions, {self.dim} attendues")
        return encode(quantize(l2_normalize(values)))

    def embed(self, texts: list[str]) -> list[str | None]:
        """Un Base64 par texte ; None quand le moteur échoue sur ce texte (journalisé)."""
        if self.has_batch and len(texts) > 1:
            try:
                responses = self.engine.compute_embedding_batch(texts, self.options)
                if len(responses) == len(texts):
                    return [self._to_b64(r.embedding) for r in responses]
                log(f"lot : {len(responses)} réponses pour {len(texts)} textes, repli texte par texte")
            except Exception as ex:  # noqa: BLE001
                log(f"lot en échec ({ex}), repli texte par texte")
        out: list[str | None] = []
        for t in texts:
            try:
                out.append(self._to_b64(self.engine.compute_embedding(t, self.options).embedding))
            except Exception as ex:  # noqa: BLE001
                log(f"échec sur « {t[:80]} » : {ex}")
                out.append(None)
        return out

    def close(self):
        self.engine.close()


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


# --- Traitement d'un site ---------------------------------------------------------------------------------------


def process_site(site, path, previous_path, mid, embedder, batch, limit, dry_run):
    t0 = time.monotonic()
    entries = read_jsonl(path)
    cache = load_cache(previous_path, mid)
    reused = 0
    todo: list[int] = []
    for i, e in enumerate(entries):
        hit = cache.get(cache_key(passage_text(e), mid))
        if hit:
            if e.get("embedding") != hit:
                e["embedding"] = hit
            reused += 1
        elif e.get("embedding") and previous_path is None:
            reused += 1  # pas de catalogue précédent pour ce site : le vecteur présent fait foi
        else:
            e.pop("embedding", None)  # texte changé depuis le calcul : le vecteur ne vaut plus
            todo.append(i)
    pending = len(todo)
    if limit is not None:
        todo = todo[:limit]
    computed = failed = 0
    if not dry_run and todo:
        for start in range(0, len(todo), batch):
            idx = todo[start : start + batch]
            vectors = embedder.embed([passage_text(entries[i]) for i in idx])
            for i, v in zip(idx, vectors):
                if v is None:
                    failed += 1
                else:
                    entries[i]["embedding"] = v
                    computed += 1
    if not dry_run:
        write_jsonl(path, entries, compress=_is_gzip(path))
    return {
        "site": site, "total": len(entries), "reused": reused, "computed": computed, "failed": failed,
        "pending": pending - computed, "seconds": time.monotonic() - t0,
    }


def summary_line(r: dict, dry_run: bool) -> str:
    if dry_run:
        return f"{r['site']} : {r['total']} fiches, réutilisés {r['reused']}, à calculer {r['pending']}"
    rate = r["computed"] / r["seconds"] if r["seconds"] > 0 else 0.0
    return (f"{r['site']} : {r['total']} fiches, réutilisés {r['reused']}, calculés {r['computed']}, "
            f"échecs {r['failed']}, restants {r['pending']}, {r['seconds']:.1f} s ({rate:.1f} fiches/s)")


def write_report(directory: Path, results: list[dict], mid: str, model: str | None) -> None:
    lines = [
        "# Vectorisation du catalogue", "",
        f"- Modèle : `{mid}` ({Path(model).name if model else '—'}), texte v{TEXT_VERSION}, INT8 (échelle {SCALE})",
        "", "| Site | Fiches | Réutilisés | Calculés | Échecs | Restants | Secondes |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for r in results:
        lines.append(f"| {r['site']} | {r['total']} | {r['reused']} | {r['computed']} | {r['failed']} "
                     f"| {r['pending']} | {r['seconds']:.1f} |")
    t = {k: sum(r[k] for r in results) for k in ("total", "reused", "computed", "failed", "pending", "seconds")}
    lines.append(f"| **Total** | {t['total']} | {t['reused']} | {t['computed']} | {t['failed']} "
                 f"| {t['pending']} | {t['seconds']:.1f} |")
    (directory / "embed-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Remplit le champ embedding des fiches <site>.jsonl du catalogue.")
    ap.add_argument("--in", dest="inp", required=True, help="dossier des <site>.jsonl, réécrits en place")
    ap.add_argument("--model", help="fichier .litertlm (obligatoire hors --dry-run)")
    ap.add_argument("--previous", help="dossier du catalogue précédent (cache des vecteurs)")
    ap.add_argument("--sites", help="sites à traiter, séparés par des virgules")
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--dim", type=int, default=DIM)
    ap.add_argument("--threads", type=int, default=None, help="fils CPU du moteur (défaut : celui du moteur)")
    ap.add_argument("--dry-run", action="store_true", help="sans modèle : compte ce qui reste à calculer")
    ap.add_argument("--limit", type=int, default=None, help="au plus N fiches calculées par site (essais)")
    ap.add_argument("--golden", default=str(GOLDEN_PATH), help="fixture écrite par --dry-run")
    a = ap.parse_args(argv)

    inp = Path(a.inp)
    if not inp.is_dir():
        ap.error(f"--in : dossier introuvable : {inp}")
    if not a.dry_run and not a.model:
        ap.error("--model est obligatoire hors --dry-run")
    if a.batch < 1:
        ap.error("--batch doit être >= 1")
    mid = model_id(a.dim)
    files = site_files(inp)
    if a.sites:
        wanted = [s.strip() for s in a.sites.split(",") if s.strip()]
        missing = [s for s in wanted if s not in files]
        if missing:
            log(f"sites absents de {inp} : {', '.join(missing)}")
        files = {s: files[s] for s in wanted if s in files}
    previous = site_files(Path(a.previous)) if a.previous else {}

    embedder = None if a.dry_run else Embedder(a.model, a.dim, a.threads)
    results = []
    try:
        for site, path in files.items():
            r = process_site(site, path, previous.get(site), mid, embedder, a.batch, a.limit, a.dry_run)
            results.append(r)
            print(summary_line(r, a.dry_run), flush=True)
    finally:
        if embedder is not None:
            embedder.close()

    if a.dry_run:
        g = Path(a.golden)
        g.write_text(json.dumps(golden(a.dim), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"golden : {g}")
    else:
        write_report(inp, results, mid, a.model)
    total = {k: sum(r[k] for r in results) for k in ("reused", "computed", "pending")}
    print(f"total : réutilisés {total['reused']}, calculés {total['computed']}, restants {total['pending']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
