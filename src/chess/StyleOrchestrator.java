package chess;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for all eval parameters (material values + PSTs).
 *
 * Storage layout: two flat int[] of size NUM_FEATURES.
 *   - baseValues:    handcrafted empirical defaults; never mutated
 *   - dynamicValues: current effective weights; overwritten in place by
 *                    applyStyleModifiers
 *
 * Hot path: callers hoist weights() into a local once per evaluate() call,
 * then index it. JIT collapses this to a single MOV + LEA per lookup.
 *
 * The class is intentionally final to allow inlining of the trivial
 * accessors. Thread safety: NOT thread-safe; apply modifiers only when
 * no search thread is reading from weights().
 */
public final class StyleOrchestrator {

    // -------------------------------------------------------------------------
    // Sizing & layout
    // -------------------------------------------------------------------------
    public static final int NUM_FEATURES = 256;

    // Section bases (inclusive start indices, contiguous blocks).
    public static final int MAT_BASE         =   0; //  6 valori
    public static final int PST_PAWN_BASE    =   6; // 32 valori (4 cols × 8 ranks)
    public static final int PST_KNIGHT_BASE  =  38;
    public static final int PST_BISHOP_BASE  =  70;
    public static final int PST_ROOK_BASE    = 102;
    public static final int PST_QUEEN_BASE   = 134;
    public static final int PST_KING_MG_BASE = 166;
    // [198..199] = PAWN_ISOLATED_MG, PAWN_DOUBLED_MG (compat brain v2)

    // NEW eval features (Etapa 2 - tapered eval, king safety, pawn structure)
    public static final int PST_KING_EG_BASE   = 200; // 32 valori — regele in endgame prefera centrul

    // Scalari pentru eval matur
    public static final int KING_SAFETY_BASE   = 232; // scaling pentru penalitatea de king safety
    public static final int BISHOP_PAIR_MG     = 233;
    public static final int BISHOP_PAIR_EG     = 234;
    public static final int PASSED_PAWN_BASE   = 235; // bonus * (rank_advance) per pion passed
    public static final int PAWN_ISOLATED_EG   = 236; // izolat in EG (mai grav)
    public static final int PAWN_DOUBLED_EG    = 237;

    // Mobility — bonus per legal move/atac
    public static final int MOB_KNIGHT_MG = 238;
    public static final int MOB_KNIGHT_EG = 239;
    public static final int MOB_BISHOP_MG = 240;
    public static final int MOB_BISHOP_EG = 241;
    public static final int MOB_ROOK_MG   = 242;
    public static final int MOB_ROOK_EG   = 243;
    public static final int MOB_QUEEN_MG  = 244;
    public static final int MOB_QUEEN_EG  = 245;

    // Outposts — cavaleri/nebuni pe pătrate sigure (no enemy pawn poate ataca)
    public static final int OUTPOST_KNIGHT_MG = 246;
    public static final int OUTPOST_KNIGHT_EG = 247;
    public static final int OUTPOST_BISHOP_MG = 248;
    public static final int OUTPOST_BISHOP_EG = 249;

    // Rook on file
    public static final int ROOK_OPEN_FILE      = 250;
    public static final int ROOK_SEMI_OPEN_FILE = 251;

    // Pawn shield
    public static final int PAWN_SHIELD_BASE = 252;
    // [253..255] spare

    // Material — indici expliciti (ferim de off-by-one cu Piece.type()=1..6).
    public static final int MAT_PAWN   = MAT_BASE + 0;
    public static final int MAT_KNIGHT = MAT_BASE + 1;
    public static final int MAT_BISHOP = MAT_BASE + 2;
    public static final int MAT_ROOK   = MAT_BASE + 3;
    public static final int MAT_QUEEN  = MAT_BASE + 4;
    public static final int MAT_KING   = MAT_BASE + 5;

    // PST base per piece type (1..6). Slot 0 = NONE = unused.
    public static final int[] PST_BASE_BY_TYPE = {
        0,
        PST_PAWN_BASE,
        PST_KNIGHT_BASE,
        PST_BISHOP_BASE,
        PST_ROOK_BASE,
        PST_QUEEN_BASE,
        PST_KING_MG_BASE,
    };

    // Reserved slots (defaults activate cu valori reale acum).
    public static final int PAWN_ISOLATED_MG = 198;
    public static final int PAWN_DOUBLED_MG  = 199;

