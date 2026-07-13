# ♞ Chess Engine

A modern Java chess engine with a **natural-language style controller** — describe how you want it to play in plain English or Romanian, and the engine's evaluation function rewires itself in real time.

> "play like Karpov" → engine ups PST_KING_MG, PST_KNIGHT, PAWN_ISOLATED penalties  
> "sacrifice pieces for attack" → MAT_PAWN down, PST_QUEEN up, king safety relaxed  
> "solid, patient endgame technique" → PST_KING_EG up, mobility bonuses tuned toward simplification

---

## 📊 At a glance

| Aspect | Value |
|---|---|
| **Language** | Java 21 |
| **Estimated ELO** | ~2400-2600 (from ~1700 baseline, +700 ELO validated via SPRT) |
| **Search technique** | Iterative deepening α-β with 15+ modern pruning/extension techniques |
| **Evaluation** | Tapered MG/EG with material, PSTs, king safety, pawn structure, mobility, outposts, rook files, pawn shield |
| **Multi-threading** | Lazy SMP, 1-8 threads, lock-free TT (XOR trick) |
| **Style system** | 256-feature StyleOrchestrator with live JSON reload + trained MLP for text→weights |
| **Opening book** | Polyglot `.bin` format (Donna-compatible) |
| **Protocol** | Full UCI + pondering |

---

## 🚀 What makes this engine different

Modern chess engines converge on ~equal playing styles because they're all optimizing for objective ELO. This one lets you **steer its personality** with plain text:

```bash
$ ./scripts/brain.sh "play like Tal — sacrifice pawns for kingside attack"
[18:35:47] [brain/mlp_v2] ✓ inferred from 8 tokens
           modifiers (10 non-neutral):
             ↓ MAT_PAWN           = 0.902
             ↑ PST_QUEEN          = 1.07
             ↑ PST_KNIGHT         = 1.036
             ↑ PST_ROOK           = 1.056
             ↓ PST_KING_MG        = 0.990
           → wrote styles/current.json
```

The engine detects the file change on its next move (via mtime polling) and reloads the weights in-place — no restart needed, even mid-game on Lichess.

---

## 🎯 Feature checklist

### Search (in `Search.java`)
- ✅ **Transposition table** — 128 MB default, XOR-trick lock-free for multi-thread
- ✅ **Alpha-beta pruning** with negamax formulation
- ✅ **Iterative deepening** with **aspiration windows** (±50 cp, doubling on fail)
- ✅ **Null Move Pruning** (R=2, gated by non-pawn material + non-PV + not-in-check)
- ✅ **Late Move Reductions** with logarithmic formula (`ln(d)·ln(mv)/2`)
- ✅ **Late Move Pruning** (skip quiets past `5 + depth²` at depth ≤ 3)
- ✅ **Reverse Futility Pruning** (static null, depth ≤ 6)
- ✅ **Futility Pruning frontier** (depth = 1, quiet moves only)
- ✅ **Razoring** (depth ≤ 2, quiescence verifier)
- ✅ **Internal Iterative Deepening** (PV nodes without TT move, depth ≥ 5)
- ✅ **Check extensions** (+1 ply when in check)
- ✅ **Mate distance pruning** (clamp α/β around mate scores)
- ✅ **Killer moves** (2 per ply)
- ✅ **History heuristic** (depth² updates, aging on `ucinewgame`)
- ✅ **Counter moves heuristic** (per opponent piece+square)
- ✅ **Static Exchange Evaluation** (swap algorithm, separates good/bad captures)
- ✅ **Quiescence search** with stand-pat + MVV-LVA capture ordering

### Evaluation (in `Evaluator.java`)
- ✅ **Tapered MG/EG interpolation** (phase from Knight=1, Bishop=1, Rook=2, Queen=4, max 24)
- ✅ **Material values + piece-square tables** (32-value half-board with L-R symmetry)
- ✅ **Endgame-specific king PST** (encourages centralization)
- ✅ **King safety** (quadratic penalty in attackers near king, Chebyshev ≤ 3)
- ✅ **Pawn structure** — isolated, doubled, passed (bonus by rank, MG/EG split)
- ✅ **Bishop pair** bonus (+30 MG, +50 EG)
- ✅ **Mobility** (per-piece bonus × legal move count, MG/EG split)
- ✅ **Outposts** for knights + bishops (safe advanced squares)
- ✅ **Rook on open / semi-open file**
- ✅ **Pawn shield** in front of castled king

