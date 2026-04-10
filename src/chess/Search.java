package chess;

import java.util.List;

public class Search {

    private static final int INF     =  1_000_000;
    private static final int NEG_INF = -1_000_000;

    // Valorile pieselor folosite exclusiv pentru ordonarea mutarilor (MVV-LVA)
    // Indexate dupa Piece.type(): NONE=0, PAWN=1, KNIGHT=2, BISHOP=3, ROOK=4, QUEEN=5, KING=6
    private static final int[] MVV_VALUES = { 0, 100, 320, 330, 500, 900, 20000 };

    private final MoveGenerator generator = new MoveGenerator();
    private final Evaluator     evaluator = new Evaluator();

    private Move    bestMove;
    private int     nodesSearched;
    private boolean timeUp;
    private long    startTime;
    private long    timeLimit; // milisecunde; Long.MAX_VALUE = fara limita

    // -------------------------------------------------------------------------
    // API public — adancime fixa (mod interactiv)
    // -------------------------------------------------------------------------
    public Move findBestMove(Board board, int colorToMove, int depth) {
        return search(board, colorToMove, depth, Long.MAX_VALUE);
    }

    // -------------------------------------------------------------------------
    // API public — limita de timp (mod UCI)
    // Foloseste iterative deepening: cauta 1, 2, 3, ... pana expira timpul
    // -------------------------------------------------------------------------
    public Move findBestMoveInTime(Board board, int colorToMove, long timeLimitMs) {
        return search(board, colorToMove, 99, timeLimitMs);
    }

    // -------------------------------------------------------------------------
    // Motorul de cautare unificat
    // maxDepth = adancimea maxima; timeLimitMs = limita de timp in ms
    // -------------------------------------------------------------------------
    private Move search(Board board, int colorToMove, int maxDepth, long timeLimitMs) {
        this.timeLimit = timeLimitMs;
        this.startTime = System.currentTimeMillis();
        this.timeUp    = false;

        Move best = null;

        for (int depth = 1; depth <= maxDepth; depth++) {
            bestMove      = null;
            nodesSearched = 0;

            int score = alphaBeta(board, colorToMove, depth, NEG_INF, INF, true);

            if (!timeUp) {
                // Iteratia s-a terminat complet — salvam rezultatul
                if (bestMove != null) best = bestMove;
                long elapsed = System.currentTimeMillis() - startTime;
                // Linia "info" e valida atat in UCI cat si in modul interactiv
                System.out.println("info depth " + depth
                    + " score cp " + score
                    + " nodes "   + nodesSearched
                    + " time "    + elapsed);
            }

            // Oprim daca am depasit timpul sau am atins adancimea maxima
            if (timeUp || System.currentTimeMillis() - startTime >= timeLimitMs) break;
        }

        return best;
    }

    // -------------------------------------------------------------------------
    // Alpha-Beta Negamax
    //
    // Returneaza scorul pozitiei din perspectiva jucatorului la mutare.
    // isRoot = true doar pentru primul nivel (retinem bestMove).
    //
    // alpha = cel mai bun scor garantat pentru jucatorul la mutare
    // beta  = cel mai bun scor garantat pentru adversar
    // -------------------------------------------------------------------------
    private int alphaBeta(Board board, int color, int depth, int alpha, int beta, boolean isRoot) {
        // Verificare timp (la fiecare 2048 noduri pentru a minimiza overhead)
        if ((nodesSearched & 2047) == 0 && timeLimit != Long.MAX_VALUE) {
            if (System.currentTimeMillis() - startTime >= timeLimit) {
                timeUp = true;
            }
        }
        if (timeUp) return 0; // valoarea nu conteaza, iteratia va fi ignorata

        nodesSearched++;

        if (depth == 0) {
            return quiescence(board, color, alpha, beta);
        }

        List<Move> moves = generator.generateMoves(board, color);
        moves.sort((a, b) -> scoreMove(board, b) - scoreMove(board, a));
        int opponent = (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;

        int bestScore  = NEG_INF;
        int legalCount = 0;

        for (Move move : moves) {
            GameState state = board.makeMove(move);

            if (generator.isInCheck(board, color)) {
                board.unmakeMove(move, state);
                continue;
            }
            legalCount++;

            int score = -alphaBeta(board, opponent, depth - 1, -beta, -alpha, false);
            board.unmakeMove(move, state);

            if (timeUp) return 0;

            if (score > bestScore) {
                bestScore = score;
                if (isRoot) bestMove = move;
            }

            alpha = Math.max(alpha, score);
            if (alpha >= beta) break; // beta cutoff
        }

        if (legalCount == 0) {
            if (generator.isInCheck(board, color)) {
                return NEG_INF + (1000 - depth); // mat — preferam sa fim dati mat mai tarziu
            } else {
                return 0; // pat
            }
        }

        return bestScore;
    }

    // -------------------------------------------------------------------------
    // Quiescence Search
    //
    // Apelat la depth=0 in loc de evaluate() direct.
    // Continua sa exploreze doar capturi pana pozitia devine "linistita".
    // Previne horizon effect: engineul nu mai crede ca e bine daca tocmai
    // urmeaza sa piarda o piesa.
    // -------------------------------------------------------------------------
    private int quiescence(Board board, int color, int alpha, int beta) {
        if ((nodesSearched & 2047) == 0 && timeLimit != Long.MAX_VALUE) {
            if (System.currentTimeMillis() - startTime >= timeLimit) timeUp = true;
        }
        if (timeUp) return 0;

        nodesSearched++;

        // Stand-pat: evaluam pozitia fara sa facem nicio mutare.
        // Presupunem ca jucatorul poate alege sa nu captureze nimic —
        // daca evaluarea statica e deja mai buna decat beta, ne oprim.
        int standPat = evaluator.evaluate(board, color);
        if (standPat >= beta) return beta;
        if (standPat > alpha) alpha = standPat;

        int opponent = (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;

        List<Move> captures = generator.generateCaptures(board, color);
        captures.sort((a, b) -> scoreMove(board, b) - scoreMove(board, a));

        for (Move move : captures) {
            GameState state = board.makeMove(move);
            if (generator.isInCheck(board, color)) {
                board.unmakeMove(move, state);
                continue;
            }

            int score = -quiescence(board, opponent, -beta, -alpha);
            board.unmakeMove(move, state);

            if (timeUp) return 0;

            if (score >= beta) return beta;  // beta cutoff
            if (score > alpha) alpha = score;
        }

        return alpha;
    }

    // -------------------------------------------------------------------------
    // Move ordering — MVV-LVA
    // -------------------------------------------------------------------------
    private int scoreMove(Board board, Move move) {
        if (move.isPromotion()) return 20_000;

        if (move.isEnPassant()) return 10 * MVV_VALUES[Piece.PAWN] - MVV_VALUES[Piece.PAWN];

        int captured = board.getSquare(move.to());
        if (!Piece.isEmpty(captured)) {
            int attackerType = Piece.type(board.getSquare(move.from()));
            int victimType   = Piece.type(captured);
            return 10 * MVV_VALUES[victimType] - MVV_VALUES[attackerType];
        }

        return 0;
    }
}