    // -------------------------------------------------------------------------
    // Broadcast groups — pentru JSON-style files in forma "named"
    // -------------------------------------------------------------------------
    // Ex: {"PST_PAWN": 1.2} => multiplica TOATE cele 32 valori ale PST_PAWN cu 1.2
    // Numele de grup poate referi:
    //   - un singur indice (ex. "MAT_PAWN") sau
    //   - un bloc contiguu de indici (ex. "PST_PAWN" → 32 valori)
    public static final Map<String, int[]> BROADCAST_GROUPS;
    static {
        Map<String, int[]> m = new LinkedHashMap<>();
        // Singular
        m.put("MAT_PAWN",   new int[]{ MAT_PAWN   });
        m.put("MAT_KNIGHT", new int[]{ MAT_KNIGHT });
        m.put("MAT_BISHOP", new int[]{ MAT_BISHOP });
        m.put("MAT_ROOK",   new int[]{ MAT_ROOK   });
        m.put("MAT_QUEEN",  new int[]{ MAT_QUEEN  });
        m.put("MAT_KING",   new int[]{ MAT_KING   }); // locked anyway, dar oferim numele
        // PST blocks (32 valori fiecare)
        m.put("PST_PAWN",     rangeOf(PST_PAWN_BASE,    32));
        m.put("PST_KNIGHT",   rangeOf(PST_KNIGHT_BASE,  32));
        m.put("PST_BISHOP",   rangeOf(PST_BISHOP_BASE,  32));
        m.put("PST_ROOK",     rangeOf(PST_ROOK_BASE,    32));
        m.put("PST_QUEEN",    rangeOf(PST_QUEEN_BASE,   32));
        m.put("PST_KING_MG",  rangeOf(PST_KING_MG_BASE, 32));
        m.put("PST_KING_EG",  rangeOf(PST_KING_EG_BASE, 32));
        // Scalari
        m.put("PAWN_ISOLATED_MG", new int[]{ PAWN_ISOLATED_MG });
        m.put("PAWN_DOUBLED_MG",  new int[]{ PAWN_DOUBLED_MG  });
        m.put("PAWN_ISOLATED_EG", new int[]{ PAWN_ISOLATED_EG });
        m.put("PAWN_DOUBLED_EG",  new int[]{ PAWN_DOUBLED_EG  });
        m.put("KING_SAFETY",      new int[]{ KING_SAFETY_BASE });
        m.put("BISHOP_PAIR_MG",   new int[]{ BISHOP_PAIR_MG   });
        m.put("BISHOP_PAIR_EG",   new int[]{ BISHOP_PAIR_EG   });
        m.put("PASSED_PAWN",      new int[]{ PASSED_PAWN_BASE });
        // Mobility
        m.put("MOB_KNIGHT_MG",     new int[]{ MOB_KNIGHT_MG    });
        m.put("MOB_KNIGHT_EG",     new int[]{ MOB_KNIGHT_EG    });
        m.put("MOB_BISHOP_MG",     new int[]{ MOB_BISHOP_MG    });
        m.put("MOB_BISHOP_EG",     new int[]{ MOB_BISHOP_EG    });
        m.put("MOB_ROOK_MG",       new int[]{ MOB_ROOK_MG      });
        m.put("MOB_ROOK_EG",       new int[]{ MOB_ROOK_EG      });
        m.put("MOB_QUEEN_MG",      new int[]{ MOB_QUEEN_MG     });
        m.put("MOB_QUEEN_EG",      new int[]{ MOB_QUEEN_EG     });
        // Outposts
        m.put("OUTPOST_KNIGHT_MG", new int[]{ OUTPOST_KNIGHT_MG });
        m.put("OUTPOST_KNIGHT_EG", new int[]{ OUTPOST_KNIGHT_EG });
        m.put("OUTPOST_BISHOP_MG", new int[]{ OUTPOST_BISHOP_MG });
        m.put("OUTPOST_BISHOP_EG", new int[]{ OUTPOST_BISHOP_EG });
        // Rook file
        m.put("ROOK_OPEN_FILE",      new int[]{ ROOK_OPEN_FILE      });
        m.put("ROOK_SEMI_OPEN_FILE", new int[]{ ROOK_SEMI_OPEN_FILE });
        // Pawn shield
        m.put("PAWN_SHIELD",      new int[]{ PAWN_SHIELD_BASE });
        BROADCAST_GROUPS = Collections.unmodifiableMap(m);
    }

