package chess;

/**
 * Evaluare statica a pozitiei.
 *
 * Toate valorile (material + PST-uri) provin din StyleOrchestrator —
 * Evaluator-ul nu mai stocheaza tablouri proprii. Asta permite ca
 * un MLP (sau alt sistem de personalizare) sa modifice stilul de joc
 * doar prin StyleOrchestrator.applyStyleModifiers().
 *
 * Hot path: evaluate() face UN field-load la inceput (hoist al array-ului
 * weights() intr-un local final), apoi totul e doar acces direct in array.
 * JIT inlineaza accesul → niciun overhead fata de static final array-uri.
 */
public final class Evaluator {

    private final StyleOrchestrator style;

    public Evaluator(StyleOrchestrator style) {
        if (style == null) throw new NullPointerException("style");
        this.style = style;
    }

    // Backward-compat: foloseste un StyleOrchestrator default cu valori empirice
    public Evaluator() {
        this(new StyleOrchestrator());
    }

    // Returneaza scorul din perspectiva albului:
    //   pozitiv = bine pentru alb
    //   negativ = bine pentru negru
    public int evaluate(Board board) {
        // Hoist o singura data — restul buclei e pur acces in array
        final int[] w = style.weights();

        int score = 0;
        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getSquare(sq);
            if (Piece.isEmpty(piece)) continue;

            int type    = Piece.type(piece);                        // 1..6
            int row     = sq >>> 3;
            int col     = sq & 7;
            int prow    = Piece.isWhite(piece) ? row : (7 - row);   // mirror rank pt negru
            int symFile = (col < 4) ? col : (7 - col);              // mirror file (a≡h, b≡g, ...)

            // Doua lookups in array-ul de weights:
            //   1) valoarea materiala (indexata dupa tipul piesei)
            //   2) bonus pozitional din PST-ul corespunzator tipului
            int material = w[StyleOrchestrator.MAT_PAWN + type - 1];
            int pst      = w[StyleOrchestrator.PST_BASE_BY_TYPE[type] + prow * 4 + symFile];

            int value = material + pst;
            if (Piece.isWhite(piece)) score += value;
            else                       score -= value;
        }
        return score;
    }

    // Util: scorul din perspectiva jucatorului la mutare
    public int evaluate(Board board, int colorToMove) {
        int score = evaluate(board);
        return (colorToMove == Piece.WHITE) ? score : -score;
    }
}
