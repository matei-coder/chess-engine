# Batch 4 — Edge cases, paraphrases, casual language

## Context

Batches 1-3 covered chess-specific terminology and explicit styles. This batch
trains the model to handle:
1. **Casual language** users would actually type ("vreau să joace agresiv")
2. **Paraphrases** of common concepts (multiple ways to say "defensive")
3. **Compound styles** ("aggressive but only with knights")
4. **Negations** ("not too aggressive", "avoid trading queens")
5. **Vague/ambiguous descriptions** ("just play normal", "play smart")

## Feature schema

- `MAT_PAWN`, `MAT_KNIGHT`, `MAT_BISHOP`, `MAT_ROOK`, `MAT_QUEEN`
- `PST_PAWN`, `PST_KNIGHT`, `PST_BISHOP`, `PST_ROOK`, `PST_QUEEN`, `PST_KING_MG`
- `PAWN_ISOLATED_MG`, `PAWN_DOUBLED_MG`

Multiplier range: `[0.85, 1.15]`.

## Target distribution (50 entries)

### A) Casual user input — 15 entries
How real users would phrase requests (not chess theorists):
- "play aggressive", "fii agresiv", "be careful", "joacă tare"
- "I want it to attack", "nu pierde", "to play safe"
- "make it crazy", "joacă nebunesc", "be defensive please"
- Short, imperative, possibly grammatically casual

### B) Paraphrases — 10 entries
Same concept, very different words:
- "solid play" / "calm and measured" / "no risks please" / "careful style"
- "aggressive" / "go for the throat" / "ataca tare" / "play sharply"
- "knight player" / "loves cavalry" / "îi plac caii" / "horse advocate"

### C) Compound/conditional styles — 10 entries
Multiple constraints in one description:
- "aggressive but solid king" → PST_KING_MG up, PST_KNIGHT up
- "play with knights but avoid queen sacrifices" → PST_KNIGHT up, MAT_QUEEN up
- "endgame focused with strong pawn play" → PST_KING_MG down, PST_PAWN up
- "calm in opening, sharp in middlegame" → reasonable balanced output
- "attacker who hates losing pawns" → PST_KNIGHT up, PAWN_DOUBLED_MG/ISOLATED_MG up

### D) Negations — 8 entries
What NOT to do:
- "don't play passive" → PST_KNIGHT/PST_BISHOP higher (more active)
- "nu vreau să trade reginele" → MAT_QUEEN up
- "avoid pawn weaknesses" → PAWN_ISOLATED_MG/PAWN_DOUBLED_MG up
- "don't be a coward" → PST_KING_MG slightly down, PST_KNIGHT up
- "no early queen sorties" → PST_QUEEN down

### E) Vague — 7 entries
Hard cases — should produce near-baseline (minor tweaks):
- "play normal" → 1-2 small tweaks only
- "play smart" → small balanced tweaks
- "be good" → minimal modifiers
- "play like a human" → small tweaks toward natural style

These should produce SPARSE modifiers (only 0-2 features non-1.0).

## Style + language requirements

- ~50% RO, ~50% EN
- Length variance: from 2 words ("be solid") to 25 words ("a thoughtful player
  who plays slowly, considering all options before committing to a plan")
- Include COMMON spelling variants: "agresiv", "agressiv", "agressive"
- Include some typos that real users would make: "agresif", "defensiv", "knigt"
- Mix imperatives ("play X"), declaratives ("plays X"), preferences
  ("îmi place când...", "I prefer...")

## Output format

JSON array, exactly 50 entries:
```json
[
  {
    "text": "play aggressive",
    "modifiers": {"PST_KNIGHT": 1.10, "PST_BISHOP": 1.08, "MAT_QUEEN": 1.03}
  },
  {
    "text": "joacă cu cai dar nu sacrifica regina",
    "modifiers": {"PST_KNIGHT": 1.12, "MAT_KNIGHT": 1.05, "MAT_QUEEN": 1.05}
  },
  {
    "text": "play normal",
    "modifiers": {"PST_KING_MG": 1.02}
  },
  {
    "text": "don't lose pieces stupidly",
    "modifiers": {"MAT_KNIGHT": 1.03, "MAT_BISHOP": 1.03, "MAT_ROOK": 1.03}
  },
  ...
]
```

**Hard rules**:
- 50 entries, unique texts
- All multipliers ∈ [0.85, 1.15]
- AT LEAST 5 entries should have ONLY 1 modifier (vague/minimal cases)
- AT LEAST 8 entries should be ≤ 4 words (casual short)
- AT LEAST 5 entries should be ≥ 15 words (verbose)
- AT LEAST 5 entries must include a NEGATION ("don't", "nu", "avoid", "no")
- Mix RO/EN ~50/50
