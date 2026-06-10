# Master prompt — reusable pentru generare batches de 50 entries

## CONTEXT (pentru Gemini)

I am training a small MLP that maps free-text descriptions of a chess
playing style → numerical multipliers on a chess engine's evaluation
function. The MLP is **under-fitting** because the previous dataset (200
entries) has multipliers clustered close to 1.0 — the model just learns
to predict the mean.

I need 50 NEW examples (text + modifier dict) optimized for:
1. **Magnitude diversity**: USE the full safe range, not just ±5%
2. **Direction balance**: equal amounts of UP and DOWN multipliers
3. **Feature sparsity**: 1-3 modifiers per entry typically (clearer signal than 5+)
4. **Text diversity**: varied vocabulary, lengths, languages

## FEATURE SCHEMA

Use ONLY these 13 feature names (MAT_KING is locked, never include):

```
MAT_PAWN, MAT_KNIGHT, MAT_BISHOP, MAT_ROOK, MAT_QUEEN
PST_PAWN, PST_KNIGHT, PST_BISHOP, PST_ROOK, PST_QUEEN, PST_KING_MG
PAWN_ISOLATED_MG, PAWN_DOUBLED_MG
```

**MAT_X**: material value (higher = engine values that piece more)
**PST_X**: positional importance (higher = engine cares more about good squares for that piece)
**PST_KING_MG**: king safety in middlegame (higher = engine keeps king safer; lower = engine activates king early, endgame-oriented)
**PAWN_ISOLATED_MG / PAWN_DOUBLED_MG**: penalty multiplier for weak pawns (higher = engine HATES these more)

## CRITICAL RULES

1. Range: `[0.85, 1.15]`. **NEVER** outside.
2. **Use full range**: at LEAST 10 examples should have a multiplier ≥ 1.10 or ≤ 0.90 (i.e., near the boundary)
3. Sparse: AT LEAST 20 of the 50 entries should have only 1 or 2 modifiers
4. Include modifiers BELOW 1.0 in AT LEAST 20 of 50 entries (current dataset is biased toward positive multipliers)
5. NO duplicate texts within batch
6. Mix RO + EN approximately 50/50

## BATCH FOCUS (rotation — change every run)

**On each new run, focus on ONE of the following themes** (rotate
through them so different batches cover different angles):

### Theme A: Strong piece preferences (use sparse, near-bound modifiers)
- "loves knights" / "hates knights" → PST_KNIGHT 1.13 or 0.87
- "queen sacrificer" → MAT_QUEEN 0.88, PST_KNIGHT/BISHOP up
- "bishop pair hoarder" → MAT_BISHOP 1.12, no other modifier

### Theme B: Famous players (1-2 modifiers, strong magnitude)
Carlsen, Kasparov, Karpov, Petrosian, Capablanca, Fischer, Tal, Smyslov,
Botvinnik, Kramnik, Anand, Caruana, Magnus, Nakamura, Aronian, Polgar
Each name → 1-3 modifiers characteristic, with magnitude ≥ ±0.08

### Theme C: Opening systems (specific style of each)
Sicilian Najdorf, Caro-Kann, French, Berlin, KID, Slav, English,
Catalan, Modern, Hippopotamus, Pirc, Scandinavian, London System, QGA
Each → 2-3 modifiers describing the style of that opening

### Theme D: Time-control / opponent-adaptive
"bullet — fast attacks", "classical — patient maneuvering",
"versus computer — solid", "versus weaker player — punish",
"versus stronger — solid + draw seeker"

### Theme E: Negative/avoidance (UNDER-represented in current data)
"don't trade queens", "avoid pawn weaknesses", "no passive rooks",
"refuses to fianchetto", "anti-knight player", "anti-bishop player"
Each → modifiers with multiple values BELOW 1.0

### Theme F: Phase-specific
"opening expert" / "middlegame specialist" / "endgame technician"
"transition into endgame" / "avoids endgames"
"opening trap setter" / "endgame grinder"

### Theme G: Single-feature focus (sparsest, strongest signal)
Each entry has EXACTLY 1 modifier, with magnitude ≥ ±0.08:
"hates isolated pawns": {"PAWN_ISOLATED_MG": 1.14}
"queen on h-file": {"PST_QUEEN": 1.13}
"passive rooks player": {"PST_ROOK": 0.88}
... 50 entries each focusing on 1 single feature

## FEW-SHOT EXAMPLES

```json
[
  {
    "text": "Anti-knight player who always trades knights for bishops",
    "modifiers": {"MAT_KNIGHT": 0.88, "MAT_BISHOP": 1.12}
  },
  {
    "text": "Karpov — quiet positional pressure with knights",
    "modifiers": {"PST_KNIGHT": 1.13, "PST_ROOK": 1.06, "PST_KING_MG": 1.05}
  },
  {
    "text": "Sacrifică regina pentru atac decisiv",
    "modifiers": {"MAT_QUEEN": 0.86, "PST_KNIGHT": 1.10, "PST_BISHOP": 1.08}
  },
  {
    "text": "ignoră structura pionilor",
    "modifiers": {"PAWN_DOUBLED_MG": 0.86, "PAWN_ISOLATED_MG": 0.86}
  },
  {
    "text": "loves the bishop pair",
    "modifiers": {"MAT_BISHOP": 1.12}
  },
  {
    "text": "bullet maniac — fast tactical chaos",
    "modifiers": {"PST_QUEEN": 1.13, "MAT_PAWN": 0.92, "PST_KING_MG": 0.92}
  },
  {
    "text": "tehnician de final pur",
    "modifiers": {"PST_KING_MG": 0.87, "MAT_PAWN": 1.10}
  },
  {
    "text": "fortăreață Petrosian — nu permite nimic",
    "modifiers": {"PST_KING_MG": 1.14, "PAWN_ISOLATED_MG": 1.10}
  },
  {
    "text": "joacă fără regina",
    "modifiers": {"MAT_QUEEN": 0.86}
  },
  {
    "text": "Tal — sacrifices for kingside attack",
    "modifiers": {"MAT_PAWN": 0.88, "PST_QUEEN": 1.13, "PST_KING_MG": 0.90}
  }
]
```

## OUTPUT FORMAT

Strict JSON array of exactly 50 entries:
```json
[
  {"text": "...", "modifiers": {"FEAT": float, ...}},
  ...
]
```

No surrounding markdown, no commentary — just the JSON array.

## VERIFICATION CHECKLIST (apply mentally before responding)

- [ ] Exactly 50 entries?
- [ ] All texts unique within batch?
- [ ] ~25 RO + ~25 EN?
- [ ] At least 10 entries have a multiplier ≥ 1.10 or ≤ 0.90?
- [ ] At least 20 entries have only 1 or 2 modifiers (sparse)?
- [ ] At least 20 entries include at least one multiplier < 1.0?
- [ ] No use of MAT_KING (locked)?
- [ ] All multipliers in [0.85, 1.15]?
- [ ] No duplicates with obvious paraphrases?

If any check fails, REDO that part.
