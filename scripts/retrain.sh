#!/usr/bin/env bash
# retrain.sh — wrapper care alege hyperparametri pe baza marimii dataset-ului.
#
# Folosit dupa ingest pentru retraining rapid cu setari sane.
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/.." && pwd)
DATASET="$REPO_DIR/data/style_dataset.jsonl"

if [[ ! -f "$DATASET" ]]; then
  echo "ERROR: $DATASET nu exista. Ruleaza ingest_gemini.py intai." >&2
  exit 1
fi

N=$(wc -l < "$DATASET" | tr -d ' ')
echo "Dataset: $N entries"

# Hyperparametri pe baza marimii:
#   < 100:  hidden=32, vocab mic, dropout 0,    epochs 100
#   < 300:  hidden=64, vocab medium, dropout 0,    epochs 200
#   < 700:  hidden=96, vocab medium, dropout 0.1,  epochs 400
#   >= 700: hidden=128, vocab mare, dropout 0.15, epochs 500
if [[ $N -lt 100 ]]; then
  HIDDEN=32;  WORDS=400;  TRIGRAMS=300;  DROPOUT=0;     EPOCHS=100; LR=0.02
elif [[ $N -lt 300 ]]; then
  HIDDEN=64;  WORDS=1500; TRIGRAMS=800;  DROPOUT=0;     EPOCHS=300; LR=0.02
elif [[ $N -lt 700 ]]; then
  HIDDEN=96;  WORDS=2000; TRIGRAMS=1000; DROPOUT=0.1;   EPOCHS=500; LR=0.015
else
  HIDDEN=128; WORDS=2500; TRIGRAMS=1200; DROPOUT=0.15;  EPOCHS=800; LR=0.012
fi

echo "Hyperparams: hidden=$HIDDEN vocab=($WORDS+$TRIGRAMS) dropout=$DROPOUT epochs=$EPOCHS lr=$LR"
echo

exec "$SCRIPT_DIR/train_brain.py" \
  --hidden "$HIDDEN" \
  --vocab-words "$WORDS" \
  --vocab-trigrams "$TRIGRAMS" \
  --dropout "$DROPOUT" \
  --epochs "$EPOCHS" \
  --lr "$LR" \
  --patience 50 \
  --log-every 30 \
  "$@"
