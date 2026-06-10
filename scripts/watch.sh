#!/usr/bin/env bash
# watch.sh — live monitoring pentru schimbari de stil + engine activity
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
exec python3 -u "$SCRIPT_DIR/watch.py" "$@"
