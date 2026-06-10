#!/usr/bin/env bash
# brain.sh — wrapper peste style_brain.py.
#
# Usage:
#   ./scripts/brain.sh "agresiv cu cai"
#   ./scripts/brain.sh -i                 # mode interactiv (REPL)
#   echo "defensiv solid" | ./scripts/brain.sh
#   ./scripts/brain.sh --list             # vezi keyword-urile recunoscute
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
exec python3 "$SCRIPT_DIR/style_brain.py" "$@"
