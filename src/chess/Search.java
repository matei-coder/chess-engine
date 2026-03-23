package chess;

import java.util.List;

public class Search {

    private static final int INF      =  1_000_000;
    private static final int NEG_INF  = -1_000_000;

    private final MoveGenerator generator = new MoveGenerator();
    private final Evaluator     evaluator = new Evaluator();

    private Move  bestMove;
    private int   nodesSearched;

    // Intrarea publica: returneaza cea mai buna mutare la adancimea data
    public Move findBestMove(Board board, int colorToMove, int depth) {
        bestMove      = null;
        nodesSearched = 0;

        alphaBeta(board, colorToMove, depth, NEG_INF, INF, true);

        System.out.println("Noduri explorate: " + nodesSearched);
        return bestMove;
    }

    // -------------------------------------------------------------------------
    // Alpha-Beta
    //
    // Returneaza scorul pozitiei din perspectiva jucatorului la mutare.
    // isRoot = true doar pentru primul nivel, ca sa retinem bestMove.
    //
    // alpha = cel mai bun scor pe care MAX il poate garanta pana acum
    // beta  = cel mai bun scor pe care MIN il poate garanta pana acum
    //
    // Daca alpha >= beta → "cutoff" — nu mai are rost sa exploram
    // -------------------------------------------------------------------------
    private int alphaBeta(Board board, int color, int depth, int alpha, int beta, boolean isRoot) {
        nodesSearched++;

        // Cazul de baza: am ajuns la adancimea maxima → evaluam pozitia
        if (depth == 0) {
            return evaluator.evaluate(board, color);
        }

        List<Move> moves = generator.generateMoves(board, color);
        int opponent = (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;

        // Daca nu mai sunt mutari disponibile
        if (moves.isEmpty()) {
            // Verificam daca suntem in sah — daca da, e mat; daca nu, e pat
            // (pentru simplitate deocamdata returnam 0 — implementam sah mai tarziu)
            return 0;
        }

        int bestScore = NEG_INF;

        for (Move move : moves) {
            // Aplicam mutarea
            GameState state = board.makeMove(move);

            // Cautam recursiv la nivel mai adanc
            // Negam scorul: ce e bun pentru opponent e rau pentru noi
            int score = -alphaBeta(board, opponent, depth - 1, -beta, -alpha, false);

            // Desfacem mutarea
            board.unmakeMove(move, state);

            if (score > bestScore) {
                bestScore = score;
                if (isRoot) bestMove = move; // retinem mutarea de la nivelul 1
            }

            // Actualizam alpha
            alpha = Math.max(alpha, score);

            // Cutoff: adversarul nu va lasa aceasta ramura sa fie aleasa
            if (alpha >= beta) {
                break; // "beta cutoff" — taiem restul mutarilor
            }
        }

        return bestScore;
    }
}