    private static int[] rangeOf(int base, int len) {
        int[] r = new int[len];
        for (int i = 0; i < len; i++) r[i] = base + i;
        return r;
    }

    // -------------------------------------------------------------------------
    // Clamps — previn ca MLP-ul sa scoata valori care strica search-ul
    // -------------------------------------------------------------------------
    private static final float MIN_MULTIPLIER = 0.50f;
    private static final float MAX_MULTIPLIER = 2.00f;
    private static final int   MIN_WEIGHT     = -2000;
    private static final int   MAX_WEIGHT     = +2000;

    // Per-feature lock mask: true = feature NU se scaleaza niciodata.
    // Necesar pentru MAT_KING (un rege "ieftin" rupe terminarea search-ului).
    private final boolean[] locked = new boolean[NUM_FEATURES];

    // -------------------------------------------------------------------------
    // Storage
    // -------------------------------------------------------------------------
    private final int[] baseValues    = new int[NUM_FEATURES];
    private final int[] dynamicValues = new int[NUM_FEATURES];

    public StyleOrchestrator() {
        loadEmpiricalBaseline();
        locked[MAT_KING] = true; // regele e mereu "infinit"
        resetToBaseline();
    }

    // -------------------------------------------------------------------------
    // Hot-path accessor — folosit din Evaluator
    // -------------------------------------------------------------------------

    /**
     * Returneaza array-ul de weights curent. Read-only by convention;
     * mutarea lui corupe orchestrator-ul.
     */
    public int[] weights() {
        return dynamicValues;
    }

    public int weight(int index) {
        return dynamicValues[index];
    }

    // -------------------------------------------------------------------------
    // Mutation API
    // -------------------------------------------------------------------------

    /** Reset complet — uita influenta MLP. */
    public void resetToBaseline() {
        System.arraycopy(baseValues, 0, dynamicValues, 0, NUM_FEATURES);
    }

    /**
     * Aplica un vector de multiplicatori MLP. Suprascrie dynamicValues in-place.
     * Cost: O(N), zero alocari.
     */
    public void applyStyleModifiers(float[] mlpOutput) {
        if (mlpOutput.length != NUM_FEATURES) {
            throw new IllegalArgumentException(
                "MLP vector size " + mlpOutput.length + " != " + NUM_FEATURES);
        }

        final int[]     base      = this.baseValues;
        final int[]     dynamic   = this.dynamicValues;
        final boolean[] lockedRef = this.locked;

        for (int i = 0; i < NUM_FEATURES; i++) {
            if (lockedRef[i]) {
                dynamic[i] = base[i];
                continue;
            }

            float mult = mlpOutput[i];
            // NaN-safe: NaN comparat cu orice returneaza false → folosim !(>=)
            if (!(mult >= MIN_MULTIPLIER)) mult = MIN_MULTIPLIER;
            else if (mult > MAX_MULTIPLIER) mult = MAX_MULTIPLIER;

            int scaled = (int) (base[i] * mult);
            if (scaled < MIN_WEIGHT)      scaled = MIN_WEIGHT;
            else if (scaled > MAX_WEIGHT) scaled = MAX_WEIGHT;

            dynamic[i] = scaled;
        }
    }

