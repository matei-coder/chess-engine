# Batch 3 — Game phases (opening/middlegame/endgame) + pawn structure depth

## Context

This is batch 3 of training data for a chess engine "style brain". Previous
batches covered general styles and queen/rook activity. This batch focuses on
**game phase preferences** and **pawn structure understanding**, which are
under-represented.

## Feature schema (must use only these names)

- `MAT_PAWN`, `MAT_KNIGHT`, `MAT_BISHOP`, `MAT_ROOK`, `MAT_QUEEN` — material values
- `PST_PAWN`, `PST_KNIGHT`, `PST_BISHOP`, `PST_ROOK`, `PST_QUEEN` — positional importance
- `PST_KING_MG` — king safety in middlegame (LOW = king activates earlier = endgame-oriented)
- `PAWN_ISOLATED_MG`, `PAWN_DOUBLED_MG` — multipliers on penalty for weak pawns
  (HIGH = engine HATES weak pawns more; LOW = engine tolerates pawn weaknesses)

Multiplier safe range: `[0.85, 1.15]`. NEVER exceed.

## Target distribution (50 entries)

### A) Opening preferences — 12 entries
- Hypermodern (lets opponent build center): PST_PAWN slightly lower, PST_KNIGHT
  + PST_BISHOP slightly higher
- Classical (e4/d4 center): PST_PAWN higher, PST_KING_MG very high (safety!)
- Gambit-style (sacrifices pawn for development): MAT_PAWN lower (0.85-0.95),
  PST_KNIGHT/PST_BISHOP higher
- Solid setups (Slav, Caro-Kann): high PAWN_DOUBLED_MG, PAWN_ISOLATED_MG,
  PST_KING_MG

### B) Middlegame styles — 13 entries
- Maneuvering (Karpov, Petrosian): high PST_KNIGHT, slight reduction PST_PAWN
- Sharp tactical (Tal, Shirov): high PST_QUEEN, PST_BISHOP, PST_KNIGHT,
  PST_KING_MG slightly lower (calculated risks)
- Strategic squeezing: high PAWN_ISOLATED_MG, PAWN_DOUBLED_MG (target weaknesses)
- Quick attack: PST_KING_MG higher (own king safety) + bishop activity

### C) Endgame transitions — 15 entries
**Important**: endgame-oriented players want king activation:
- "Loves endgames" / "iubește finaluri" → PST_KING_MG = 0.88-0.95 (king activates)
- "Endgame technique" / "tehnică de finale" → MAT_KNIGHT and MAT_BISHOP slightly higher
  (minor pieces matter more in endgames)
- "Pawn endgame specialist" → PST_PAWN higher, PAWN_DOUBLED_MG higher
- "Avoids endgames, wants middlegame complications" → PST_KING_MG higher (1.08-1.12),
  PST_QUEEN higher (likes complicated positions)

### D) Pawn structure focused — 10 entries
- "Hates isolated pawns" → PAWN_ISOLATED_MG = 1.10-1.15
- "Tolerates pawn weaknesses for activity" → PAWN_ISOLATED_MG = 0.88-0.95,
  PAWN_DOUBLED_MG = 0.88-0.95, PST_KNIGHT/PST_BISHOP higher (active pieces compensate)
- "Pawn chain master" (Closed Sicilian, KID) → PST_PAWN higher,
  PST_KNIGHT higher, PST_BISHOP slightly lower
- "Pawn break specialist" → PST_PAWN higher

## Style + language requirements

- ~50% Romanian, ~50% English
- Length: mix from 4-25 words; INCLUDE some longer "philosophical" descriptions
- Mix chess vocabulary with everyday language
- USE NAMES of famous players as style anchors:
  - English: Karpov, Petrosian, Carlsen, Capablanca, Smyslov (positional);
    Tal, Kasparov, Shirov, Morozevich (tactical); Kramnik (solid)
  - Romanian: "stil Karpov", "joacă Petrosian-style", "tactic ca Tal"
- USE opening/system names: Berlin, Caro-Kann, KID, Slav, Sicilian Najdorf,
  Catalan, English, Modern, Hippopotamus

## Output format

JSON array, exactly 50 entries:
```json
[
  {
    "text": "Hypermodern player who lets opponent overextend in center then strikes",
    "modifiers": {"PST_PAWN": 0.93, "PST_KNIGHT": 1.08, "PST_BISHOP": 1.10}
  },
  {
    "text": "Iubește finalurile, intră rapid în endgame cu tehnică perfectă",
    "modifiers": {"PST_KING_MG": 0.90, "MAT_KNIGHT": 1.05, "MAT_BISHOP": 1.05, "MAT_QUEEN": 0.97}
  },
  {
    "text": "Petrosian-style fortress maker, prevents all counterplay before advancing",
    "modifiers": {"PST_KING_MG": 1.12, "PST_KNIGHT": 1.10, "PAWN_ISOLATED_MG": 1.08}
  },
  {
    "text": "Sacrifică structura pionilor pentru atac asupra regelui advers",
    "modifiers": {"PAWN_DOUBLED_MG": 0.88, "PAWN_ISOLATED_MG": 0.88, "PST_QUEEN": 1.10, "PST_KNIGHT": 1.08}
  },
  ...
]
```

**Hard rules**:
- Sparse modifiers (2-5 per entry typical)
- Stay strictly in [0.85, 1.15]
- ENSURE at least 8 entries have PST_KING_MG < 1.0 (endgame-oriented)
- ENSURE at least 6 entries reference a famous player or named system
- ENSURE all 50 entries are UNIQUE (no near-duplicates)
- Mix RO/EN approximately 50/50
