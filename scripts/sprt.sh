#!/usr/bin/env bash
# sprt.sh — Sequential Probability Ratio Test pentru două style files.
#
# SPRT oprește testul cât mai devreme cu p-value controlat (α=β=0.05 default).
# Util pentru a confirma rapid dacă o schimbare aduce ELO sau nu.
#
# Usage:
#   ./scripts/sprt.sh <styleA.json> <styleB.json> [ELO1=5] [TC=10+0.1] [MAX_GAMES=2000]
#
# Decizie:
#   H0 accepted = stilul A NU e mai bun decât B cu cel puțin ELO0 (default 0)
#   H1 accepted = stilul A E mai bun decât B cu cel puțin ELO1 (default 5)
#   În medie SPRT decide în 200-800 jocuri pentru ELO1=5.
set -euo pipefail
ulimit -n 65536 2>/dev/null || true

if [[ $# -lt 2 ]]; then
  echo "usage: $0 <styleA.json> <styleB.json> [ELO1=5] [TC=10+0.1] [MAX_GAMES=2000]"
  echo "example: $0 styles/aggressive.json styles/balanced.json 5 10+0.1"
  exit 1
fi

STYLE_A=$1
STYLE_B=$2
ELO1=${3:-5}
TC=${4:-10+0.1}
MAX_GAMES=${5:-2000}
ELO0=0
ALPHA=0.05
BETA=0.05

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
ENGINE_PATH="$REPO_DIR/run_engine.sh"
OPENINGS="$REPO_DIR/test_positions/openings.epd"
LOGS_DIR="$REPO_DIR/logs"
mkdir -p "$LOGS_DIR"

DEFAULT_CONC=$(( $(sysctl -n hw.physicalcpu 2>/dev/null || echo 4) - 1 ))
CONCURRENCY=$DEFAULT_CONC

name_for() { basename "$1" .json; }
NAME_A=$(name_for "$STYLE_A")
NAME_B=$(name_for "$STYLE_B")
STAMP=$(date +%Y%m%d-%H%M%S)
PGN_OUT="$LOGS_DIR/sprt-${NAME_A}-vs-${NAME_B}-${STAMP}.pgn"

STYLE_A_ABS=$(cd "$(dirname "$STYLE_A")" && pwd)/$(basename "$STYLE_A")
STYLE_B_ABS=$(cd "$(dirname "$STYLE_B")" && pwd)/$(basename "$STYLE_B")

echo "================================================================"
echo "  SPRT: $NAME_A vs $NAME_B"
echo "  H0: ELO diff <= $ELO0     H1: ELO diff >= $ELO1"
echo "  α=$ALPHA  β=$BETA   TC: $TC   max games: $MAX_GAMES"
echo "  PGN:   $PGN_OUT"
echo "================================================================"

"$REPO_DIR/tools/fastchess" \
  -engine name="$NAME_A" cmd="$ENGINE_PATH" proto=uci \
          option.OwnBook=false option.StyleFile="$STYLE_A_ABS" \
  -engine name="$NAME_B" cmd="$ENGINE_PATH" proto=uci \
          option.OwnBook=false option.StyleFile="$STYLE_B_ABS" \
  -each tc="$TC" \
  -games 2 -rounds $((MAX_GAMES / 2)) \
  -openings file="$OPENINGS" format=epd order=random plies=16 \
  -repeat \
  -resign movecount=4 score=600 \
  -draw movenumber=40 movecount=8 score=10 \
  -concurrency "$CONCURRENCY" \
  -pgnout file="$PGN_OUT" \
  -sprt elo0=$ELO0 elo1=$ELO1 alpha=$ALPHA beta=$BETA \
  -ratinginterval 10
