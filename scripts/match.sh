#!/usr/bin/env bash
# match.sh — fixed-games match între două style files folosind cutechess-cli.
#
# Usage:
#   ./scripts/match.sh <styleA.json> <styleB.json> [N_GAMES] [TC] [CONCURRENCY]
#
# Defaults: 100 jocuri, TC 10+0.1, concurrency = nr fizic de cores - 1.
#
# Output: ELO diff (A vs B) cu interval de confidență 95% + PGN salvat în logs/.
set -euo pipefail

# fastchess deschide multe file descriptors (per worker + pipe-uri spre engine);
# default macOS poate fi prea mic. Ridicam soft limit.
ulimit -n 65536 2>/dev/null || true

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <styleA.json> <styleB.json> [N_GAMES=100] [TC=10+0.1] [CONCURRENCY]"
  echo "example: $0 styles/aggressive.json styles/balanced.json 200 5+0.1 6"
  exit 1
fi

STYLE_A=$1
STYLE_B=$2
N_GAMES=${3:-100}
TC=${4:-10+0.1}
DEFAULT_CONC=$(( $(sysctl -n hw.physicalcpu 2>/dev/null || echo 4) - 1 ))
CONCURRENCY=${5:-$DEFAULT_CONC}

# Resolve paths absolute (cutechess needs absolute paths for engine cmd)
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
ENGINE_PATH="$REPO_DIR/run_engine.sh"
OPENINGS="$REPO_DIR/test_positions/openings.epd"
LOGS_DIR="$REPO_DIR/logs"
mkdir -p "$LOGS_DIR"

# Pretty name from style file basename, no extension
name_for() { basename "$1" .json; }
NAME_A=$(name_for "$STYLE_A")
NAME_B=$(name_for "$STYLE_B")
STAMP=$(date +%Y%m%d-%H%M%S)
PGN_OUT="$LOGS_DIR/${NAME_A}-vs-${NAME_B}-${STAMP}.pgn"

# Style files must be absolute too — engine works dir e set in run_engine.sh,
# but cutechess passes the option value verbatim
STYLE_A_ABS=$(cd "$(dirname "$STYLE_A")" && pwd)/$(basename "$STYLE_A")
STYLE_B_ABS=$(cd "$(dirname "$STYLE_B")" && pwd)/$(basename "$STYLE_B")

echo "================================================================"
echo "  Match: $NAME_A vs $NAME_B"
echo "  Games: $N_GAMES   TC: $TC   Concurrency: $CONCURRENCY"
echo "  PGN:   $PGN_OUT"
echo "================================================================"

"$REPO_DIR/tools/fastchess" \
  -engine name="$NAME_A" cmd="$ENGINE_PATH" proto=uci \
          option.OwnBook=false option.StyleFile="$STYLE_A_ABS" \
  -engine name="$NAME_B" cmd="$ENGINE_PATH" proto=uci \
          option.OwnBook=false option.StyleFile="$STYLE_B_ABS" \
  -each tc="$TC" \
  -games 2 -rounds $((N_GAMES / 2)) \
  -openings file="$OPENINGS" format=epd order=random plies=16 \
  -repeat \
  -resign movecount=4 score=600 \
  -draw movenumber=40 movecount=8 score=10 \
  -concurrency "$CONCURRENCY" \
  -pgnout file="$PGN_OUT" \
  -ratinginterval 10
