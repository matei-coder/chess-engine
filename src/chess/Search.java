package chess;

import java.util.List;

public class Search {

    private static final int INF      =  1_000_000;
    private static final int NEG_INF  = -1_000_000;
    private static final int MATE_VAL =     100_000;

    // Pentru MVV-LVA si scorul mutarilor
    private static final int[] MVV_VALUES = { 0, 100, 320, 330, 500, 900, 20000 };

    // Scoruri pentru ordering (mai mare = incercat mai devreme)
    private static final int SCORE_TT       = 10_000_000;
    private static final int SCORE_PROMO    =  9_000_000;
    private static final int SCORE_CAPTURE  =  1_000_000;
    private static final int SCORE_KILLER1  =    800_000;
    private static final int SCORE_KILLER2  =    790_000;

    private static final int MAX_PLY = 128;

    private final MoveGenerator    generator = new MoveGenerator();
    private final StyleOrchestrator style;
    private final Evaluator        evaluator;
    private       TranspositionTable tt    = new TranspositionTable(16);

    // -------------------------------------------------------------------------
    // Constructori
    // -------------------------------------------------------------------------
    public Search() {
        this(new StyleOrchestrator());
    }

    public Search(StyleOrchestrator style) {
        this.style     = style;
        this.evaluator = new Evaluator(style);
    }

    // Expune orchestrator-ul ca sa poata fi modificat din UCI / un MLP extern
    public StyleOrchestrator getStyle() { return style; }

    // -------------------------------------------------------------------------
    // Stare per search
    // -------------------------------------------------------------------------
    private Move    bestMoveRoot;
    private int     bestScoreRoot;
    private int     nodesSearched;
    private boolean timeUp;
    private long    startTime;
    private long    timeLimit;
    private int     selDepth;

    // -------------------------------------------------------------------------
    // Control extern din UCI (volatile pentru ca sunt scrise din alt thread)
    // -------------------------------------------------------------------------
    // stopFlag: setat de UCI cand primeste "stop" sau cand pregateste un go nou
    private volatile boolean stopFlag = false;
    // ponderMode: in timpul ponderingului ignoram complet deadline-ul
    private volatile boolean ponderMode = false;
    // deadline: timpul absolut (ms epoch) la care trebuie sa oprim
    private volatile long    deadline   = Long.MAX_VALUE;

    // Killer moves: 2 mutari tacute care au produs beta cutoff la fiecare ply.
    // Stocate ca int packed (flag<<12 | from<<6 | to) ca sa fie comparat ieftin.
    private final int[] killer1 = new int[MAX_PLY];
    private final int[] killer2 = new int[MAX_PLY];

    // History heuristic: per (tipul piesei, patratul tinta), creste cu depth^2
    // cand o mutare tacuta produce beta cutoff.
    // Index: (color==WHITE?0:6) + (type-1) = 0..11
    private final int[][] history = new int[12][64];

    // Istoricul jocului — hash-urile pozitiilor INAINTE de fiecare mutare reala.
    // Setat extern de Uci.handlePosition. Folosit pentru detectarea repetitiei.
    private long[] gameHistory    = new long[0];
    private int    gameHistoryLen = 0;

    // Hash-urile pozitiilor pe drumul curent de search (per ply).
    private final long[] searchHistory = new long[MAX_PLY];

    // -------------------------------------------------------------------------
    // API public — pastram semnaturile vechi pentru Main interactiv
    // -------------------------------------------------------------------------
    public Move findBestMove(Board board, int colorToMove, int depth) {
        return runSearch(board, colorToMove, depth, Long.MAX_VALUE, false);
    }

    public Move findBestMoveInTime(Board board, int colorToMove, long timeLimitMs) {
        return runSearch(board, colorToMove, 99, timeLimitMs, false);
    }

    // API generalizat pentru UCI: suporta ponder + abort
    public Move runSearch(Board board, int colorToMove, int maxDepth,
                          long timeLimitMs, boolean isPonder) {
        this.stopFlag   = false;
        this.ponderMode = isPonder;
        this.deadline   = isPonder ? Long.MAX_VALUE
                                   : System.currentTimeMillis() + timeLimitMs;
        return iterativeDeepening(board, colorToMove, maxDepth, timeLimitMs);
    }

