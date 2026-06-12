package chess;

/**
 * Evaluare statica MATURA cu:
 *   - Material + PST tapered (MG/EG interpolation pentru rege; restul comun)
 *   - King safety (zona 3×3 in jurul regelui, atacatori^2)
 *   - Pawn structure (passed, isolated, doubled — MG/EG diferit)
 *   - Bishop pair bonus
 *
 * Phase calculation (clasic, ala Fruit):
 *   Knight=1, Bishop=1, Rook=2, Queen=4 → max 24 (MG pur), min 0 (EG pur)
 *
 * Scor final: lerp(mgScore, egScore, 1 - phase/24)
 *
 * Hot path: hoist style.weights() in local final la inceput.
 */
public final class Evaluator {

    private final StyleOrchestrator style;

    // Phase weights per piesa (pentru tapered eval)
    private static final int PH_KNIGHT = 1;
    private static final int PH_BISHOP = 1;
    private static final int PH_ROOK   = 2;
    private static final int PH_QUEEN  = 4;
    private static final int PH_MAX    = 24; // 4 cavaleri + 4 nebuni + 4 ture + 2 regine

    // Directii pentru mobility (sliders)
    private static final int[] ROOK_DIRS    = { 8, -8, 1, -1 };
    private static final int[] BISHOP_DIRS  = { 9, 7, -7, -9 };
    private static final int[] QUEEN_DIRS   = { 8, -8, 1, -1, 9, 7, -7, -9 };
    private static final int[] KNIGHT_JUMPS = { 17, 15, 10, 6, -6, -10, -15, -17 };

    // PST base index per piece type (1..6)
    private static final int[] PST_BASE_BY_TYPE = {
        0,
        StyleOrchestrator.PST_PAWN_BASE,
        StyleOrchestrator.PST_KNIGHT_BASE,
        StyleOrchestrator.PST_BISHOP_BASE,
        StyleOrchestrator.PST_ROOK_BASE,
        StyleOrchestrator.PST_QUEEN_BASE,
        StyleOrchestrator.PST_KING_MG_BASE,
    };

    public Evaluator(StyleOrchestrator style) {
        if (style == null) throw new NullPointerException("style");
        this.style = style;
    }
    public Evaluator() { this(new StyleOrchestrator()); }

    // -------------------------------------------------------------------------
    // API principal
    // -------------------------------------------------------------------------

