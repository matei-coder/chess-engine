
source /Users/mateichiriac/Documents/chess-engine/.env
python3 ~/lichess-bot/lichess-bot.py --config /Users/mateichiriac/Documents/chess-engine/lichess-bot-config.yml


# Chess Engine

A chess engine written in Java, capable of playing via a console interface or over the Lichess platform using the UCI protocol.

---

## Features

- Full legal move generation (including castling, en passant, and promotions)
- Check, checkmate, and stalemate detection
- Position evaluation with piece-square tables
- Alpha-beta search with iterative deepening and move ordering
- UCI protocol support for integration with chess GUIs and Lichess

---

## Project Structure

```
src/chess/
├── Main.java          — Entry point (console mode or UCI mode)
├── Board.java         — Board representation (8x8 int array)
├── Piece.java         — Piece encoding as integers
├── Move.java          — Move encoding (from, to, flag packed in one int)
├── GameState.java     — Snapshot of irreversible state for unmakeMove
├── FenParser.java     — FEN string parser
├── MoveGenerator.java — Pseudo-legal + legal move generation, attack detection
├── InputParser.java   — Parses "e2e4"-style strings into legal Move objects
├── Evaluator.java     — Static position evaluation
├── Search.java        — Iterative deepening alpha-beta search
└── Uci.java           — UCI protocol handler
```

---

## How to Build and Run

**Compile:**
```bash
javac -d out src/chess/*.java
```

**Run in console mode (you play White, engine plays Black):**
```bash
java -cp out chess.Main
```

**Run in UCI mode (for GUIs or lichess-bot):**
```bash
java -cp out chess.Main uci
```

On Windows you can also use the included `run_engine.bat` script, which is the entry point used by `lichess-bot-config.yml`.

---

## Algorithms

### Minimax

The engine uses the **Negamax** formulation of minimax — a simplified variant where the score is always returned from the perspective of the side to move. At each node, the engine picks the move that maximizes its own score, knowing the opponent will do the same on their turn.

```
negamax(board, depth):
    if depth == 0: return evaluate(board)
    best = -INF
    for each move:
        make(move)
        score = -negamax(board, depth - 1)
        unmake(move)
        best = max(best, score)
    return best
```

Checkmate is detected when no legal moves exist and the side to move is in check (score: `-INF + depth`, so the engine prefers to be mated later). Stalemate returns 0.

---

### Alpha-Beta Pruning

Alpha-beta pruning cuts branches that cannot influence the final result:

- **alpha** — the best score the current player can already guarantee
- **beta** — the best score the opponent can already guarantee

When `alpha >= beta`, the remaining moves in the current node are skipped (beta cutoff). This reduces the effective search tree from O(b^d) to approximately O(b^(d/2)), allowing roughly **twice the search depth** for the same computation time.

---

### Iterative Deepening

Instead of searching directly to a fixed depth, the engine searches depth 1, then depth 2, then depth 3, and so on, until the time limit expires. Each completed iteration produces a best move, so the engine always has a valid answer to return even if time runs out mid-search.

This also improves alpha-beta efficiency, since the best move found at depth N is tried first at depth N+1, generating early cutoffs.

```
for depth = 1, 2, 3, ...:
    bestMove = alphaBeta(board, depth)
    if time expired: break
return lastCompletedBestMove
```

---

### Move Ordering — MVV-LVA

Move ordering dramatically improves alpha-beta cutoff rates. Moves are sorted before searching using the **MVV-LVA** (Most Valuable Victim — Least Valuable Attacker) heuristic:

- Captures are scored as `10 * victim_value - attacker_value`
- This prioritizes capturing a queen with a pawn over capturing a pawn with a queen
- Promotions are tried first (highest priority)
- En passant is treated as a pawn captures pawn

Better move ordering means more beta cutoffs early, which shrinks the search tree significantly.

---

### Static Evaluation

The evaluation function scores a position by summing up material and positional bonuses for every piece on the board:

**Material values (in centipawns):**

| Piece  | Value |
|--------|-------|
| Pawn   | 100   |
| Knight | 320   |
| Bishop | 330   |
| Rook   | 500   |
| Queen  | 900   |
| King   | 20000 |

**Piece-Square Tables (PST):** Each piece type has a 64-entry table that adds or subtracts a bonus based on its position. For example:
- Pawns are rewarded for advancing toward promotion
- Knights are penalized on the edges, rewarded in the center
- The king is penalized in the center during the middlegame (encourages castling)

The score is computed from White's perspective and flipped for Black by mirroring the square index (`63 - sq`).

---

### Legal Move Filtering

The engine generates pseudo-legal moves (all moves that follow piece movement rules, ignoring checks), then filters them by making each move and checking whether the moving side's king is left in check. Only moves that pass this test are legal.

Castling has additional constraints: the king cannot castle while in check, and cannot pass through or land on an attacked square.

---

## UCI Protocol

[UCI (Universal Chess Interface)](https://www.chessprogramming.org/UCI) is the standard protocol for communication between a chess engine and a GUI or bot framework. The engine communicates via stdin/stdout.

**Supported commands:**

| Command       | Description                                             |
|---------------|---------------------------------------------------------|
| `uci`         | Engine identifies itself, responds with `uciok`         |
| `isready`     | Engine responds `readyok` when ready                    |
| `ucinewgame`  | Resets the board to starting position                   |
| `position`    | Sets up a position from `startpos` or a FEN string, then applies a list of moves |
| `go`          | Starts search; supports `depth`, `movetime`, `wtime`/`btime`/`winc`/`binc` |
| `stop`        | Stops search (acknowledged, not yet threaded)           |
| `quit`        | Exits the engine                                        |

**Time management:** When given `wtime`/`btime`, the engine allocates approximately `remaining_time / 30 + increment` milliseconds per move — a simple but effective formula for timed games.

---

## Lichess Integration

The engine connects to Lichess using [lichess-bot](https://github.com/lichess-bot-devs/lichess-bot), a Python bridge that speaks UCI to your engine and the Lichess API on the other side.

**Setup:**
1. Create a Lichess bot account and generate an API token.
2. Edit `lichess-bot-config.yml` and replace `PUNE_TOKENUL_TAU_AICI` with your token.
3. Install and run lichess-bot pointing at this config file.

The engine accepts bullet, blitz, and rapid challenges in both casual and rated modes (`lichess-bot-config.yml`).