    // Apelat de UCI la "stop" — abort imediat
    public void stop() { stopFlag = true; }

    // Apelat de UCI la "ponderhit" — switch din ponder mode la time-limited
    public void ponderhit(long allocatedMs) {
        deadline   = System.currentTimeMillis() + allocatedMs;
        ponderMode = false;
    }

    // Extragere ponder move din TT: cea mai buna replica adversarului dupa bestMove.
    public Move getPonderMove(Board board, int colorToMove, Move bestMove) {
        if (bestMove == null) return null;
        GameState state = board.makeMove(bestMove);
        long key = ttKey(board, opponent(colorToMove));
        Move ponder = null;
        if (tt.keyMatches(key)) {
            long entry = tt.probe(key);
            int packed = TranspositionTable.unpackMove(entry);
            ponder = TranspositionTable.decodeMove(packed);
        }
        board.unmakeMove(bestMove, state);
        return ponder;
    }

    public void setHashSizeMB(int mb) { tt.resize(Math.max(1, mb)); }
    public void clearHash()           { tt.clear(); resetSearchState(); }

    // Apelat de Uci inainte de fiecare search: history = hash-urile pozitiilor
    // intalnite in partida reala (inainte de fiecare board.makeMove).
    public void setGameHistory(long[] history, int len) {
        this.gameHistory    = history;
        this.gameHistoryLen = len;
    }

    private void resetSearchState() {
        for (int i = 0; i < MAX_PLY; i++) { killer1[i] = 0; killer2[i] = 0; }
        for (int p = 0; p < 12; p++)
            for (int sq = 0; sq < 64; sq++) history[p][sq] = 0;
    }