    /** Scor din perspectiva ALBULUI (pozitiv = bine pentru alb). */
    public int evaluate(Board board) {
        final int[] w = style.weights();

        int mg = 0;
        int eg = 0;
        int phase = 0;

        // Pozitiile regilor — direct din cache-ul Board (O(1))
        final int whiteKingSq = board.getKingSq(Piece.WHITE);
        final int blackKingSq = board.getKingSq(Piece.BLACK);

        int whiteBishops = 0, blackBishops = 0;
        long whitePawnBB = 0L, blackPawnBB = 0L;
        int[] whitePawnsByFile = new int[8];
        int[] blackPawnsByFile = new int[8];

        // Pre-pass: numarăm pionii pe fileuri (nevoie pentru outposts + rook file + pawn shield)
        for (int sq = 0; sq < 64; sq++) {
            int p = board.getSquare(sq);
            if (Piece.type(p) == Piece.PAWN) {
                int file = sq & 7;
                if (Piece.isWhite(p)) { whitePawnsByFile[file]++; whitePawnBB |= (1L << sq); }
                else                  { blackPawnsByFile[file]++; blackPawnBB |= (1L << sq); }
            }
        }

        // King safety counter — atacatori non-pawn aproape de rege (Chebyshev<=3)
        int whiteAttackersNearKing = 0;
        int blackAttackersNearKing = 0;

        // Mobility + outposts + rook file bonuses
        int mobMg = 0, mobEg = 0;
        int outpostMg = 0, outpostEg = 0;
        int rookFileMg = 0;  // efectul e doar in MG (in EG rook activity = mobility)

        // Faza 2: scoring pass — material, PST, mobility, outposts, rook file, king safety
        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getSquare(sq);
            if (Piece.isEmpty(piece)) continue;

            int type  = Piece.type(piece);
            boolean white = Piece.isWhite(piece);
            int sign = white ? 1 : -1;

            // Material
            int material = w[StyleOrchestrator.MAT_PAWN + type - 1];
            mg += sign * material;
            eg += sign * material;

            // PST
            int row = sq >>> 3;
            int col = sq & 7;
            int prow = white ? row : (7 - row);
            int symFile = (col < 4) ? col : (7 - col);
            int pstIdx = PST_BASE_BY_TYPE[type] + prow * 4 + symFile;
            int pstVal = w[pstIdx];

            if (type == Piece.KING) {
                int pstEgIdx = StyleOrchestrator.PST_KING_EG_BASE + prow * 4 + symFile;
                mg += sign * pstVal;
                eg += sign * w[pstEgIdx];
            } else {
                mg += sign * pstVal;
                eg += sign * pstVal;
            }

            // Phase + bishop count + king safety + mobility + outposts + rook file
            switch (type) {
                case Piece.KNIGHT -> {
                    phase += PH_KNIGHT;
                    int kingTarget = white ? blackKingSq : whiteKingSq;
                    if (chebDist(sq, kingTarget) <= 3) {
                        if (white) blackAttackersNearKing++; else whiteAttackersNearKing++;
                    }
                    // Mobility (numar destinatii goale sau cu piesa adversa)
                    int mob = knightMobility(board, sq, white);
                    mobMg += sign * mob * w[StyleOrchestrator.MOB_KNIGHT_MG];
                    mobEg += sign * mob * w[StyleOrchestrator.MOB_KNIGHT_EG];
                    // Outpost — cavaler pe pătrat sigur in territoria adversa
                    if (isOutpost(white, row, col, whitePawnsByFile, blackPawnsByFile)) {
                        outpostMg += sign * w[StyleOrchestrator.OUTPOST_KNIGHT_MG];
                        outpostEg += sign * w[StyleOrchestrator.OUTPOST_KNIGHT_EG];
                    }
                }
                case Piece.BISHOP -> {
                    phase += PH_BISHOP;
                    if (white) whiteBishops++; else blackBishops++;
                    int kingTarget = white ? blackKingSq : whiteKingSq;
                    if (chebDist(sq, kingTarget) <= 3) {
                        if (white) blackAttackersNearKing++; else whiteAttackersNearKing++;
                    }
                    int mob = bishopMobility(board, sq, white);
                    mobMg += sign * mob * w[StyleOrchestrator.MOB_BISHOP_MG];
                    mobEg += sign * mob * w[StyleOrchestrator.MOB_BISHOP_EG];
                    if (isOutpost(white, row, col, whitePawnsByFile, blackPawnsByFile)) {
                        outpostMg += sign * w[StyleOrchestrator.OUTPOST_BISHOP_MG];
                        outpostEg += sign * w[StyleOrchestrator.OUTPOST_BISHOP_EG];
                    }
                }
                case Piece.ROOK -> {
                    phase += PH_ROOK;
                    int kingTarget = white ? blackKingSq : whiteKingSq;
                    if (chebDist(sq, kingTarget) <= 3) {
                        if (white) blackAttackersNearKing++; else whiteAttackersNearKing++;
                    }
                    int mob = rookMobility(board, sq, white);
                    mobMg += sign * mob * w[StyleOrchestrator.MOB_ROOK_MG];
                    mobEg += sign * mob * w[StyleOrchestrator.MOB_ROOK_EG];
                    // Open / semi-open file
                    int file = col;
                    int myFile = white ? whitePawnsByFile[file] : blackPawnsByFile[file];
                    int oppFile = white ? blackPawnsByFile[file] : whitePawnsByFile[file];
                    if (myFile == 0 && oppFile == 0) {
                        rookFileMg += sign * w[StyleOrchestrator.ROOK_OPEN_FILE];
                    } else if (myFile == 0) {
                        rookFileMg += sign * w[StyleOrchestrator.ROOK_SEMI_OPEN_FILE];
                    }
                }
                case Piece.QUEEN -> {
                    phase += PH_QUEEN;
                    int kingTarget = white ? blackKingSq : whiteKingSq;
                    if (chebDist(sq, kingTarget) <= 3) {
                        if (white) blackAttackersNearKing += 2; else whiteAttackersNearKing += 2;
                    }
                    int mob = queenMobility(board, sq, white);
                    mobMg += sign * mob * w[StyleOrchestrator.MOB_QUEEN_MG];
                    mobEg += sign * mob * w[StyleOrchestrator.MOB_QUEEN_EG];
                }
                default -> { /* PAWN/KING — pawn deja procesat in pre-pass */ }
            }
        }

