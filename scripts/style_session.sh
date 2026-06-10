#!/usr/bin/env bash
# style_session.sh — interactive UCI session with live style switching
#
# Usage: ./scripts/style_session.sh
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

# Asigura ca engine-ul e compilat
if [[ ! -f "$REPO_DIR/out/chess/Main.class" ]]; then
  echo "compiling engine..."
  (cd "$REPO_DIR" && javac -d out src/chess/*.java)
fi

exec python3 "$SCRIPT_DIR/style_session.py"