    // -------------------------------------------------------------------------
    // Iterative deepening cu aspiration windows
    // -------------------------------------------------------------------------
    private Move iterativeDeepening(Board board, int colorToMove, int maxDepth, long timeLimitMs) {
        this.timeLimit = timeLimitMs;
        this.startTime = System.currentTimeMillis();
        this.timeUp    = false;
        this.bestMoveRoot  = null;
        this.bestScoreRoot = 0;

        Move bestSoFar = null;
        int  prevScore = 0;

        for (int depth = 1; depth <= maxDepth; depth++) {
            int alpha = NEG_INF;
            int beta  = INF;
            int window = 50;

            // Aspiration: pornim cu fereastra ingusta dupa primele 3 iteratii
            if (depth >= 4) {
                alpha = prevScore - window;
                beta  = prevScore + window;
            }

            int score;
            while (true) {
                bestMoveRoot = null;
                nodesSearched = 0;
                selDepth = 0;

                score = alphaBeta(board, colorToMove, depth, alpha, beta, true, 0, true);

                if (timeUp) break;

                if (score <= alpha) {
                    // fail low — largim catre minus infinit
                    window *= 2;
                    alpha = (window >= 1000) ? NEG_INF : (prevScore - window);
                    continue;
                }
                if (score >= beta) {
                    // fail high — largim catre plus infinit
                    window *= 2;
                    beta = (window >= 1000) ? INF : (prevScore + window);
                    continue;
                }
                break; // scor in fereastra
            }

            if (timeUp) break;

            if (bestMoveRoot != null) {
                bestSoFar = bestMoveRoot;
                bestScoreRoot = score;
                prevScore = score;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("info depth " + depth
                + " seldepth " + selDepth
                + " score " + formatScore(score)
                + " nodes "  + nodesSearched
                + " time "   + elapsed
                + (bestSoFar != null ? " pv " + bestSoFar : ""));

            // Verificare deadline (nu cand suntem in ponder mode — atunci timpul e infinit)
            if (!ponderMode && System.currentTimeMillis() >= deadline) break;
            // Stop extern
            if (stopFlag) break;
            // Mate gasit — nu mai are sens sa cautam mai adanc
            if (Math.abs(score) >= MATE_VAL - MAX_PLY && !ponderMode) break;
        }

        return bestSoFar;
    }

    private String formatScore(int score) {
        if (score >= MATE_VAL - MAX_PLY)  return "mate "  + ((MATE_VAL - score + 1) / 2);
        if (score <= -MATE_VAL + MAX_PLY) return "mate -" + ((MATE_VAL + score + 1) / 2);
        return "cp " + score;
    }

    // -------------------------------------------------------------------------
    // Alpha-Beta cu TT, NMP, LMR, killers, history, check extension
    // -------------------------------------------------------------------------
    private int alphaBeta(Board board, int color, int depth,
                          int alpha, int beta, boolean isRoot, int ply, boolean isPV) {
        // Verificare timp/abort (la fiecare 2048 noduri)
        if ((nodesSearched & 2047) == 0) {
            if (stopFlag) timeUp = true;
            else if (!ponderMode && System.currentTimeMillis() >= deadline) timeUp = true;
        }
        if (timeUp) return 0;

        nodesSearched++;
        if (ply > selDepth) selDepth = ply;

        long currentHash = ttKey(board, color);

        // -------- Verificari de remiza (skip la root pentru a returna o mutare) --------
        if (!isRoot) {
            // Regula celor 50 de mutari
            if (board.getHalfmoveClock() >= 100) return 0;
            // Material insuficient pentru mat
            if (hasInsufficientMaterial(board)) return 0;
            // Repetitie (conservator: tratam a 2-a aparitie ca remiza in search)
            if (isRepetition(currentHash, ply)) return 0;
        }

        // Inregistram hash-ul pozitiei curente pentru detectia repetitiei la copii
        if (ply < MAX_PLY) searchHistory[ply] = currentHash;

        boolean inCheck = generator.isInCheck(board, color);

        // Check extension — daca suntem in sah, extindem cu 1 ply
        if (inCheck) depth++;

        if (depth <= 0) {
            return quiescence(board, color, alpha, beta, ply);
        }

        int alphaOrig = alpha;

        // -------- TT probe --------
        long ttKey = currentHash;
        int  ttMove = TranspositionTable.NO_MOVE;
        if (tt.keyMatches(ttKey)) {
            long entry = tt.probe(ttKey);
            int  ttDepth = TranspositionTable.unpackDepth(entry);
            int  ttScore = TranspositionTable.unpackScore(entry);
            int  ttFlag  = TranspositionTable.unpackFlag(entry);
            ttMove = TranspositionTable.unpackMove(entry);

            if (!isPV && ttDepth >= depth) {
                if (ttFlag == TranspositionTable.FLAG_EXACT) return ttScore;
                if (ttFlag == TranspositionTable.FLAG_LOWER && ttScore >= beta)  return ttScore;
                if (ttFlag == TranspositionTable.FLAG_UPPER && ttScore <= alpha) return ttScore;
            }
        }

        // -------- Null Move Pruning --------
        // Dam adversarului mutarea gratis si vedem daca e tot bine pentru noi.
        // Sarim daca: in sah, in PV, nu avem material, sau depth prea mic.
        if (!isPV && !inCheck && depth >= 3 && hasNonPawnMaterial(board, color) && beta < MATE_VAL - MAX_PLY) {
            int oldEp = board.getEnPassantSquare();
            board.setEnPassantSquare(-1);
            int nullScore = -alphaBeta(board, opponent(color), depth - 1 - 2,
                                       -beta, -beta + 1, false, ply + 1, false);
            board.setEnPassantSquare(oldEp);
            if (timeUp) return 0;
            if (nullScore >= beta) {
                // Stocam in TT ca lower bound
                tt.store(ttKey, depth, beta, TranspositionTable.FLAG_LOWER, ttMove);
                return beta;
            }
        }

        // -------- Generam si ordonam mutarile --------
        List<Move> moves = generator.generateMoves(board, color);

        // Scor pentru ordering: TT move > capturi > killers > history
        int[] scores = new int[moves.size()];
        for (int i = 0; i < moves.size(); i++) {
            scores[i] = scoreMove(board, moves.get(i), ttMove, ply);
        }
        sortByScore(moves, scores);

        int bestScore = NEG_INF;
        Move bestMoveLocal = null;
        int  bestMovePacked = TranspositionTable.NO_MOVE;
        int  legalCount = 0;
        int  opponent  = opponent(color);
        int  quietsSeen = 0;

        for (int i = 0; i < moves.size(); i++) {
            Move move = moves.get(i);
            GameState state = board.makeMove(move);

            if (generator.isInCheck(board, color)) {
                board.unmakeMove(move, state);
                continue;
            }
            legalCount++;

            boolean isCapture = !Piece.isEmpty(state.capturedPiece) || move.isEnPassant();
            boolean isQuiet   = !isCapture && !move.isPromotion();
            boolean givesCheck = generator.isInCheck(board, opponent);

            // -------- Late Move Reductions --------
            int reduction = 0;
            if (depth >= 3 && legalCount > 3 && isQuiet && !givesCheck) {
                reduction = 1;
                if (legalCount > 6) reduction = 2;
            }

            int score;
            if (legalCount == 1) {
                // Prima mutare — search complet PV
                score = -alphaBeta(board, opponent, depth - 1, -beta, -alpha, false, ply + 1, isPV);
            } else {
                // Search redus cu fereastra inchisa
                score = -alphaBeta(board, opponent, depth - 1 - reduction,
                                   -alpha - 1, -alpha, false, ply + 1, false);
                // Re-search la depth complet daca scorul ne surprinde
                if (!timeUp && score > alpha && reduction > 0) {
                    score = -alphaBeta(board, opponent, depth - 1, -alpha - 1, -alpha, false, ply + 1, false);
                }
                // Re-search PV daca scorul promite
                if (!timeUp && score > alpha && score < beta) {
                    score = -alphaBeta(board, opponent, depth - 1, -beta, -alpha, false, ply + 1, true);
                }
            }

            board.unmakeMove(move, state);
            if (timeUp) return 0;

            if (isQuiet) quietsSeen++;

            if (score > bestScore) {
                bestScore = score;
                bestMoveLocal = move;
                bestMovePacked = TranspositionTable.encodeMove(move);
                if (isRoot) {
                    bestMoveRoot = move;
                }
            }

            if (score > alpha) alpha = score;

            if (alpha >= beta) {
                // Beta cutoff — updateaza killers si history daca e tacuta
                if (isQuiet) {
                    int packed = TranspositionTable.encodeMove(move);
                    if (killer1[ply] != packed) {
                        killer2[ply] = killer1[ply];
                        killer1[ply] = packed;
                    }
                    int pieceIdx = historyIndex(board.getSquare(move.from()));
                    history[pieceIdx][move.to()] += depth * depth;
                }
                break;
            }
        }

        if (legalCount == 0) {
            // Nu exista mutari legale — mat sau pat
            if (inCheck) return -MATE_VAL + ply; // mat — preferam sa fim dati mat mai tarziu
            return 0; // pat
        }

        // -------- TT store --------
        int flag;
        if      (bestScore <= alphaOrig) flag = TranspositionTable.FLAG_UPPER;
        else if (bestScore >= beta)      flag = TranspositionTable.FLAG_LOWER;
        else                              flag = TranspositionTable.FLAG_EXACT;
        tt.store(ttKey, depth, bestScore, flag, bestMovePacked);

        return bestScore;
    }

    // -------------------------------------------------------------------------
    // Quiescence Search — neschimbat fata de baseline, doar adaugam ply
    // -------------------------------------------------------------------------
    private int quiescence(Board board, int color, int alpha, int beta, int ply) {
        if ((nodesSearched & 2047) == 0) {
            if (stopFlag) timeUp = true;
            else if (!ponderMode && System.currentTimeMillis() >= deadline) timeUp = true;
        }
        if (timeUp) return 0;

        nodesSearched++;
        if (ply > selDepth) selDepth = ply;

        int standPat = evaluator.evaluate(board, color);
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        int opponent = opponent(color);

        List<Move> captures = generator.generateCaptures(board, color);
        int[] scores = new int[captures.size()];
        for (int i = 0; i < captures.size(); i++) {
            scores[i] = scoreMove(board, captures.get(i), TranspositionTable.NO_MOVE, ply);
        }
        sortByScore(captures, scores);

        for (Move move : captures) {
            GameState state = board.makeMove(move);
            if (generator.isInCheck(board, color)) {
                board.unmakeMove(move, state);
                continue;
            }

            int score = -quiescence(board, opponent, -beta, -alpha, ply + 1);
            board.unmakeMove(move, state);

            if (timeUp) return 0;

            if (score >= beta) return beta;
            if (score > alpha) alpha = score;
        }

        return alpha;
    }

    // -------------------------------------------------------------------------
    // Move scoring (combinat — TT > capturi > killers > history)
    // -------------------------------------------------------------------------
    private int scoreMove(Board board, Move move, int ttMove, int ply) {
        int packed = TranspositionTable.encodeMove(move);
        if (packed == ttMove && ttMove != TranspositionTable.NO_MOVE) return SCORE_TT;

        if (move.isPromotion()) return SCORE_PROMO;

        int captured = board.getSquare(move.to());
        if (!Piece.isEmpty(captured)) {
            int attackerType = Piece.type(board.getSquare(move.from()));
            int victimType   = Piece.type(captured);
            return SCORE_CAPTURE + 10 * MVV_VALUES[victimType] - MVV_VALUES[attackerType];
        }
        if (move.isEnPassant()) {
            return SCORE_CAPTURE + 10 * MVV_VALUES[Piece.PAWN] - MVV_VALUES[Piece.PAWN];
        }

        if (ply < MAX_PLY) {
            if (packed == killer1[ply]) return SCORE_KILLER1;
            if (packed == killer2[ply]) return SCORE_KILLER2;
        }

        int pieceIdx = historyIndex(board.getSquare(move.from()));
        return history[pieceIdx][move.to()];
    }

    // Sortare descrescatoare in-place pe baza scorurilor
    private void sortByScore(List<Move> moves, int[] scores) {
        // insertion sort — eficient pentru liste mici (gen pana la ~40 mutari)
        for (int i = 1; i < moves.size(); i++) {
            int  s = scores[i];
            Move m = moves.get(i);
            int j = i - 1;
            while (j >= 0 && scores[j] < s) {
                scores[j + 1] = scores[j];
                moves.set(j + 1, moves.get(j));
                j--;
            }
            scores[j + 1] = s;
            moves.set(j + 1, m);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------
    private long ttKey(Board board, int color) {
        return board.getZobristHash() ^ (color == Piece.BLACK ? Board.ZOBRIST_BLACK_SIDE : 0);
    }

    private int opponent(int color) {
        return (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;
    }

    private int historyIndex(int piece) {
        // 0..5 = white P..K, 6..11 = black P..K
        return (Piece.type(piece) - 1) + (Piece.color(piece) == Piece.BLACK ? 6 : 0);
    }

    // Are tabara `color` material neagonist de pioni? (pentru NMP — evitam zugzwang)
    private boolean hasNonPawnMaterial(Board board, int color) {
        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getSquare(sq);
            if (Piece.isEmpty(piece) || Piece.color(piece) != color) continue;
            int t = Piece.type(piece);
            if (t == Piece.KNIGHT || t == Piece.BISHOP || t == Piece.ROOK || t == Piece.QUEEN) {
                return true;
            }
        }
        return false;
    }

    // Adevarat daca hash-ul curent a mai aparut in jocul real sau pe drumul de search.
    // Conservator: o singura aparitie anterioara e suficienta pentru a returna 0
    // (engine-ul evita liniile in care e o ciclare; previne si "pierderi prin repetitie"
    // in finaluri castigatoare).
    private boolean isRepetition(long hash, int ply) {
        for (int i = 0; i < gameHistoryLen; i++) {
            if (gameHistory[i] == hash) return true;
        }
        for (int i = 0; i < ply; i++) {
            if (searchHistory[i] == hash) return true;
        }
        return false;
    }

    // Material insuficient pentru mat → remiza automata
    // Acoperit: KvK, KvKN, KvKB, KNvK, KBvK
    // Nu acoperit: KBvKB cu nebuni pe culori diferite (rar; eval-ul gestioneaza adesea)
    private boolean hasInsufficientMaterial(Board board) {
        int whiteMinors = 0, blackMinors = 0;
        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getSquare(sq);
            if (Piece.isEmpty(piece)) continue;
            int t = Piece.type(piece);
            if (t == Piece.KING) continue;
            // Orice pion/tura/regina inseamna ca matul ramane posibil
            if (t == Piece.PAWN || t == Piece.ROOK || t == Piece.QUEEN) return false;
            // Nebun sau cal
            if (Piece.isWhite(piece)) whiteMinors++;
            else                       blackMinors++;
        }
        // KvK, KvK+minor, K+minor vs K
        return whiteMinors <= 1 && blackMinors <= 1;
    }
}