        mg += mobMg + outpostMg + rookFileMg;
        eg += mobEg + outpostEg;

        if (phase > PH_MAX) phase = PH_MAX;

        // -------- Bishop pair --------
        if (whiteBishops >= 2) {
            mg += w[StyleOrchestrator.BISHOP_PAIR_MG];
            eg += w[StyleOrchestrator.BISHOP_PAIR_EG];
        }
        if (blackBishops >= 2) {
            mg -= w[StyleOrchestrator.BISHOP_PAIR_MG];
            eg -= w[StyleOrchestrator.BISHOP_PAIR_EG];
        }

        // -------- Pawn structure --------
        int[] pawnEval = evaluatePawns(w, whitePawnsByFile, blackPawnsByFile, whitePawnBB, blackPawnBB);
        mg += pawnEval[0];
        eg += pawnEval[1];

        // -------- King safety (quadratic in attackers) --------
        int safetyScale = w[StyleOrchestrator.KING_SAFETY_BASE];
        mg -= safetyScale * whiteAttackersNearKing * whiteAttackersNearKing;
        mg += safetyScale * blackAttackersNearKing * blackAttackersNearKing;
        eg -= (safetyScale / 4) * whiteAttackersNearKing * whiteAttackersNearKing;
        eg += (safetyScale / 4) * blackAttackersNearKing * blackAttackersNearKing;

        // -------- Pawn shield (MG only — regele are nevoie de aparare doar in MG) --------
        int shieldBase = w[StyleOrchestrator.PAWN_SHIELD_BASE];
        if (shieldBase > 0) {
            mg += pawnShield(whiteKingSq, whitePawnsByFile, whitePawnBB, shieldBase, true);
            mg -= pawnShield(blackKingSq, blackPawnsByFile, blackPawnBB, shieldBase, false);
        }

