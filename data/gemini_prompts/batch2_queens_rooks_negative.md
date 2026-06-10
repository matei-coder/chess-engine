# Batch 2 — Queen & Rook activity + NEGATIVE multipliers

## Context

I am training a chess engine's "style brain" — a model that maps a free-text
description of a desired playing style into multipliers on the engine's
evaluation function.

The first batch (50 entries) covered most features well, BUT:
- PST_QUEEN and PST_ROOK only appeared with POSITIVE multipliers (≥ 1.05).
- We need examples with NEGATIVE direction (multipliers in [0.85, 0.99]).
- We also need more MAT_KNIGHT examples (it was under-represented).

## Feature schema (must use only these names)

- `MAT_PAWN`, `MAT_KNIGHT`, `MAT_BISHOP`, `MAT_ROOK`, `MAT_QUEEN` — material values
- `PST_PAWN`, `PST_KNIGHT`, `PST_BISHOP`, `PST_ROOK`, `PST_QUEEN` — piece positional importance
- `PST_KING_MG` — king safety in middlegame
- `PAWN_ISOLATED_MG`, `PAWN_DOUBLED_MG` — penalty multipliers for weak pawns

Multiplier safe range: `[0.85, 1.15]`. NEVER go outside this range.

Higher multiplier = engine values that feature MORE.
Lower multiplier = engine values that feature LESS.

## Target distribution for this batch (50 entries)

Focus on these scenarios:

### A) Queen play — 15 entries (mix RO + EN)
Cover BOTH directions:
- POSITIVE PST_QUEEN: active queen sorties, queen on open files, queen
  leading attacks, "polgar style" early queen mobility
- NEGATIVE PST_QUEEN: prefer to keep queen safe and passive, avoid early
  queen development, trade queens early, conservative queen placement
- Mix with MAT_QUEEN: some players overvalue queens (1.10-1.15), some
  prefer to trade queens for two minor pieces (0.85-0.95)

### B) Rook play — 15 entries (mix RO + EN)
Cover BOTH directions:
- POSITIVE PST_ROOK: rook lifts, rook on 7th, doubled rooks on open files,
  active rook centralization in endgame
- NEGATIVE PST_ROOK: passive rook on home rank, rook on closed file,
  scared of trading rooks
- Mix MAT_ROOK with PAWN_DOUBLED_MG (rook prefers open files = fewer pawn weaknesses to defend)

### C) Knight play — 10 entries
Lots of variety needed (only 6 in first batch):
- Knight outposts, knight maneuvers (Karpov style)
- Anti-knight: prefer trading knights for bishops in open positions
- Negative MAT_KNIGHT (0.88-0.95) for "values bishops higher than knights"

### D) Mixed / general — 10 entries
Free-form, diverse, but with at least ONE of {PST_QUEEN, PST_ROOK, MAT_KNIGHT}
non-neutral in each entry.

## Style + language requirements

- ~50% Romanian, ~50% English
- Length: mix 5-20 words
- Style: mix formal chess vocabulary AND casual descriptions ("plays like X",
  "loves to do Y")
- Use chess concepts that might be NEW to a simple model:
  - "minority attack", "fianchetto preference", "outpost",
  - "back-rank mating threats", "underpromotion",
  - "Karpov-like", "Tal-like", "Kasparov-like",
  - "anti-Sicilian aggression", "Berlin defense passive style",
  - "atac de minoritate", "fianchetto", "tehnică de finale"

## Output format

JSON array, exactly 50 entries:
```json
[
  {
    "text": "Plays active queen with early sorties to the kingside",
    "modifiers": {"PST_QUEEN": 1.13, "MAT_QUEEN": 1.05, "PST_KING_MG": 0.95}
  },
  {
    "text": "Preferă să schimbe regina devreme pentru două piese ușoare bune",
    "modifiers": {"MAT_QUEEN": 0.92, "MAT_KNIGHT": 1.05, "MAT_BISHOP": 1.05}
  },
  {
    "text": "Karpov-style positional rook maneuvering with patient improvement",
    "modifiers": {"PST_ROOK": 1.12, "PST_KNIGHT": 1.05, "PST_KING_MG": 1.03}
  },
  ...
]
```

**Hard rules**:
- Include ONLY non-1.0 modifiers (sparse)
- 2-5 modifiers per entry typical (rarely 1, rarely 6+)
- No duplicate texts within batch
- Stay within [0.85, 1.15] for every multiplier
- ENSURE at least 7 entries have PST_QUEEN < 1.0 (negative direction)
- ENSURE at least 7 entries have PST_ROOK < 1.0 (negative direction)
- ENSURE at least 5 entries have MAT_KNIGHT < 1.0 (negative direction)