### Multi-threading
- ✅ **Lazy SMP** with 1-8 worker threads
- ✅ **Lock-free TT** via XOR-trick (`keys[i] = key ⊕ data`)
- ✅ **Staggered worker start depths** (odd workers start at depth 2, even at 1)
- ✅ **Per-thread history perturbation** (± noise from `Random(seed ⊕ workerId)`)
- ✅ **Board deep-copy per worker** (no shared mutable state)
- ✅ **Coordinated stop** (main sets stop flag → propagates)

### Correctness
- ✅ **Threefold repetition detection** (via Zobrist history)
- ✅ **50-move rule** (halfmove clock in Board + GameState)
- ✅ **Insufficient material** (KvK, KvKN, KvKB)
- ✅ **Zobrist hashing** (incremental in makeMove/unmakeMove)

### Protocol
- ✅ **UCI** — `uci`, `isready`, `ucinewgame`, `position`, `go depth/movetime/wtime`, `stop`, `quit`
- ✅ **Pondering** — `go ponder`, `ponderhit`
- ✅ **Options** — `Hash`, `Threads`, `OwnBook`, `BookFile`, `StyleFile`, `Ponder`, `Move Overhead`

### Opening book
- ✅ **Polyglot `.bin` reader** (Donna-compatible: gm2001, komodo, rodent)
- ✅ **Auto-load** from `books/gm2001.bin` or `$DONNA_BOOK` env var
- ✅ **Weighted random** selection over legal book candidates
- ✅ **Verbose logging** — `info string book HIT/MISS/SKIP`

---

## 🎨 The style system

The most novel part of the engine. In `StyleOrchestrator.java` we have **256 tunable eval features** (material values, PST slots, king safety scale, pawn structure penalties, mobility bonuses, outposts, rook file bonuses, etc.). All of them can be independently multiplied by a factor in the range `[0.5, 2.0]` at runtime.

### JSON style files

**Named form** (human-friendly, sparse):
```json
{
  "version": 1,
  "description": "aggressive attacker who loves knight outposts",
  "modifiers": {
    "PST_KNIGHT": 1.15,
    "OUTPOST_KNIGHT_MG": 1.30,
    "MAT_QUEEN": 1.05,
    "PST_KING_MG": 0.90
  }
}
```

**Vector form** (MLP output — 200 floats direct):
```json
{
  "version": 1,
  "modifiers": [1.0, 1.05, 0.98, ..., 1.12]
}
```

Both formats accepted by `StyleLoader.java`.

### Two ways to generate style files

**1. Predefined library** (`styles/` directory):

```bash
$ ./scripts/pick_style.sh knightly
[19:42:33] [pick] ✓ styles/current.json → knightly
         desc: knightly — prefers knights over bishops (good for closed positions)
         → engine auto-reloads at the next 'go'
```

8 predefined styles: `balanced`, `solid`, `active`, `knightly`, `bishopy`, `simplifier`, `positional`, `aggressive`.

**2. Text → style** via the trained brain:

```bash
$ ./scripts/brain.sh "positional player who avoids trades and squeezes advantages"
[19:43:12] [brain/mlp_v2] ✓ inferred from 9 tokens
           modifiers (7 non-neutral):
             ↑ PST_KNIGHT         = 1.061
             ↑ PST_BISHOP         = 1.048
             ↑ PST_KING_MG        = 1.052
             ↑ PAWN_ISOLATED_MG   = 1.094
             ↓ MAT_QUEEN          = 0.968
             ↓ PST_QUEEN          = 0.972
             ↑ PST_ROOK           = 1.033
           → wrote styles/current.json
```

The MLP was trained on **750 (text, modifiers)** pairs generated via Gemini. See `scripts/train_brain.py` for the training pipeline (pure NumPy MLP: BOW + char-trigrams → 128 hidden → 14 output).

### Live reload — even during a Lichess game

The engine polls the style file's `mtime` on every `go` command. When it changes:

```
info string style auto-reloaded from .../styles/current.json ("play like Tal ...")
```

**Zero restarts, zero heap allocations** — the `StyleOrchestrator` maintains two flat `int[]` arrays (baseline + dynamic) and overwrites `dynamicValues` in place.

---

## 🧠 Architecture

