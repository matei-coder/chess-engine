#!/usr/bin/env python3
"""style_brain — homemade NLP model that turns a free-text description
into a 200-feature style vector and writes it to styles/current.json.

v1 (implementat aici): keyword-based linear model.
  - Tokenize text → extract recognized keywords (RO + EN, cu sinonime)
  - Fiecare keyword are o "semnătură" — un set de (feature_name, delta)
  - Adunăm delta-urile, adăugăm la baseline 1.0, clamp la safe range
  - Output: dict pe nume de feature (forma "named") → JSON
  - Cost: ~1ms, zero dependențe (doar stdlib).

v2 (viitor): înlocuim KEYWORDS dict cu un MLP antrenat. Interfața StyleBrain
e proiectată ca să facem swap-ul fără să atingem orchestratorul sau loader-ul.

Architectură:
    StyleBrain (interfață) — predict(text) → dict[feature_name, multiplier]
    KeywordBrain : StyleBrain — v1 implementare
    MLPBrain     : StyleBrain — v2, viitor

Usage:
    style_brain.py "agresiv cu cai"
    echo "defensiv solid" | style_brain.py
    style_brain.py --interactive   # REPL: introduci texte succesiv

Output: scrie styles/current.json (atomic via tmp+rename). Engine-ul
detectează la următorul `go` și reincarcă (vezi maybeReloadStyleFile).
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Iterable

REPO_DIR    = Path(__file__).resolve().parent.parent
STYLES_DIR  = REPO_DIR / "styles"
CURRENT     = STYLES_DIR / "current.json"

# =============================================================================
# v1: Keyword-based model
# =============================================================================
# Fiecare keyword e mapat la un set de feature_name → delta (adăugat la 1.0).
# Sume sunt clamped la [-0.20, +0.20] per feature înainte de a fi convertite
# in multiplicatori, ca să nu producem stiluri pathologic-rele
# (lecție din SPRT: aggressive.json cu × 1.5 PST_PAWN pierde -52 ELO).
#
# Numele de feature trebuie să corespundă cu BROADCAST_GROUPS din StyleOrchestrator:
#   MAT_PAWN, MAT_KNIGHT, MAT_BISHOP, MAT_ROOK, MAT_QUEEN, MAT_KING (locked)
#   PST_PAWN, PST_KNIGHT, PST_BISHOP, PST_ROOK, PST_QUEEN, PST_KING_MG

# Delta convention: pozitiv = "amplifică această caracteristică"
KEYWORDS: dict[str, dict[str, float]] = {
    # --- Stilul general ---
    "aggressive": {"PST_KNIGHT": 0.08, "PST_BISHOP": 0.08, "MAT_QUEEN": 0.03},
    "defensive":  {"PST_KING_MG": 0.10, "PST_PAWN": -0.04, "MAT_PAWN": 0.03},
    "solid":      {"PST_KING_MG": 0.08, "PST_PAWN": -0.03, "MAT_PAWN": 0.02},
    "tactical":   {"PST_KNIGHT": 0.08, "PST_BISHOP": 0.05, "MAT_QUEEN": 0.02},
    "positional": {"PST_KNIGHT": 0.06, "PST_BISHOP": 0.06, "PST_ROOK": 0.04},
    "patient":    {"PST_PAWN": -0.05, "PST_KING_MG": 0.05},

    # --- Piese preferate ---
    "knight":     {"MAT_KNIGHT": 0.05, "PST_KNIGHT": 0.10, "MAT_BISHOP": -0.03},
    "bishop":     {"MAT_BISHOP": 0.05, "PST_BISHOP": 0.10, "MAT_KNIGHT": -0.03},
    "rook":       {"MAT_ROOK": 0.04, "PST_ROOK": 0.08},
    "queen":      {"MAT_QUEEN": 0.05, "PST_QUEEN": 0.05},
    "pawn":       {"PST_PAWN": 0.05, "MAT_PAWN": 0.03},

    # --- Faza de joc / direcție strategică ---
    "endgame":    {"PST_KING_MG": -0.08, "MAT_KNIGHT": 0.04, "MAT_BISHOP": 0.04},
    "simplify":   {"MAT_KNIGHT": 0.04, "MAT_BISHOP": 0.04, "MAT_ROOK": 0.04, "MAT_QUEEN": 0.04, "MAT_PAWN": -0.03},
    "trade":      {"MAT_KNIGHT": 0.04, "MAT_BISHOP": 0.04, "MAT_ROOK": 0.04, "MAT_QUEEN": 0.04},
    "attack":     {"PST_KNIGHT": 0.08, "PST_BISHOP": 0.08, "MAT_QUEEN": 0.04},
    "open":       {"PST_BISHOP": 0.08, "PST_ROOK": 0.04, "MAT_BISHOP": 0.03, "MAT_KNIGHT": -0.02},
    "closed":     {"PST_KNIGHT": 0.08, "MAT_KNIGHT": 0.03, "MAT_BISHOP": -0.02, "PST_BISHOP": -0.04},
    "fast":       {"PST_PAWN": 0.04, "PST_KNIGHT": 0.04},
    "slow":       {"PST_PAWN": -0.03, "PST_KING_MG": 0.04},
}

# Mapping RO → EN canonical (extensibil)
SYNONYMS: dict[str, str] = {
    # Stil general
    "agresiv": "aggressive", "agresiva": "aggressive", "agresive": "aggressive",
    "atacant": "attack", "ataca": "attack", "atac": "attack",
    "defensiv": "defensive", "defensiva": "defensive", "defensive": "defensive",
    "solid": "solid", "solida": "solid",
    "tactic": "tactical", "tactica": "tactical", "tactical": "tactical",
    "pozitional": "positional", "pozițional": "positional",
    "rabdator": "patient", "răbdător": "patient", "rabdare": "patient",

    # Piese (RO singular + plural)
    "cal": "knight", "cai": "knight", "cailor": "knight",
    "nebun": "bishop", "nebuni": "bishop", "nebunilor": "bishop",
    "tura": "rook", "ture": "rook", "tură": "rook",
    "regina": "queen", "regină": "queen", "regine": "queen",
    "pion": "pawn", "pioni": "pawn", "pionilor": "pawn",
    "rege": "endgame",  # vorbim de rege = de obicei in endgame

    # Faza / strategie
    "final": "endgame", "finaluri": "endgame", "endgame": "endgame", "endgames": "endgame",
    "deschidere": "open",  # nu e fix dar correlat
    "deschis": "open", "deschisa": "open", "deschise": "open",
    "inchis": "closed", "închis": "closed", "inchisa": "closed", "închisă": "closed",
    "schimburi": "trade", "schimb": "trade", "trades": "trade",
    "simplifica": "simplify", "simplificare": "simplify",
    "rapid": "fast", "rapida": "fast",
    "lent": "slow", "lenta": "slow", "lentă": "slow",
}

# Cuvinte ignorate (umplutură care n-ar trebui să declanșeze nimic)
STOPWORDS = {
    "play", "joc", "joaca", "joacă", "cu", "with", "pe", "in", "la",
    "si", "și", "and", "the", "a", "an", "to", "for", "de", "a",
    "un", "o", "niste", "vreau", "i", "want", "want", "let",
}


def tokenize(text: str) -> list[str]:
    """Tokenize: lowercase, split on non-letter, drop stopwords."""
    # Pastram doar litere + spatii — eliminam punctuație, cifre, etc.
    cleaned = re.sub(r"[^a-zA-ZăâîșțĂÂÎȘȚ\s-]+", " ", text.lower())
    tokens = [t for t in cleaned.split() if t and t not in STOPWORDS]
    return tokens


def canonicalize(tok: str) -> str | None:
    """Map sinonim → keyword canonic. Returnează None dacă tok-ul nu e recunoscut."""
    if tok in KEYWORDS:
        return tok
    if tok in SYNONYMS:
        canonical = SYNONYMS[tok]
        # Pasul al doilea: sinonim ar putea fi tot keyword direct
        return canonical if canonical in KEYWORDS else None
    return None


# =============================================================================
# Interfață StyleBrain — permite swap v1 ↔ v2 fără modificări in upstream
# =============================================================================
class StyleBrain:
    """Interfață abstractă. v1 (KeywordBrain) și v2 (MLPBrain) o implementează."""
    def predict(self, text: str) -> tuple[dict[str, float], dict[str, int]]:
        """Returnează (modifiers, features_detected).

        modifiers: dict feature_name → multiplicator (in jurul lui 1.0)
        features_detected: dict keyword_canonic → count (pentru diagnostic/debug)
        """
        raise NotImplementedError


class KeywordBrain(StyleBrain):
    """v1: keyword extraction + linear sum + clamp."""

    # Range per-feature înainte de a converti delta in multiplicator
    MIN_DELTA = -0.20
    MAX_DELTA =  0.20

    # Final multiplier clamp (safety net peste StyleOrchestrator-ul Java)
    MIN_MULT  = 0.80
    MAX_MULT  = 1.25

    def predict(self, text: str) -> tuple[dict[str, float], dict[str, int]]:
        tokens = tokenize(text)
        detected: dict[str, int] = {}
        deltas:   dict[str, float] = {}

        for tok in tokens:
            kw = canonicalize(tok)
            if kw is None:
                continue
            detected[kw] = detected.get(kw, 0) + 1
            for feat, delta in KEYWORDS[kw].items():
                deltas[feat] = deltas.get(feat, 0.0) + delta

        # Clamp delta per-feature, apoi conversie 1.0 + delta
        modifiers: dict[str, float] = {}
        for feat, total_delta in deltas.items():
            clamped = max(self.MIN_DELTA, min(self.MAX_DELTA, total_delta))
            mult = 1.0 + clamped
            mult = max(self.MIN_MULT, min(self.MAX_MULT, mult))
            modifiers[feat] = round(mult, 3)

        return modifiers, detected


# =============================================================================
# v2: MLP Brain — loadează model antrenat (.npz + vocab)
# =============================================================================
class MLPBrain(StyleBrain):
    """Antrenat via train_brain.py. Citeste .npz + vocab.json."""

    MODEL_PATH = REPO_DIR / "models" / "style_mlp_v2.npz"
    VOCAB_PATH = REPO_DIR / "models" / "style_vocab.json"

    # Multiplicatori sub acest prag (in apropiere de 1.0) NU se includ in output —
    # tine dict-ul curat (model prezice 1.0 ± noise pentru features neutre)
    NEUTRAL_TOL = 0.01

    def __init__(self):
        try:
            import numpy as np
        except ImportError as e:
            raise RuntimeError("numpy required for MLPBrain") from e
        self.np = np
        if not self.MODEL_PATH.exists() or not self.VOCAB_PATH.exists():
            raise FileNotFoundError(
                f"v2 model not found at {self.MODEL_PATH}. "
                f"Train it first: ./scripts/train_brain.py"
            )
        npz = np.load(self.MODEL_PATH)
        self.W1 = npz["W1"]; self.b1 = npz["b1"]
        self.W2 = npz["W2"]; self.b2 = npz["b2"]
        self.output_features = list(npz["output_features"].tolist())
        with open(self.VOCAB_PATH) as f:
            self.vocab = json.load(f)

    def _encode(self, text: str):
        np = self.np
        n_words = len(self.vocab["words"])
        n_tri   = len(self.vocab["trigrams"])
        x = np.zeros(n_words + n_tri, dtype=np.float32)
        for tok in tokenize(text):
            if tok in self.vocab["words"]:
                x[self.vocab["words"][tok]] += 1.0
            # char trigrams cu padding
            padded = "^" + tok + "$"
            for i in range(len(padded) - 2):
                tri = padded[i:i+3]
                if tri in self.vocab["trigrams"]:
                    x[n_words + self.vocab["trigrams"][tri]] += 0.5
        n = np.linalg.norm(x)
        return x / n if n > 0 else x

    def predict(self, text: str) -> tuple[dict[str, float], dict[str, int]]:
        np = self.np
        x = self._encode(text)
        if np.linalg.norm(x) < 1e-6:
            # Niciun token cunoscut — return neutru
            return {}, {}
        # Forward (no dropout in eval)
        h = np.maximum(0, x @ self.W1 + self.b1)
        z = h @ self.W2 + self.b2
        sig = 1.0 / (1.0 + np.exp(-z))
        out = 0.5 + 1.5 * sig

        # Filter near-neutral + clamp safety
        modifiers: dict[str, float] = {}
        for i, name in enumerate(self.output_features):
            v = float(out[i])
            # MAT_KING e mereu locked in StyleOrchestrator → nu-l include
            if name == "MAT_KING":
                continue
            if abs(v - 1.0) < self.NEUTRAL_TOL:
                continue
            # Safety clamp (orchestrator-ul va face si el, dar pastram clean)
            v = max(0.5, min(2.0, v))
            modifiers[name] = round(v, 3)

        info = {"mode": "mlp_v2", "input_tokens": len(tokenize(text))}
        return modifiers, info


# =============================================================================
# Brain factory — auto / v1 / v2
# =============================================================================
def make_brain(mode: str = "auto") -> StyleBrain:
    """mode: 'v1' (keyword), 'v2' (MLP), 'auto' (v2 daca exista, else v1)."""
    if mode == "v1":
        return KeywordBrain()
    if mode == "v2":
        return MLPBrain()
    # auto
    try:
        return MLPBrain()
    except (FileNotFoundError, RuntimeError):
        return KeywordBrain()


# =============================================================================
# I/O — scriere atomică in current.json
# =============================================================================
def write_style_file(modifiers: dict[str, float], description: str) -> Path:
    out = {
        "version":     1,
        "description": description,
        "modifiers":   modifiers,
    }
    tmp = CURRENT.parent / f".current.json.{os.getpid()}.tmp"
    with open(tmp, "w") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    os.replace(tmp, CURRENT)
    return CURRENT


# =============================================================================
# CLI
# =============================================================================
def _ts() -> str:
    import time as _t
    return _t.strftime("%H:%M:%S")


def process_text(text: str, brain: StyleBrain, verbose: bool = True) -> bool:
    """Returnează True dacă a fost scris un fișier, False altfel."""
    modifiers, detected = brain.predict(text)
    mode = detected.get("mode", "v1") if isinstance(detected, dict) and "mode" in detected else "v1"

    if not modifiers:
        if verbose:
            if mode.startswith("mlp"):
                print(f"[{_ts()}] [brain/{mode}] ❌ niciun token recunoscut in vocab pentru: {text!r}", file=sys.stderr)
            else:
                print(f"[{_ts()}] [brain/{mode}] ❌ niciun keyword recunoscut in: {text!r}", file=sys.stderr)
                print(f"           keywords v1 disponibile: {', '.join(sorted(KEYWORDS.keys()))}", file=sys.stderr)
        return False

    desc_prefix = "MLP v2" if mode.startswith("mlp") else "v1"
    desc = f"style_brain {desc_prefix} from text: {text!r}"
    write_style_file(modifiers, desc)
    if verbose:
        if mode.startswith("mlp"):
            print(f"[{_ts()}] [brain/{mode}] ✓ inferred from {detected.get('input_tokens', '?')} tokens")
        else:
            kw_str = ", ".join(f"{k}×{v}" for k, v in sorted(detected.items()) if isinstance(v, int))
            print(f"[{_ts()}] [brain/v1] ✓ keywords: {kw_str}")
        print(f"           modifiers ({len(modifiers)} non-neutral):")
        for k, v in sorted(modifiers.items()):
            arrow = "↑" if v > 1.0 else "↓"
            print(f"             {arrow} {k:18} = {v}")
        print(f"           → wrote {CURRENT.relative_to(REPO_DIR)}")
    return True


def main():
    import argparse
    ap = argparse.ArgumentParser(description="style_brain — text → style modifiers")
    ap.add_argument("text", nargs="*", help="text de procesat (sau folosește -i pentru interactiv)")
    ap.add_argument("--mode", choices=["v1", "v2", "auto"], default="auto",
                    help="v1=keyword, v2=MLP, auto=v2 dacă există, altfel v1 (default: auto)")
    ap.add_argument("-i", "--interactive", action="store_true", help="Mod interactiv REPL")
    ap.add_argument("--list", "-l", action="store_true", help="Listează keywords v1 si exit")
    args = ap.parse_args()

    if args.list:
        print("Keywords disponibile (cu deltas):")
        for kw in sorted(KEYWORDS.keys()):
            print(f"  {kw}: {KEYWORDS[kw]}")
        return

    # Instantiate brain
    try:
        brain = make_brain(args.mode)
    except (FileNotFoundError, RuntimeError) as e:
        print(f"[brain] ERROR: {e}", file=sys.stderr)
        sys.exit(2)
    actual_mode = "v2" if isinstance(brain, MLPBrain) else "v1"
    if args.mode == "auto":
        print(f"[brain] auto-selected: {actual_mode}", file=sys.stderr)

    if args.interactive:
        print("Tasteaza descriere (Ctrl-D pentru iesire):")
        try:
            while True:
                line = input("brain> ").strip()
                if line:
                    process_text(line, brain)
        except (EOFError, KeyboardInterrupt):
            print()
        return

    if not args.text:
        # Citește din stdin (pipe)
        if not sys.stdin.isatty():
            text = sys.stdin.read().strip()
            if text:
                ok = process_text(text, brain)
                sys.exit(0 if ok else 1)
        print("usage: style_brain.py [--mode v1|v2|auto] \"descriere\"  sau  -i pentru interactiv", file=sys.stderr)
        sys.exit(1)

    text = " ".join(args.text)
    ok = process_text(text, brain)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
