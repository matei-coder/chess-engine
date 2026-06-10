#!/usr/bin/env bash
# pick_style.sh — selectează un stil și-l setează ca "current".
#
# Engine-ul detectează schimbarea automat la următoarea comandă `go`
# (vezi maybeReloadStyleFile din Uci.java). Asta funcționează ȘI in
# timpul jocurilor pe Lichess via lichess-bot: lichess-bot deschide
# engine-ul cu StyleFile=styles/current.json, iar acest script doar
# suprascrie current.json — engine-ul reincărcă la următoarea mutare.
#
# Usage:
#   ./scripts/pick_style.sh                 — listă interactivă
#   ./scripts/pick_style.sh <nume>          — direct (ex: knightly)
#   ./scripts/pick_style.sh --list          — doar listă, fără pick
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
STYLES_DIR="$REPO_DIR/styles"
CURRENT="$STYLES_DIR/current.json"

# Listează toate stilurile (exclude current si vector_sample)
list_styles() {
  local i=0
  for f in "$STYLES_DIR"/*.json; do
    local name=$(basename "$f" .json)
    [[ "$name" == "current" || "$name" == "vector_sample" ]] && continue
    i=$((i+1))
    # Marcaj * pe stilul curent
    local marker=" "
    if [[ -f "$CURRENT" ]] && cmp -s "$f" "$CURRENT" 2>/dev/null; then
      marker="*"
    fi
    # Extrage description din JSON
    local desc=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('description',''))" "$f" 2>/dev/null || echo "")
    printf "  %s %2d) %-14s — %s\n" "$marker" "$i" "$name" "$desc"
  done
}

# Returneaza path-ul fisierului corespunzator alegerii (numar sau nume)
resolve_choice() {
  local choice=$1
  if [[ "$choice" =~ ^[0-9]+$ ]]; then
    local i=0
    for f in "$STYLES_DIR"/*.json; do
      local name=$(basename "$f" .json)
      [[ "$name" == "current" || "$name" == "vector_sample" ]] && continue
      i=$((i+1))
      if [[ "$i" == "$choice" ]]; then
        echo "$f"
        return 0
      fi
    done
  else
    if [[ -f "$STYLES_DIR/$choice.json" ]]; then
      echo "$STYLES_DIR/$choice.json"
      return 0
    fi
  fi
  return 1
}

# --- main ---
if [[ "${1:-}" == "--list" || "${1:-}" == "-l" ]]; then
  list_styles
  exit 0
fi

if [[ $# -eq 0 ]]; then
  echo "Stiluri disponibile (marker * = activ acum):"
  echo
  list_styles
  echo
  read -p "Alege (număr sau nume): " choice
else
  choice="$1"
fi

if ! target=$(resolve_choice "$choice"); then
  echo "Stil necunoscut: $choice" >&2
  echo "Foloseste --list pentru a vedea cele disponibile." >&2
  exit 1
fi

# Scrie atomic: temp file + rename → engine-ul nu citeste niciodata content partial
tmp=$(mktemp "$STYLES_DIR/.current.json.XXXXXX")
trap 'rm -f "$tmp"' EXIT
cp "$target" "$tmp"
mv -f "$tmp" "$CURRENT"
trap - EXIT

ts=$(date +%H:%M:%S)
name=$(basename "$target" .json)
desc=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get('description',''))" "$target" 2>/dev/null || echo "")
echo "[$ts] [pick] ✓ styles/current.json → $name"
echo "         desc: $desc"
echo "         → engine reîncarcă automat la următorul 'go'"