        // -------- Tapered blend --------
        return (mg * phase + eg * (PH_MAX - phase)) / PH_MAX;
    }

    // Chebyshev distance — used pentru king zone
    private static int chebDist(int sqA, int sqB) {
        int dr = Math.abs((sqA >>> 3) - (sqB >>> 3));
        int dc = Math.abs((sqA & 7) - (sqB & 7));
        return Math.max(dr, dc);
    }

    // -------------------------------------------------------------------------
    // Mobility — count pătrate destinație valide (goale sau cu piesa adversa)
    // -------------------------------------------------------------------------
    private static int knightMobility(Board board, int sq, boolean white) {
        int col = sq & 7;
        int mob = 0;
        for (int j : KNIGHT_JUMPS) {
            int target = sq + j;
            if (target < 0 || target >= 64) continue;
            if (Math.abs(col - (target & 7)) > 2) continue;
            int p = board.getSquare(target);
            if (Piece.isEmpty(p) || (Piece.isWhite(p) != white)) mob++;
        }
        return mob;
    }

    private static int slidingMobility(Board board, int sq, boolean white, int[] dirs) {
        int mob = 0;
        for (int dir : dirs) {
            int cur = sq;
            while (true) {
                int prevCol = cur & 7;
                cur += dir;
                if (cur < 0 || cur >= 64) break;
                int curCol = cur & 7;
                if ((dir == 1 || dir == -1 || dir == 9 || dir == -9 || dir == 7 || dir == -7)
                        && Math.abs(prevCol - curCol) != 1) break;
                int p = board.getSquare(cur);
                if (Piece.isEmpty(p)) { mob++; continue; }
                if (Piece.isWhite(p) != white) mob++;
                break;
            }
        }
        return mob;
    }

    private static int bishopMobility(Board board, int sq, boolean white) {
        return slidingMobility(board, sq, white, BISHOP_DIRS);
    }
    private static int rookMobility(Board board, int sq, boolean white) {
        return slidingMobility(board, sq, white, ROOK_DIRS);
    }
    private static int queenMobility(Board board, int sq, boolean white) {
        return slidingMobility(board, sq, white, QUEEN_DIRS);
    }

    // -------------------------------------------------------------------------
    // Outpost — pătrat unde nu poate ataca niciun pion advers + e in territoria adversa
    // Pentru alb: row >= 3 (rank 4+); pentru negru: row <= 4 (rank 5-)
    // -------------------------------------------------------------------------
    private static boolean isOutpost(boolean white, int row, int col,
                                      int[] whitePawnsByFile, int[] blackPawnsByFile) {
        // Trebuie sa fie in territoria adversa (rank 4+ pentru alb, rank 5- pentru negru)
        if (white  && row < 3) return false;
        if (!white && row > 4) return false;
        // Niciun pion advers pe coloane adiacente (ar putea avansa si ataca)
        int[] oppPawns = white ? blackPawnsByFile : whitePawnsByFile;
        if (col > 0 && oppPawns[col - 1] > 0) return false;
        if (col < 7 && oppPawns[col + 1] > 0) return false;
        return true;
    }

    // -------------------------------------------------------------------------
    // Pawn shield — bonus per pion pe coloana regelui + adiacente, rang 2/3 (alb)
    // -------------------------------------------------------------------------
    private static int pawnShield(int kingSq, int[] pawnsByFile, long pawnBB, int shieldBase, boolean white) {
        // Doar daca regele e in rank 1 (alb) sau rank 8 (negru) — pozitia post-rocada
        int kingRow = kingSq >>> 3;
        if (white  && kingRow > 1) return 0;
        if (!white && kingRow < 6) return 0;
        int kingCol = kingSq & 7;
        int score = 0;
        // Verificam 3 coloane in jur (col-1, col, col+1)
        for (int dc = -1; dc <= 1; dc++) {
            int file = kingCol + dc;
            if (file < 0 || file > 7) continue;
            if (pawnsByFile[file] == 0) {
                score -= shieldBase; // file deschis in fata regelui = pericol
                continue;
            }
            // Bonus mai mare pentru pion mai aproape de rege
            int rank2 = white ? 1 : 6;
            int rank3 = white ? 2 : 5;
            if ((pawnBB & (1L << (rank2 * 8 + file))) != 0) {
                score += shieldBase;       // pion pe rank 2 — full bonus
            } else if ((pawnBB & (1L << (rank3 * 8 + file))) != 0) {
                score += shieldBase / 2;   // pion pe rank 3 — half bonus (avansat, mai expus)
            }
        }
        return score;
    }

    public int evaluate(Board board, int colorToMove) {
        int score = evaluate(board);
        return (colorToMove == Piece.WHITE) ? score : -score;
    }

    // -------------------------------------------------------------------------
    // Pawn structure: passed, isolated, doubled
    // -------------------------------------------------------------------------
    private int[] evaluatePawns(int[] w,
                                int[] wByFile, int[] bByFile,
                                long whitePawnBB, long blackPawnBB) {
        int isoMg = w[StyleOrchestrator.PAWN_ISOLATED_MG];
        int isoEg = w[StyleOrchestrator.PAWN_ISOLATED_EG];
        int dblMg = w[StyleOrchestrator.PAWN_DOUBLED_MG];
        int dblEg = w[StyleOrchestrator.PAWN_DOUBLED_EG];
        int passBase = w[StyleOrchestrator.PASSED_PAWN_BASE];

        int mg = 0, eg = 0;

        // Doubled + isolated per file pentru alb
        for (int f = 0; f < 8; f++) {
            // Doubled (alb)
            if (wByFile[f] > 1) {
                int extra = wByFile[f] - 1;
                mg += extra * dblMg;
                eg += extra * dblEg;
            }
            // Isolated (alb) — niciun pion alb pe coloane adiacente
            if (wByFile[f] > 0) {
                boolean leftEmpty  = (f == 0) || wByFile[f - 1] == 0;
                boolean rightEmpty = (f == 7) || wByFile[f + 1] == 0;
                if (leftEmpty && rightEmpty) {
                    mg += isoMg * wByFile[f];
                    eg += isoEg * wByFile[f];
                }
            }
            // Doubled (negru)
            if (bByFile[f] > 1) {
                int extra = bByFile[f] - 1;
                mg -= extra * dblMg;
                eg -= extra * dblEg;
            }
            // Isolated (negru)
            if (bByFile[f] > 0) {
                boolean leftEmpty  = (f == 0) || bByFile[f - 1] == 0;
                boolean rightEmpty = (f == 7) || bByFile[f + 1] == 0;
                if (leftEmpty && rightEmpty) {
                    mg -= isoMg * bByFile[f];
                    eg -= isoEg * bByFile[f];
                }
            }
        }

        // Passed pawns — pion alb la sq fără pioni negri pe acelasi/adiacent file IN FATA
        long wp = whitePawnBB;
        while (wp != 0) {
            int sq = Long.numberOfTrailingZeros(wp);
            wp &= wp - 1;
            int row = sq >>> 3;
            int col = sq & 7;
            // Verifica pioni negri pe coloana sa si adiacente, pe rank > row
            long blockMask = passedPawnMask(col, row, true);
            if ((blackPawnBB & blockMask) == 0L) {
                int rankAdv = row;  // rank 1 = 0 (just left start), rank 7 = 6 (pre-promo)
                int bonus = passBase * rankAdv / 6; // simpler scaling, max=passBase pe rank 7
                // De fapt, vrem bonus mai mare pe rank mai inalt
                bonus = passBase * (rankAdv + 1) / 4; // mai pronunțat
                mg += bonus;
                eg += bonus * 3 / 2; // mai important in EG (mai aproape de promotie efectiva)
            }
        }

        long bp = blackPawnBB;
        while (bp != 0) {
            int sq = Long.numberOfTrailingZeros(bp);
            bp &= bp - 1;
            int row = sq >>> 3;
            int col = sq & 7;
            long blockMask = passedPawnMask(col, row, false);
            if ((whitePawnBB & blockMask) == 0L) {
                int rankAdv = 7 - row;
                int bonus = passBase * (rankAdv + 1) / 4;
                mg -= bonus;
                eg -= bonus * 3 / 2;
            }
        }

        return new int[]{ mg, eg };
    }

    /**
     * Mask peste pătratele care ar bloca un pion la (col, row) sa fie passed.
     * Pentru pion alb: toate patratele pe col-1, col, col+1 cu rank > row
     * Pentru pion negru: toate patratele pe col-1, col, col+1 cu rank < row
     */
    private static long passedPawnMask(int col, int row, boolean white) {
        long mask = 0L;
        int rowStart, rowEnd;
        if (white) { rowStart = row + 1; rowEnd = 7; }
        else       { rowStart = 0;       rowEnd = row - 1; }
        for (int r = rowStart; r <= rowEnd; r++) {
            for (int c = Math.max(0, col - 1); c <= Math.min(7, col + 1); c++) {
                mask |= 1L << (r * 8 + c);
            }
        }
        return mask;
    }

}
