#!/usr/bin/env python3
"""compare_brains.py — afișează v1 vs v2 side-by-side pe o lista de texte.

Useful pentru:
  - Verificat ca v2 generalizează la fraze pe care v1 nu le poate parsa
  - Detectat regresii (v2 prezice modificatori absurzi)
  - Quality control inainte de SPRT

Usage:
    ./scripts/compare_brains.py              # lista default de 15 texte
    ./scripts/compare_brains.py --file my_tests.txt   # one text per line
    ./scripts/compare_brains.py "agresiv cu cai" "play like Carlsen"
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Import StyleBrain implementations
sys.path.insert(0, str(Path(__file__).resolve().parent))
from style_brain import KeywordBrain, MLPBrain  # noqa: E402


DEFAULT_TESTS = [
    # Texte pe care v1 le acopera bine
    "agresiv cu cai",
    "defensiv solid",
    "open positional bishops",
    "endgame technique",

    # Texte pe care v1 le rateaza partial sau total
    "play like Karpov",
    "Tal-style sacrifices",
    "Petrosian fortress",
    "hypermodern player",
    "anti-Sicilian aggression",

    # Texte casual
    "play aggressive but safe king",
    "joaca sigur",
    "nu pierde piese",
    "be careful with queen",

    # Vagi
    "play normal",
    "play smart",
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("texts", nargs="*", help="texte de comparat (default: lista hardcoded)")
    ap.add_argument("--file", type=Path, help="fisier cu un text per linie")
    ap.add_argument("--v1-only", action="store_true", help="afiseaza doar v1 (pentru cand v2 nu exista)")
    args = ap.parse_args()

    texts: list[str] = []
    if args.file and args.file.exists():
        texts = [l.strip() for l in args.file.read_text().splitlines() if l.strip()]
    elif args.texts:
        texts = list(args.texts)
    else:
        texts = DEFAULT_TESTS

    v1 = KeywordBrain()
    v2 = None
    if not args.v1_only:
        try:
            v2 = MLPBrain()
        except (FileNotFoundError, RuntimeError) as e:
            print(f"[compare] v2 unavailable: {e}", file=sys.stderr)
            print(f"[compare] continuing with v1 only.\n", file=sys.stderr)

    print(f"{'='*70}")
    for text in texts:
        print(f"\n📝 {text!r}")

        m1, d1 = v1.predict(text)
        kw = ", ".join(f"{k}" for k in d1.keys())
        if m1:
            print(f"  v1 [{kw if kw else 'no keywords'}]:")
            for k, v in sorted(m1.items()):
                arrow = "↑" if v > 1.0 else "↓"
                print(f"    {arrow} {k:18} = {v}")
        else:
            print(f"  v1: ∅ (no keywords detected)")

        if v2 is not None:
            m2, _ = v2.predict(text)
            if m2:
                print(f"  v2 [MLP]:")
                for k, v in sorted(m2.items()):
                    arrow = "↑" if v > 1.0 else "↓"
                    print(f"    {arrow} {k:18} = {v}")
            else:
                print(f"  v2: ∅ (no tokens recognized)")

            # Diff
            only_v1 = set(m1) - set(m2)
            only_v2 = set(m2) - set(m1)
            common  = set(m1) & set(m2)
            if only_v1 or only_v2 or any(abs(m1[k] - m2[k]) > 0.02 for k in common):
                print(f"  diff:")
                for k in sorted(only_v1):
                    print(f"    - {k:18} (v1: {m1[k]}, v2: 1.00)")
                for k in sorted(only_v2):
                    print(f"    + {k:18} (v1: 1.00, v2: {m2[k]})")
                for k in sorted(common):
                    if abs(m1[k] - m2[k]) > 0.02:
                        print(f"    ~ {k:18}  v1: {m1[k]} → v2: {m2[k]}")
    print(f"\n{'='*70}")


if __name__ == "__main__":
    main()
