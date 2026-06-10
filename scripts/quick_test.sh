#!/usr/bin/env bash
# quick_test.sh — verifică în 2-4 jocuri rapide că pipeline-ul cutechess
# funcționează: engine-ul pornește, style files se aplică, PGN-ul se generează.
#
# Usage: ./scripts/quick_test.sh
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
cd "$REPO_DIR"

# Recompilează dacă engine-ul lipsește
if [[ ! -f out/chess/Main.class ]]; then
  echo "compiling engine..."
  javac -d out src/chess/*.java
fi

echo "Running 4 quick games (aggressive vs balanced) at TC 1+0.05..."
./scripts/match.sh styles/aggressive.json styles/balanced.json 4 1+0.05 2
echo
echo "Done. PGN salvat in logs/. Verifică continutul cu:"
echo "  ls -lt logs/ | head -3"