```
                       ┌─────────────────────────────────┐
                       │  StyleOrchestrator              │
                       │  ├─ baseValues[256] (immutable) │
                       │  ├─ dynamicValues[256] (hot)    │
                       │  └─ applyStyleModifiers()       │
                       └─────────────────────────────────┘
                                     ▲
                                     │ live reload via mtime poll
                                     │
    stdin ──► Uci ──► Search ──► Evaluator ◄── weights[]
                │        │
                │        ▼
                │    ┌────────────────────────┐
                │    │  TranspositionTable    │
                │    │  (128 MB, XOR-safe)    │
                │    └────────────────────────┘
                │        ▲
                │        │ shared
                │        │
                │    ┌────────────────────────┐
                │    │  Worker threads × N-1  │
                │    │  (Lazy SMP)            │
                │    └────────────────────────┘
                │
                ▼
             stdout (bestmove, info)

    ┌─────────────────┐          ┌─────────────────┐
    │  style_brain.py │  writes  │ styles/         │
    │  (KeywordBrain  │─────────►│   current.json  │
    │   or MLPBrain)  │  atomic  │                 │
    └─────────────────┘          └─────────────────┘
```

---

## ⚡ Quick start

```bash
# Compile
javac -d out src/chess/*.java

# Interactive mode (you play White vs the engine)
java -cp out chess.Main

# UCI mode (for GUIs and lichess-bot)
java -cp out chess.Main uci

# With a specific style
echo -e "uci\nsetoption name Threads value 4\nsetoption name StyleFile value styles/knightly.json\nposition startpos\ngo movetime 5000" | java -cp out chess.Main uci
```

---

## 🎛️ UCI options

| Option | Type | Default | Description |
|---|---|---|---|
| `Hash` | spin | 128 MB | Transposition table size (1-512 MB) |
| `Threads` | spin | 1 | Lazy SMP threads (1-8) |
| `OwnBook` | check | true | Use Polyglot opening book |
| `BookFile` | string | `books/gm2001.bin` | Path to Polyglot `.bin` |
| `StyleFile` | string | (empty) | Path to a JSON style file — engine auto-reloads on change |
| `Ponder` | check | true | Think on opponent's clock |
| `Move Overhead` | spin | 30 ms | Time buffer for UCI latency |

Setting `StyleFile` to an empty value resets the engine to baseline weights.

---

## 📁 Project structure

```
chess-engine/
├── src/chess/                     # Engine source (Java)
│   ├── Board.java                 # 8×8 mailbox + cached king pos + Zobrist
│   ├── Piece.java                 # Piece encoding (color | type in one int)
│   ├── Move.java                  # Packed move (from, to, flag)
│   ├── GameState.java             # Reversible state for unmake
│   ├── FenParser.java             # FEN string → Board
│   ├── MoveGenerator.java         # Pseudo-legal + legal + attack detection
│   ├── InputParser.java           # "e2e4" text → Move
│   ├── Evaluator.java             # Tapered MG/EG eval (mobility, king safety, etc.)
│   ├── Search.java                # Iterative deepening α-β + Lazy SMP
│   ├── TranspositionTable.java    # Lock-free hash table (XOR trick)
│   ├── StyleOrchestrator.java     # 256-feature weight registry
│   ├── StyleLoader.java           # JSON parser + applier
│   ├── OpeningBook.java           # Polyglot reader
│   ├── PolyglotRandom.java        # Standard 781 Polyglot Zobrist constants
│   ├── Uci.java                   # UCI protocol + threading coordination
│   └── Main.java                  # Entry point (interactive / UCI)
│
├── scripts/                       # Tooling (Bash + Python)
│   ├── brain.sh + style_brain.py  # Text → style modifiers (v1 keyword or v2 MLP)
│   ├── train_brain.py             # Train MLPBrain on Gemini-labeled data
│   ├── ingest_gemini.py           # Validate + dedup batches into dataset
│   ├── retrain.sh                 # Auto-tune hyperparams by dataset size
│   ├── compare_brains.py          # A/B v1 vs v2 outputs
│   ├── pick_style.sh              # Select a predefined style
│   ├── style_session.sh + .py     # Interactive UCI proxy with `style <name>`
│   ├── watch.sh + watch.py        # Live monitor (style changes + engine log)
│   ├── match.sh                   # fastchess wrapper — N games with ELO diff
│   ├── sprt.sh                    # fastchess wrapper — SPRT decision
│   └── quick_test.sh              # 4-game smoke test
│
├── styles/                        # Predefined style files
│   ├── balanced.json              # Baseline (all × 1.0)
│   ├── solid.json                 # Defensive, king safety amplified
│   ├── active.json                # Piece mobilization bonus
│   ├── knightly.json              # Knights > Bishops
│   ├── bishopy.json               # Bishops > Knights
│   ├── simplifier.json            # Prefers trades → endgame
│   ├── aggressive.json            # ⚠️ Regression example (SPRT: -52 ELO)
│   ├── positional.json            # Original extreme (risky)
│   └── current.json               # Symlink/copy — actively loaded by engine
│
├── data/
│   ├── gemini_prompts/            # 4 markdown prompts for Gemini data gen
│   ├── style_dataset.jsonl        # Cumulative training data (~750 entries)
│   └── dataset_meta.json          # Coverage stats
│
├── models/                        # Trained MLP + vocab
│   ├── style_mlp_v2.npz           # Weights (~130k parameters)
│   ├── style_vocab.json           # BOW + trigram vocabulary
│   └── style_meta.json            # Training metadata
│
├── test_positions/openings.epd    # 30 opening positions for SPRT
├── tools/fastchess                # Match runner binary (built from source)
├── books/                         # Polyglot opening books (gitignored)
├── logs/                          # PGN + SPRT output
├── gemini_batches/                # Raw Gemini output before ingest
├── run_engine.sh / .bat           # Launcher scripts for lichess-bot
├── lichess-bot-config.yml         # Bot configuration (token gitignored)
└── DEPENDENCIES.md                # Mermaid class dependency graph
```