    // -------------------------------------------------------------------------
    // Baseline empiric — mutat aici din Evaluator.java
    // -------------------------------------------------------------------------
    private void loadEmpiricalBaseline() {
        // Material (centipawni).
        baseValues[MAT_PAWN]   =   100;
        baseValues[MAT_KNIGHT] =   320;
        baseValues[MAT_BISHOP] =   330;
        baseValues[MAT_ROOK]   =   500;
        baseValues[MAT_QUEEN]  =   900;
        baseValues[MAT_KING]   = 20000;

        // 6 × PST de 32 valori (4 cols × 8 ranks).
        // Conventie: row 0 = RANK 1 al albului; coloanele a-d, e-h oglindite.
        copyInto(PST_PAWN_BASE, new int[]{
            // rank 1: pionul nu apare aici
              0,   0,   0,   0,
            // rank 2 (start): d/e penalizati (blocheaza nebunii)
              5,  10,  10, -20,
              5,  -5, -10,   0,
              0,   0,   0,  20,   // rank 4 — center push recompensat
              5,   5,  10,  25,
             10,  10,  20,  30,
             50,  50,  50,  50,   // rank 7 — aproape de promotie
              0,   0,   0,   0,   // rank 8: deja a promovat
        });
        copyInto(PST_KNIGHT_BASE, new int[]{
            -50, -40, -30, -30,
            -40, -20,   0,   0,
            -30,   0,  10,  15,
            -30,   5,  15,  20,
            -30,   0,  15,  20,
            -30,   5,  10,  15,
            -40, -20,   0,   5,
            -50, -40, -30, -30,
        });
        copyInto(PST_BISHOP_BASE, new int[]{
            -20, -10, -10, -10,
            -10,   5,   0,   0,
            -10,  10,  10,  10,
            -10,   0,  10,  10,
            -10,   5,   5,  10,
            -10,   0,   5,  10,
            -10,   0,   0,   0,
            -20, -10, -10, -10,
        });
        copyInto(PST_ROOK_BASE, new int[]{
              0,   0,   0,   5,
             -5,   0,   0,   0,
             -5,   0,   0,   0,
             -5,   0,   0,   0,
             -5,   0,   0,   0,
             -5,   0,   0,   0,
              5,  10,  10,  10,
              0,   0,   0,   0,
        });
        copyInto(PST_QUEEN_BASE, new int[]{
            -20, -10, -10,  -5,
            -10,   0,   5,   0,
            -10,   5,   5,   5,
              0,   0,   5,   5,
             -5,   0,   5,   5,
            -10,   0,   5,   5,
            -10,   0,   0,   0,
            -20, -10, -10,  -5,
        });
        copyInto(PST_KING_MG_BASE, new int[]{
             20,  30,  10,   0,
             20,  20,   0,   0,
            -10, -20, -20, -20,
            -20, -30, -30, -40,
            -30, -40, -40, -50,
            -30, -40, -40, -50,
            -30, -40, -40, -50,
            -30, -40, -40, -50,
        });

        // PST_KING_EG — regele in endgame VRea centru + activitate (inversul MG)
        copyInto(PST_KING_EG_BASE, new int[]{
            -50, -30, -20, -20,   // rank 1 (col a/h, b/g, c/f, d/e)
            -30, -10,   0,   5,
            -20,   0,  20,  30,
            -20,   5,  30,  40,   // rank 4 — centru optim pentru rege in EG
            -20,   5,  30,  40,
            -20,   0,  20,  30,
            -30, -10,   0,   5,
            -50, -30, -20, -20,   // rank 8
        });

        // Eval features acum activate (in MG)
        baseValues[PAWN_ISOLATED_MG] = -15;
        baseValues[PAWN_DOUBLED_MG]  = -10;
        baseValues[PAWN_ISOLATED_EG] = -25; // mai grav in EG
        baseValues[PAWN_DOUBLED_EG]  = -20;

        // King safety: scaling factor pentru penalitatea (atacatori^2)
        baseValues[KING_SAFETY_BASE] = 10;

        // Bishop pair
        baseValues[BISHOP_PAIR_MG] = 30;
        baseValues[BISHOP_PAIR_EG] = 50; // mai valoros in EG (board mai deschis)

        // Passed pawn — bonus per rank avansat (multiplicat pe rank in Evaluator)
        baseValues[PASSED_PAWN_BASE] = 15;

        // Mobility — bonus per legal move (MG mai mic, EG mai mare)
        baseValues[MOB_KNIGHT_MG] = 4;  baseValues[MOB_KNIGHT_EG] = 6;
        baseValues[MOB_BISHOP_MG] = 3;  baseValues[MOB_BISHOP_EG] = 5;
        baseValues[MOB_ROOK_MG]   = 2;  baseValues[MOB_ROOK_EG]   = 4;
        baseValues[MOB_QUEEN_MG]  = 1;  baseValues[MOB_QUEEN_EG]  = 2;

        // Outposts — bonus per piesa pe outpost
        baseValues[OUTPOST_KNIGHT_MG] = 25;
        baseValues[OUTPOST_KNIGHT_EG] = 15;
        baseValues[OUTPOST_BISHOP_MG] = 15;
        baseValues[OUTPOST_BISHOP_EG] = 10;

        // Rook on open/semi-open file
        baseValues[ROOK_OPEN_FILE]      = 25;
        baseValues[ROOK_SEMI_OPEN_FILE] = 12;

        // Pawn shield — bonus per pion in shield (rank 2 = full bonus, rank 3 = half)
        baseValues[PAWN_SHIELD_BASE] = 12;
    }

    private void copyInto(int base, int[] block) {
        System.arraycopy(block, 0, baseValues, base, block.length);
    }
}
