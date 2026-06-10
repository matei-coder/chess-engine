#!/usr/bin/env python3
"""train_brain.py — antreneaza un MLP NumPy pe data/style_dataset.jsonl.

Arhitectura:
  Input (vocab_size, BOW + char-trigram counts, L2 normalized)
    → Linear → ReLU → Dropout(0.2)
    → Linear → Sigmoid * 1.5 + 0.5 → output (200-dim, multiplicator per feature)

Loss: MSE pe vectorul de output.
Optimizer: SGD cu momentum 0.9, weight decay 1e-4.

Output:
    models/style_mlp_v2.npz   — W1, b1, W2, b2, output_features (npy)
    models/style_vocab.json   — {"words": {...}, "trigrams": {...}}
    models/style_meta.json    — training metadata

Usage:
    ./scripts/train_brain.py                  # train cu default
    ./scripts/train_brain.py --epochs 200     # custom
    ./scripts/train_brain.py --hidden 64      # smaller model
    ./scripts/train_brain.py --val-split 0.2  # custom validation ratio
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np

REPO       = Path(__file__).resolve().parent.parent
DATASET    = REPO / "data" / "style_dataset.jsonl"
MODELS_DIR = REPO / "models"

# Acelasi set ca in StyleOrchestrator (output features)
OUTPUT_FEATURES = [
    "MAT_PAWN", "MAT_KNIGHT", "MAT_BISHOP", "MAT_ROOK", "MAT_QUEEN", "MAT_KING",
    "PST_PAWN", "PST_KNIGHT", "PST_BISHOP", "PST_ROOK", "PST_QUEEN", "PST_KING_MG",
    "PAWN_ISOLATED_MG", "PAWN_DOUBLED_MG",
]
FEATURE_INDEX = {f: i for i, f in enumerate(OUTPUT_FEATURES)}
NUM_OUT = len(OUTPUT_FEATURES)  # 14

# Locked features — output e mereu 1.0 indiferent de ce zice modelul
LOCKED_FEATURES = {"MAT_KING"}

# Range hard de multiplicator (engine clamp)
MIN_MULT = 0.5
MAX_MULT = 2.0


# =============================================================================
# Tokenization + encoder
# =============================================================================
def tokenize(text: str) -> list[str]:
    """Lowercase, strip non-letter, split on whitespace."""
    cleaned = re.sub(r"[^a-zA-ZăâîșțĂÂÎȘȚ\s-]+", " ", text.lower())
    return [t for t in cleaned.split() if len(t) >= 2]


def char_trigrams(word: str) -> list[str]:
    """Returneaza trigramele cu padding ('^' la start, '$' la final)."""
    padded = "^" + word + "$"
    return [padded[i:i+3] for i in range(len(padded) - 2)]


def build_vocab(texts: list[str], max_words: int = 500, max_trigrams: int = 500) -> dict:
    """Vocabular din corpus. Top-K cele mai frecvente."""
    word_counts = Counter()
    tri_counts  = Counter()
    for t in texts:
        for tok in tokenize(t):
            word_counts[tok] += 1
            for tri in char_trigrams(tok):
                tri_counts[tri] += 1
    words = {w: i for i, (w, _) in enumerate(word_counts.most_common(max_words))}
    trigrams = {g: i for i, (g, _) in enumerate(tri_counts.most_common(max_trigrams))}
    return {"words": words, "trigrams": trigrams}


def encode(text: str, vocab: dict) -> np.ndarray:
    """Returneaza vector L2-normalized, dim = len(words) + len(trigrams)."""
    n_words = len(vocab["words"])
    n_tri   = len(vocab["trigrams"])
    x = np.zeros(n_words + n_tri, dtype=np.float32)
    for tok in tokenize(text):
        if tok in vocab["words"]:
            x[vocab["words"][tok]] += 1.0
        for tri in char_trigrams(tok):
            if tri in vocab["trigrams"]:
                x[n_words + vocab["trigrams"][tri]] += 0.5
    n = np.linalg.norm(x)
    return x / n if n > 0 else x


def encode_label(modifiers: dict, baseline: float = 1.0) -> np.ndarray:
    """Convert dict modifiers → vector 14-dim. Lipsesc → 1.0."""
    y = np.full(NUM_OUT, baseline, dtype=np.float32)
    for k, v in modifiers.items():
        if k in FEATURE_INDEX:
            y[FEATURE_INDEX[k]] = float(v)
    return y


# =============================================================================
# MLP NumPy (forward + backward + optimizer in-place)
# =============================================================================
class MLP:
    """2-layer MLP cu output sigmoid-scaled in [0.5, 2.0].

    Trainable: W1 (D, H), b1 (H,), W2 (H, OUT), b2 (OUT,)
    Forward: out = 0.5 + 1.5 * sigmoid(ReLU(X @ W1 + b1) @ W2 + b2)
    Loss: MSE
    Optimizer: SGD + momentum + weight decay
    Regularization: dropout dupa ReLU (training only)
    """

    def __init__(self, input_dim: int, hidden_dim: int, output_dim: int,
                 dropout: float = 0.2, seed: int = 42):
        rng = np.random.default_rng(seed)
        # He init pentru ReLU
        self.W1 = rng.standard_normal((input_dim, hidden_dim)).astype(np.float32) * np.sqrt(2.0 / input_dim)
        self.b1 = np.zeros(hidden_dim, dtype=np.float32)
        self.W2 = rng.standard_normal((hidden_dim, output_dim)).astype(np.float32) * np.sqrt(2.0 / hidden_dim)
        self.b2 = np.zeros(output_dim, dtype=np.float32)
        # Momentum buffers
        self.mW1 = np.zeros_like(self.W1)
        self.mb1 = np.zeros_like(self.b1)
        self.mW2 = np.zeros_like(self.W2)
        self.mb2 = np.zeros_like(self.b2)
        self.dropout = dropout
        self.rng = rng

    # ---- Forward ----
    def forward(self, X: np.ndarray, training: bool = False) -> np.ndarray:
        self.X = X
        self.z1 = X @ self.W1 + self.b1
        self.a1 = np.maximum(0, self.z1)   # ReLU
        if training and self.dropout > 0:
            self.mask = (self.rng.random(self.a1.shape) > self.dropout).astype(np.float32) / (1 - self.dropout)
            self.a1 = self.a1 * self.mask
        else:
            self.mask = None
        self.z2 = self.a1 @ self.W2 + self.b2
        self.sig = 1.0 / (1.0 + np.exp(-self.z2))
        self.out = 0.5 + 1.5 * self.sig
        return self.out

    # ---- Backward ----
    def backward(self, Y: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
        """Returneaza gradientii. Y: (B, OUT). Assume out + sig sunt din ultimul forward."""
        B = Y.shape[0]
        # d_loss / d_out = 2 * (out - Y) / B  (MSE)
        d_out = 2.0 * (self.out - Y) / B          # (B, OUT)
        # d_out / d_sig = 1.5
        # d_sig / d_z2  = sig * (1 - sig)
        d_z2 = d_out * 1.5 * self.sig * (1.0 - self.sig)  # (B, OUT)
        # d_loss / d_W2 = a1.T @ d_z2
        dW2 = self.a1.T @ d_z2
        db2 = d_z2.sum(axis=0)
        # d_loss / d_a1 = d_z2 @ W2.T
        d_a1 = d_z2 @ self.W2.T
        if self.mask is not None:
            d_a1 = d_a1 * self.mask
        # ReLU gradient
        d_z1 = d_a1 * (self.z1 > 0).astype(np.float32)
        dW1 = self.X.T @ d_z1
        db1 = d_z1.sum(axis=0)
        return dW1, db1, dW2, db2

    # ---- Update (SGD + momentum + weight decay) ----
    def step(self, dW1, db1, dW2, db2, lr: float, momentum: float, wd: float):
        # weight decay e gradient pe parametri direct
        self.mW1 = momentum * self.mW1 + dW1 + wd * self.W1
        self.mb1 = momentum * self.mb1 + db1
        self.mW2 = momentum * self.mW2 + dW2 + wd * self.W2
        self.mb2 = momentum * self.mb2 + db2
        self.W1 -= lr * self.mW1
        self.b1 -= lr * self.mb1
        self.W2 -= lr * self.mW2
        self.b2 -= lr * self.mb2


# =============================================================================
# Training loop
# =============================================================================
def mse(pred: np.ndarray, true: np.ndarray) -> float:
    return float(np.mean((pred - true) ** 2))


def train(args):
    if not DATASET.exists():
        print(f"[ERROR] {DATASET} doesn't exist. Run ingest_gemini.py first.", file=sys.stderr)
        sys.exit(1)

    # ---- Load ----
    entries = []
    for line in DATASET.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            e = json.loads(line)
            if "text" in e and "modifiers" in e:
                entries.append(e)
        except json.JSONDecodeError:
            continue

    if len(entries) < 20:
        print(f"[WARN] only {len(entries)} entries — too few for meaningful training.")
        print(f"       MLP will overfit. Adăuga mai multe batches via Gemini.")
        # Continuăm oricum — util pentru smoke-test al pipeline-ului

    print(f"Loaded {len(entries)} training examples\n")

    # ---- Build vocab ----
    texts = [e["text"] for e in entries]
    vocab = build_vocab(texts, max_words=args.vocab_words, max_trigrams=args.vocab_trigrams)
    input_dim = len(vocab["words"]) + len(vocab["trigrams"])
    print(f"Vocab: {len(vocab['words'])} words + {len(vocab['trigrams'])} trigrams = {input_dim} dims")

    # ---- Encode ----
    X = np.stack([encode(e["text"], vocab) for e in entries])
    Y = np.stack([encode_label(e["modifiers"]) for e in entries])
    print(f"X: {X.shape}   Y: {Y.shape}")

    # ---- Train/val split ----
    rng = np.random.default_rng(args.seed)
    idx = rng.permutation(len(entries))
    n_val = max(1, int(len(entries) * args.val_split))
    val_idx, tr_idx = idx[:n_val], idx[n_val:]
    X_tr, Y_tr = X[tr_idx], Y[tr_idx]
    X_val, Y_val = X[val_idx], Y[val_idx]
    print(f"Train: {len(tr_idx)}   Val: {len(val_idx)}\n")

    # ---- Build model ----
    model = MLP(input_dim, args.hidden, NUM_OUT, dropout=args.dropout, seed=args.seed)
    print(f"MLP: {input_dim} → {args.hidden} (ReLU + dropout {args.dropout}) → {NUM_OUT} (sig·1.5+0.5)")
    n_params = sum(p.size for p in [model.W1, model.b1, model.W2, model.b2])
    print(f"Parameters: {n_params:,}\n")

    # ---- Train loop ----
    best_val = float("inf")
    best_state = None
    patience = args.patience
    patience_left = patience
    history = []

    for epoch in range(1, args.epochs + 1):
        # Shuffle
        perm = rng.permutation(len(tr_idx))
        X_tr_s, Y_tr_s = X_tr[perm], Y_tr[perm]
        # Mini-batches
        tr_losses = []
        for i in range(0, len(X_tr_s), args.batch_size):
            xb = X_tr_s[i:i+args.batch_size]
            yb = Y_tr_s[i:i+args.batch_size]
            pred = model.forward(xb, training=True)
            tr_losses.append(mse(pred, yb))
            grads = model.backward(yb)
            model.step(*grads, lr=args.lr, momentum=args.momentum, wd=args.wd)

        tr_loss = float(np.mean(tr_losses))
        val_pred = model.forward(X_val, training=False)
        val_loss = mse(val_pred, Y_val)
        history.append({"epoch": epoch, "train_loss": tr_loss, "val_loss": val_loss})

        if val_loss < best_val - 1e-6:
            best_val = val_loss
            best_state = {
                "W1": model.W1.copy(), "b1": model.b1.copy(),
                "W2": model.W2.copy(), "b2": model.b2.copy(),
            }
            patience_left = patience
            tag = " *"
        else:
            patience_left -= 1
            tag = ""

        if epoch % args.log_every == 0 or epoch == 1 or epoch == args.epochs:
            print(f"  ep {epoch:4} | train {tr_loss:.5f} | val {val_loss:.5f}{tag}")

        if patience_left == 0:
            print(f"  early stop at epoch {epoch} (no improvement for {patience} epochs)")
            break

    # ---- Save ----
    MODELS_DIR.mkdir(exist_ok=True)
    model.W1, model.b1, model.W2, model.b2 = (
        best_state["W1"], best_state["b1"], best_state["W2"], best_state["b2"]
    )

    np.savez(
        MODELS_DIR / "style_mlp_v2.npz",
        W1=model.W1, b1=model.b1, W2=model.W2, b2=model.b2,
        output_features=np.array(OUTPUT_FEATURES),
    )
    with open(MODELS_DIR / "style_vocab.json", "w", encoding="utf-8") as f:
        json.dump(vocab, f, ensure_ascii=False, indent=2)
    with open(MODELS_DIR / "style_meta.json", "w", encoding="utf-8") as f:
        json.dump({
            "trained_at":    time.strftime("%Y-%m-%d %H:%M:%S"),
            "dataset_size":  len(entries),
            "train_size":    len(tr_idx),
            "val_size":      len(val_idx),
            "input_dim":     input_dim,
            "hidden_dim":    args.hidden,
            "output_dim":    NUM_OUT,
            "parameters":    n_params,
            "best_val_loss": round(best_val, 5),
            "epochs_run":    history[-1]["epoch"],
            "args": vars(args),
        }, f, indent=2)

    print()
    print(f"✓ Saved models/style_mlp_v2.npz  ({MODELS_DIR/'style_mlp_v2.npz'})")
    print(f"✓ Saved models/style_vocab.json")
    print(f"✓ Saved models/style_meta.json")
    print(f"  Best val loss: {best_val:.5f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs",          type=int,   default=200)
    ap.add_argument("--batch-size",      type=int,   default=32)
    ap.add_argument("--hidden",          type=int,   default=128)
    ap.add_argument("--lr",              type=float, default=0.01)
    ap.add_argument("--momentum",        type=float, default=0.9)
    ap.add_argument("--wd",              type=float, default=1e-4, help="Weight decay")
    ap.add_argument("--dropout",         type=float, default=0.2)
    ap.add_argument("--val-split",       type=float, default=0.15)
    ap.add_argument("--patience",        type=int,   default=20)
    ap.add_argument("--seed",            type=int,   default=42)
    ap.add_argument("--vocab-words",     type=int,   default=500)
    ap.add_argument("--vocab-trigrams",  type=int,   default=500)
    ap.add_argument("--log-every",       type=int,   default=10)
    args = ap.parse_args()
    train(args)


if __name__ == "__main__":
    main()