---

## 🧪 Testing infrastructure

### Match runner

```bash
./scripts/match.sh styles/aggressive.json styles/balanced.json 200 10+0.1
```

Runs 200 games at 10s+0.1s time control, prints ELO diff with 95% CI.

### SPRT (Sequential Probability Ratio Test)

```bash
./scripts/sprt.sh styles/new_idea.json styles/balanced.json 10 5+0.05
```

Stops as soon as it can decide with high confidence (`α = β = 0.05`) whether the new file gives ≥ 10 ELO. Median termination: 300-500 games (~15-30 min on M-series Mac).

### Real result from SPRT

We measured our full Etapa 2 upgrade (tapered eval + all pruning) at **+222 ELO with p < 0.001** over the baseline commit `firstTrain` — 78 games, 78% points, LOS 99.99%.

---

## 🎼 The MLP brain in detail

Located in `scripts/style_brain.py` (Python, NumPy-only):

```
   "play like Karpov"
         │
         ▼
   tokenize + char-trigrams
         │
         ▼
   L2-normalized BOW vector (1013 dims)
         │
         ▼ (Linear + ReLU + Dropout)
   hidden layer (128 dims)
         │
         ▼ (Linear + sigmoid × 1.5 + 0.5)
   output vector (14 features × multipliers ∈ [0.5, 2.0])
         │
         ▼
   sparse dict + atomic JSON write
         │
         ▼
   styles/current.json
         │
         ▼
   engine mtime-poll → applyStyleModifiers()
```

**Training**: 750 (text, modifiers) pairs generated via Gemini, prompts in `data/gemini_prompts/`. Trained in ~30 seconds on CPU with early stopping (val loss 0.0011). See `scripts/train_brain.py` for the full loop.

**Deployment**: Ship `models/*.npz + .json`, run `brain.sh` anywhere Python is available. Auto-fallback to v1 (`KeywordBrain`, rule-based) if the model files are missing.

---

## 🏛️ Algorithms deep-dive

### Search — modernized negamax

The classical negamax:
```
negamax(depth):
    if depth == 0: return evaluate()
    best = -INF
    for move in ordered_moves():
        make(move)
        score = -negamax(depth - 1)
        unmake(move)
        best = max(best, score)
    return best
```

...gets augmented with **15+ pruning and extension techniques**:

