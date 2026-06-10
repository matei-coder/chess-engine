#!/usr/bin/env python3
"""Ingest Gemini-generated batches → master training dataset.

Accept input:
  - JSON array (Gemini default output)         [{"text": ..., "modifiers": ...}, ...]
  - JSONL (one object per line)

Validation per entry:
  - Has "text" (non-empty string) AND "modifiers" (dict).
  - All modifier keys are in KNOWN_FEATURES (whitelist).
  - All modifier values are floats in [0.5, 2.0].

Deduplication: by text (case-insensitive, stripped).

Output:
  - Appends accepted entries to data/style_dataset.jsonl (JSONL).
  - Logs accepted/rejected stats + sample rejections.
  - Updates data/dataset_meta.json with cumulative stats.

Usage:
    ./scripts/ingest_gemini.py gemini_batches/*.json
    ./scripts/ingest_gemini.py path/to/batch.jsonl
    ./scripts/ingest_gemini.py --stats   # just print current dataset stats
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any

REPO_DIR    = Path(__file__).resolve().parent.parent
DATA_DIR    = REPO_DIR / "data"
DATASET     = DATA_DIR / "style_dataset.jsonl"
META        = DATA_DIR / "dataset_meta.json"

# Whitelist de features acceptate — corespunde cu BROADCAST_GROUPS din StyleOrchestrator.java
KNOWN_FEATURES = {
    "MAT_PAWN", "MAT_KNIGHT", "MAT_BISHOP", "MAT_ROOK", "MAT_QUEEN", "MAT_KING",
    "PST_PAWN", "PST_KNIGHT", "PST_BISHOP", "PST_ROOK", "PST_QUEEN", "PST_KING_MG",
    "PAWN_ISOLATED_MG", "PAWN_DOUBLED_MG",
}

# MAT_KING e locked in StyleOrchestrator → multiplicator ignorat la runtime
LOCKED_FEATURES = {"MAT_KING"}

# Multiplicatori in afara acestui range sunt rejected (safety bounds)
MIN_MULT = 0.5
MAX_MULT = 2.0


def load_batch(path: Path) -> list[dict]:
    """Returnează lista de entries indiferent de format (JSON array sau JSONL)."""
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        return []
    # Try JSON array first
    try:
        data = json.loads(text)
        if isinstance(data, list):
            return data
        if isinstance(data, dict):
            # Maybe wrapped: {"data": [...]} or just one entry
            for k in ("data", "entries", "examples", "dataset"):
                if k in data and isinstance(data[k], list):
                    return data[k]
            # Single entry
            return [data]
    except json.JSONDecodeError:
        pass
    # Try JSONL
    entries = []
    for i, line in enumerate(text.split("\n"), 1):
        line = line.strip()
        if not line:
            continue
        try:
            entries.append(json.loads(line))
        except json.JSONDecodeError as e:
            print(f"  [WARN] {path.name} line {i}: {e}", file=sys.stderr)
    return entries


def validate(entry: Any) -> tuple[bool, str]:
    """Returnează (valid, reason). Reason e empty string daca e valid."""
    if not isinstance(entry, dict):
        return False, "not a dict"
    text = entry.get("text")
    mods = entry.get("modifiers")
    if not isinstance(text, str) or not text.strip():
        return False, "missing/empty text"
    if not isinstance(mods, dict):
        return False, "modifiers must be a dict"
    if not mods:
        return False, "modifiers empty"
    for k, v in mods.items():
        if k not in KNOWN_FEATURES:
            return False, f"unknown feature '{k}'"
        if not isinstance(v, (int, float)):
            return False, f"non-numeric value for '{k}': {v!r}"
        if not (MIN_MULT <= v <= MAX_MULT):
            return False, f"value out of [{MIN_MULT}, {MAX_MULT}] for '{k}': {v}"
    return True, ""


def load_existing_dataset() -> tuple[list[dict], set[str]]:
    """Loadeaza dataset-ul curent + return-eaza set-ul de texte vazute (lowercased)."""
    if not DATASET.exists():
        return [], set()
    entries = []
    seen = set()
    for line in DATASET.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
            entries.append(e)
            seen.add(e["text"].strip().lower())
        except (json.JSONDecodeError, KeyError):
            continue
    return entries, seen


def append_entries(new_entries: list[dict]) -> None:
    DATA_DIR.mkdir(exist_ok=True)
    with open(DATASET, "a", encoding="utf-8") as f:
        for e in new_entries:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")


def write_meta(stats: dict) -> None:
    META.write_text(json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")


def compute_stats(entries: list[dict]) -> dict:
    """Stats peste tot dataset-ul: feature coverage, lang ratio, mult distribution."""
    feature_counts = Counter()
    feature_values = {f: [] for f in KNOWN_FEATURES}
    for e in entries:
        for k, v in e.get("modifiers", {}).items():
            feature_counts[k] += 1
            feature_values[k].append(v)

    feature_stats = {}
    for f in sorted(KNOWN_FEATURES):
        vals = feature_values[f]
        if vals:
            feature_stats[f] = {
                "count": len(vals),
                "min":  round(min(vals), 3),
                "max":  round(max(vals), 3),
                "mean": round(sum(vals) / len(vals), 3),
            }
        else:
            feature_stats[f] = {"count": 0}

    # Limba detection (rough): RO if contains romanian-only chars
    ro_chars = set("ăâîșțĂÂÎȘȚ")
    ro = sum(1 for e in entries if any(c in ro_chars for c in e["text"]))

    return {
        "total_entries":     len(entries),
        "feature_coverage":  feature_stats,
        "ro_estimate":       ro,
        "en_estimate":       len(entries) - ro,
        "avg_modifiers_per_entry": round(sum(len(e["modifiers"]) for e in entries) / max(len(entries), 1), 2),
    }


# ============================================================
# Main
# ============================================================
def cmd_stats():
    entries, _ = load_existing_dataset()
    stats = compute_stats(entries)
    print(json.dumps(stats, indent=2, ensure_ascii=False))


def cmd_ingest(paths: list[Path]):
    existing, seen = load_existing_dataset()
    print(f"Existing dataset: {len(existing)} entries\n")

    total_seen = 0
    total_added = 0
    total_rejected = 0
    total_duplicate = 0
    rejection_samples: list[str] = []

    for path in paths:
        if not path.exists():
            print(f"  [SKIP] {path} doesn't exist")
            continue
        entries = load_batch(path)
        added = 0
        rejected = 0
        duplicates = 0
        accepted_new: list[dict] = []
        for entry in entries:
            total_seen += 1
            ok, reason = validate(entry)
            if not ok:
                rejected += 1
                if len(rejection_samples) < 5:
                    rejection_samples.append(f"{path.name}: {reason} | entry={entry}")
                continue
            key = entry["text"].strip().lower()
            if key in seen:
                duplicates += 1
                continue
            seen.add(key)
            # Normalize: keep only text+modifiers (drop extras)
            normalized = {"text": entry["text"].strip(), "modifiers": entry["modifiers"]}
            accepted_new.append(normalized)
            added += 1

        if accepted_new:
            append_entries(accepted_new)
        total_added     += added
        total_rejected  += rejected
        total_duplicate += duplicates
        print(f"  {path.name}: {len(entries)} parsed | {added} added | {rejected} rejected | {duplicates} dup")

    print()
    print(f"== Summary ==")
    print(f"  Total seen:      {total_seen}")
    print(f"  Accepted:        {total_added}")
    print(f"  Rejected:        {total_rejected}")
    print(f"  Duplicates:      {total_duplicate}")
    print(f"  Dataset total:   {len(existing) + total_added}")

    if rejection_samples:
        print()
        print("  Rejection examples:")
        for s in rejection_samples:
            print(f"    - {s}")

    # Cumulative dataset stats
    new_dataset, _ = load_existing_dataset()
    stats = compute_stats(new_dataset)
    write_meta(stats)

    print()
    print("== Dataset coverage ==")
    print(f"  Languages: ~{stats['ro_estimate']} RO / ~{stats['en_estimate']} EN")
    print(f"  Avg modifiers per entry: {stats['avg_modifiers_per_entry']}")
    print(f"  Feature coverage (count per feature):")
    for f, fs in stats["feature_coverage"].items():
        cnt = fs["count"]
        bar = "█" * min(40, cnt)
        suffix = f"  range [{fs['min']}, {fs['max']}] mean {fs['mean']}" if cnt > 0 else ""
        print(f"    {f:18} {cnt:4} {bar}{suffix}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="*", type=Path, help="Batch files to ingest")
    ap.add_argument("--stats", action="store_true", help="Print current dataset stats without ingesting")
    args = ap.parse_args()

    if args.stats:
        cmd_stats()
        return

    if not args.paths:
        ap.error("provide at least one batch file (or --stats)")

    cmd_ingest(args.paths)


if __name__ == "__main__":
    main()
