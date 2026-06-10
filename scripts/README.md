# Testing infrastructure

A/B și SPRT testing pentru engine, folosind [fastchess](https://github.com/Disservin/fastchess)
(fork modern al cutechess-cli, mai rapid, CLI compatibil).
Permite să măsori obiectiv (cu p-value) dacă o schimbare (style file, code change, etc.)
aduce sau pierde ELO față de baseline.

---

## Setup (o singură dată)

### 1. Instalează / build fastchess

Binarul e deja inclus în repo la `tools/fastchess` (build local din source pe Apple Silicon).
Pentru rebuild sau update:

```bash
cd /tmp && git clone --depth=1 https://github.com/Disservin/fastchess.git
cd fastchess && make -j$(sysctl -n hw.physicalcpu)
cp fastchess <chess-engine-repo>/tools/fastchess
```

Verifică:
```bash
./tools/fastchess -version
```

### 2. Verifică engine-ul

```bash
# Recompilează dacă e nevoie:
javac -d out src/chess/*.java

# Test rapid manual (1 secundă):
printf 'uci\nposition startpos\ngo depth 8\nquit\n' | java -cp out chess.Main uci
```

### 3. Sanity check end-to-end

```bash
./scripts/quick_test.sh
```
Rulează 4 jocuri rapide (1 secundă fiecare) între `aggressive.json` și `balanced.json`,
salvează PGN în `logs/`. Confirmă că cutechess + engine + style files cooperează.

---

## Live style switching pentru jocuri Lichess

Folosești bot-ul pe Lichess și vrei să schimbi stilul **între mutări, fără a opri bot-ul**?
Setup-ul:

### 1. Configurează lichess-bot să folosească `styles/current.json`

În `lichess-bot-config.yml`:
```yaml
engine:
  uci_options:
    Threads: 1
    StyleFile: "/Users/mateichiriac/Documents/chess-engine/styles/current.json"
```

Bot-ul va pasa acest path engine-ului la fiecare joc. Engine-ul îl ține "în
observație" — verifică mtime-ul la fiecare comandă `go`.

### 2. Schimbă stilul cu `pick_style.sh`

```bash
./scripts/pick_style.sh knightly       # direct
./scripts/pick_style.sh                # interactiv (listă + meniu)
./scripts/pick_style.sh --list         # doar listă
```

Scriptul suprascrie atomic `styles/current.json` cu stilul ales (tmp file + rename).

### 3. Engine-ul detectează automat

La următoarea mutare după schimbare, în logul lichess-bot vei vedea:
```
info string style auto-reloaded from .../styles/current.json ("knightly — ...")
```

Nu mai necesită restart de bot, restart de engine, sau orice intervenție manuală.

### Cum funcționează

- `Uci.handleGo()` cheamă `maybeReloadStyleFile()` la fiecare `go`
- `Files.getLastModifiedTime()` e cost neglijabil (<1µs)
- Dacă mtime ≠ ultimul observat → reload via `StyleLoader`
- Re-aplicare in-place pe `dynamicValues[]` din `StyleOrchestrator` (zero alocări)
- Atomic write garantează că engine-ul nu citește niciodată JSON parțial

---

## Live watcher

Vrei să vezi în timp real ce se întâmplă cu stilul + ce face engine-ul?

```bash
./scripts/watch.sh                    # default — style file + bot log
./scripts/watch.sh --no-bot           # doar style file
./scripts/watch.sh --bot-log <path>   # custom bot log
```

Output pe terminal (cu culori):
- **`STYLE CHANGED`** — când `current.json` se modifică (brain sau pick), arată descrierea + diff per feature (`+ new`, `- removed`, `~ changed` cu deltas)
- **`ENGINE`** — când engine-ul (via lichess-bot) loghează `info string style auto-reloaded` sau erori
- **`GAME`** — când lichess-bot anunță început/sfârșit de joc

Pune-l într-un terminal separat în timp ce bot-ul joacă, schimbi stiluri din alt terminal — vezi toată activitatea pe un singur ecran.

---

## NLP brain — text → style automat

Modelul "homemade" (`scripts/brain.sh` → `style_brain.py`) traduce text liber
(RO + EN) în vector de stil și-l scrie în `styles/current.json`. Combinat cu
auto-reload, asta înseamnă: **scrii o frază, engine-ul își schimbă stilul**.

### Folosire

```bash
./scripts/brain.sh "agresiv cu cai"             # RO
./scripts/brain.sh "defensive solid endgame"    # EN
./scripts/brain.sh "pozitional cu nebuni in deschise"  # mix
./scripts/brain.sh -i                           # mod interactiv (REPL)
./scripts/brain.sh --list                       # vezi keyword-urile recunoscute
echo "patient slow" | ./scripts/brain.sh        # via stdin
```

### Cum funcționează (v1 — keyword-based)

1. **Tokenize** text → lista de cuvinte (lower-case, fără punctuație)
2. **Canonicalize**: sinonime RO/EN → keyword canonic (ex. "cai" → "knight")
3. **Lookup**: fiecare keyword are o "semnătură" de feature deltas
   (ex. `knight` → `MAT_KNIGHT +0.05, PST_KNIGHT +0.10, MAT_BISHOP -0.03`)
4. **Sum + clamp**: adunăm delta-urile pe fiecare feature, clamp la `±0.20`,
   conversie la multiplicator (`1.0 + delta`), clamp final la `[0.80, 1.25]`
5. **Atomic write** în `styles/current.json` (tmp + rename)
6. **Engine auto-reload** la următorul `go`

### Keywords recunoscute (v1)

Stil: `aggressive`, `defensive`, `solid`, `tactical`, `positional`, `patient`,
`attack`, `simplify`, `trade`, `endgame`, `open`, `closed`, `fast`, `slow`
Piese: `knight`, `bishop`, `rook`, `queen`, `pawn`

Plus sinonime RO: `agresiv`, `defensiv`, `cal/cai`, `nebun/nebuni`, etc.

### Roadmap

**v2 (viitor)**: înlocuim tabelul de keywords cu un MLP propriu antrenat pe
date sintetice. Interfața `StyleBrain` din `style_brain.py` e gata pentru
swap (clasă `KeywordBrain` → `MLPBrain`). Workflow viitor:

1. Generăm dataset: (text, style_vector) — combinăm shuffled descrieri + variante
2. Antrenăm MLP simplu (numpy, ~100 LOC)
3. Înlocuim `KeywordBrain` cu `MLPBrain` care încarcă greutățile din `.npz`
4. Output identic, generalizare mai bună la formulări neașteptate

---

## Interactive style session

Pentru a explora stilurile manual (eval & mutări pe poziție data) cu live switching:

```bash
./scripts/style_session.sh
```

Comenzi în sesiune:
| Comandă | Efect |
|---|---|
| `list` (sau `styles`) | Afișează stilurile disponibile (marker `*` pe cel activ) |
| `style <nume_sau_număr>` | Aplică live un stil (echivalent `setoption StyleFile`) |
| `bench` | Rulează `go depth 8` pe Kiwipete (poziție tactică) |
| `bench startpos` | Bench pe poziția inițială |
| `bench <FEN>` | Bench pe FEN custom |
| `compare <A> <B>` | Bench pe fiecare stil consecutiv, vezi diferența |
| `help` | Acest meniu |
| `quit` | Iesire |

Orice altă comandă e trimisă ca UCI direct (`position startpos moves ...`, `go movetime ...`, etc.).

### Stiluri predefinite (8)

| Stil | Idee | Multiplicatori |
|---|---|---|
| `balanced` | Baseline neutru | toate × 1.0 |
| `solid` | Defensiv mild — king safety + pawn structure | KING_MG×1.10, PAWN PST×0.95 |
| `active` | Mobilizare piese minore | KNIGHT/BISHOP PST × 1.10 |
| `knightly` | Preferă cai (poziții închise) | KNIGHT × 1.08, BISHOP × 0.96 |
| `bishopy` | Preferă nebuni (poziții deschise) | BISHOP × 1.08, KNIGHT × 0.96 |
| `simplifier` | Forțează schimburi spre endgame | Piese majore × 1.05, pioni × 0.97 |
| `positional` | Original (extreme PST amplification — risky) | PST × 1.4 |
| `aggressive` | **REGRESSION example** (SPRT: -52 ELO!) | PAWN × 1.3, PST_PAWN × 1.5 |

⚠️ **Lecție din SPRT**: multiplicatori > ±15% tind să strice engine-ul. Stilurile noi
(`solid`, `active`, `knightly`, `bishopy`, `simplifier`) sunt conservatoare (±5-15%)
și au șanse mai mari să dea ELO neutru sau pozitiv.

---

## Scenarii de utilizare

### A) "E noua mea schimbare mai bună?" → folosește SPRT

SPRT (Sequential Probability Ratio Test) decide cât mai devreme:
```bash
./scripts/sprt.sh styles/new_idea.json styles/baseline.json 5
#                                                          ↑
#                                       ELO threshold pentru "mai bun"
```

Output:
```
Score of new_idea vs baseline: 142 - 89 - 269  [0.553] 500
Elo difference: 37.0 +/- 12.4, LOS: 100.0 %, DrawRatio: 53.8 %
SPRT: LLR 2.98 (-2.94,2.94) [0.00,5.00]
```
- `LLR 2.98 (-2.94, 2.94)` — pragul superior 2.94 a fost depășit → **H1 acceptat** = îmbunătățire confirmată
- LLR < -2.94 → H0 acceptat (NU e îmbunătățire)
- Între → continuă să joace

Beneficiu: în loc să joci orb 1000 jocuri, oprește când e clar (median ~300-500 jocuri).

### B) "Care e ELO diff exact între A și B?" → folosește match

```bash
./scripts/match.sh styles/aggressive.json styles/positional.json 500 10+0.1
```
Rulează exact 500 jocuri, output ELO diff cu CI 95%.

Folosește când:
- Vrei să vezi diferența exactă (nu doar "e mai bun?")
- Compari multiple stiluri într-un tabel

### C) "Ce a schimbat în PV-uri?" → examinează PGN-ul

PGN-urile se salvează în `logs/<nume>-<timestamp>.pgn`. Deschide cu:
- ChessBase, Arena, SCID
- Sau lichess "Import PGN" pentru analiză rapidă cu Stockfish

---

## Parametri importanți

| Parametru | Default | Cand să-l schimbi |
|---|---|---|
| **TC (time control)** | `10+0.1` (10 sec + 0.1 inc) | Pentru testing real folosește `10+0.1`. Pentru iterație rapidă, `1+0.05`. Pentru rezultate apropiate de Lichess bullet, `5+0`. |
| **N_GAMES** (match) | 100 | Pentru variance acceptabil, min 200. Pentru ±20 ELO precision, ~500. Pentru ±10 ELO, ~2000. |
| **ELO1** (SPRT) | 5 | Threshold for "îmbunătățire reală". 5 e tipic; pentru schimbări mari folosește 10. |
| **CONCURRENCY** | physical cores - 1 | Cât poate rula paralel pe Mac-ul tău |

---

## Înțelegerea output-ului

```
Finished match
Elo difference: 37.0 +/- 12.4, LOS: 100.0 %, DrawRatio: 53.8 %
```
- **Elo difference**: diferența estimată (A vs B)
- **+/- 12.4**: jumătatea intervalului de confidență 95%. Real ELO ∈ [24.6, 49.4] cu 95% confidence.
- **LOS** (Likelihood Of Superiority): probabilitatea că A e *cu adevărat* mai bun, regardless of magnitude. >95% = înalt convins.
- **DrawRatio**: cât de "draw-ish" sunt jocurile. Mare (>60%) = TC prea scurt sau stiluri similare.

---

## Variance — câte jocuri ai nevoie?

| ELO diff real | Jocuri minime pt p<0.05 |
|---|---|
| ±50 | ~100 |
| ±20 | ~500 |
| ±10 | ~2000 |
| ±5  | ~8000 |
| ±2  | ~50000 |

Pe un Mac M-series cu 6-8 threads paralele, ~500-1500 jocuri/oră la TC 10+0.1.

---

## Ce e MIT și ce nu e încă

### Ce funcționează acum:
- AB testing style A vs style B (același engine, parametri diferiți)
- SPRT pentru decizii rapide
- PGN output pentru review
- Opening book cu 30 poziții variate

### Ce NU e încă:
- Testing împotriva alt engine (Stockfish, Komodo) — pentru asta adaugi un al doilea `-engine` cu path-ul către celălalt binar
- Tournament-uri cu N engine-uri (round-robin) — folosește `cutechess-cli` direct cu `-rounds N`
- Auto-tuning (Texel / SPSA) — vine separat

---

## Troubleshooting

**"fastchess: command not found"** → vezi secțiunea Setup pentru build (binarul ar trebui să fie în `tools/fastchess`)

**"Engine fails to launch"** → testează manual:
```bash
./run_engine.sh < /dev/null
```
Apasă Ctrl-D când vrei să iasă. Dacă nu emite `uciok` etc., problema e în engine.

**"Toate jocurile sunt remize"** → TC prea scurt (engine-ul nu vede tactici) sau style files identice.

**"PGN gol"** → cutechess s-a oprit înainte să se termine vreun joc; vezi stderr.