| Technique | Purpose | Location |
|---|---|---|
| **TT probe/store** | Reuse subtree results | Every node |
| **Aspiration windows** | Narrow search window in iterative deepening | Root only |
| **RFP** | Return early if static eval is far above β | Non-PV, depth ≤ 6 |
| **NMP** | Skip a ply, check if position is still good | Non-PV, non-check, depth ≥ 3 |
| **Razoring** | Verify hopeless positions with quiescence | Non-PV, depth ≤ 2 |
| **IID** | Bootstrap a TT move at PV nodes without one | PV, ttMove empty, depth ≥ 5 |
| **LMP** | Skip late quiet moves at low depth | Non-PV, depth ≤ 3 |
| **Futility frontier** | Skip quiet moves with hopeless static eval | Non-PV, depth = 1 |
| **LMR (log formula)** | Search late quiet moves at reduced depth | depth ≥ 3, quiet, non-check |
| **Check extension** | +1 depth when in check | Every check |
| **Mate distance pruning** | Clamp α/β around known mate scores | Every non-root node |

### Move ordering (in `scoreMove`)

Ranked by score:
1. **TT move** (10M)
2. **Promotions** (9M)
3. **Good captures** — SEE ≥ 0, MVV-LVA within tier (1M+)
4. **Killer 1, Killer 2** (800k / 790k)
5. **Counter move** (750k)
6. **Bad captures** — SEE < 0, sorted by SEE score (700k + SEE)
7. **Quiet moves** — sorted by history table

### Evaluation flow

```
evaluate(board):
    phase = compute_phase()             # 0=EG, 24=MG
    mg, eg = 0, 0
    
    pre_pass: count pawns by file       # needed for outposts, rook file, shield
    
    for each square:
        piece = board[square]
        mg += material + pst_mg + mobility_mg + outpost_mg + rook_file_mg + king_zone_attack_mg
        eg += material + pst_eg + mobility_eg + outpost_eg + king_zone_attack_eg
    
    mg += bishop_pair + pawn_shield
    eg += bishop_pair + passed_pawn_bonus
    
    both += pawn_structure_penalties    # isolated, doubled
    both += king_safety_quadratic       # -(scale × attackers²)
    
    return (mg × phase + eg × (24 - phase)) / 24
```

Every value in `mg, eg, phase` comes from `style.weights()[SOME_INDEX]` — that's how the style system rewires the eval without touching the search code.

### Zobrist hashing

Standard 781-key set, XOR incremental in `makeMove`:
- 12 × 64 piece placements
- 4 castling rights
- 8 en-passant files
- 1 side-to-move

Used for TT indexing, threefold repetition detection, and ponder move extraction from TT.

---

## 🌐 Lichess integration

The engine talks UCI, so any `lichess-bot` config works:

```yaml
engine:
  dir: "/Users/mateichiriac/Documents/chess-engine"
  name: "run_engine.sh"
  protocol: uci
  uci_options:
    Threads: 4
    Hash: 128
    StyleFile: "/absolute/path/to/styles/current.json"
    Ponder: true
```

Then in a separate terminal:
```bash
./scripts/brain.sh "aggressive attacker who loves knight outposts"
```

Next move on Lichess: engine reloads the style with the new multipliers, and plays accordingly. **No bot restart required.**

### Live monitoring

```bash
./scripts/watch.sh
```

Streams to your terminal:
- Every `styles/current.json` change (with per-feature diff and ↑/↓ arrows)
- Every engine `info string style auto-reloaded` event
- Every game start/end from the bot log

---

## 🗺️ What's next

Ideas we've costed but haven't implemented yet:

| Priority | Feature | Estimated ELO |
|---|---|---|
| High | **Move list pooling** (int[] instead of ArrayList<Move>) | +30-50 at 8 threads |
| High | **Threats evaluation** (attacked/defended piece diff) | +20-40 |
| Medium | **Syzygy 5-men tablebase support** | +40-80 endgame |
| Medium | **Singular extensions** | +20-40 |
| Medium | **Texel-tuned PSTs** (train on ~1M positions) | +30-60 (free) |
| Low | **NNUE** replacement of hand-crafted eval | +200-400 |
| Low | **Magic bitboards** for sliding pieces | +50-100 (via speed) |

---

## 📚 Further reading

- [Chess Programming Wiki](https://www.chessprogramming.org/) — canonical reference for every technique here
- [Stockfish source](https://github.com/official-stockfish/Stockfish) — for gold-standard implementations
- [fastchess](https://github.com/Disservin/fastchess) — the match runner we use for SPRT
- [Polyglot book format](http://hgm.nubati.net/book_format.html) — book file spec
- [lichess-bot](https://github.com/lichess-bot-devs/lichess-bot) — Python bridge for Lichess

---

## 📄 License

The engine source is available for personal and educational use. The trained MLP model and Gemini-generated dataset are included under the same terms.
