package chess;

public class Evaluator {

    // Valorile de baza ale pieselor (in "centipawni" — 100 = un pion)
    private static final int[] PIECE_VALUES = {
        0,    // NONE
        100,  // PAWN
        320,  // KNIGHT
        330,  // BISHOP
        500,  // ROOK
        900,  // QUEEN
        20000 // KING
    };

    // Piece-Square Tables — bonusuri de pozitie pentru fiecare piesa
    // Indexate din perspectiva ALBULUI (row 0 = randul 1 al albului)
    // Pentru negru, oglindim indexul: sq -> 63 - sq

    private static final int[] PST_PAWN = {
         0,  0,  0,  0,  0,  0,  0,  0,
        50, 50, 50, 50, 50, 50, 50, 50,  // rand 7 — aproape de promotie
        10, 10, 20, 30, 30, 20, 10, 10,
         5,  5, 10, 25, 25, 10,  5,  5,
         0,  0,  0, 20, 20,  0,  0,  0,
         5, -5,-10,  0,  0,-10, -5,  5,
         5, 10, 10,-20,-20, 10, 10,  5,
         0,  0,  0,  0,  0,  0,  0,  0   // rand 1 — niciodata nu ajunge aici
    };

    private static final int[] PST_KNIGHT = {
        -50,-40,-30,-30,-30,-30,-40,-50,
        -40,-20,  0,  0,  0,  0,-20,-40,
        -30,  0, 10, 15, 15, 10,  0,-30,
        -30,  5, 15, 20, 20, 15,  5,-30,
        -30,  0, 15, 20, 20, 15,  0,-30,
        -30,  5, 10, 15, 15, 10,  5,-30,
        -40,-20,  0,  5,  5,  0,-20,-40,
        -50,-40,-30,-30,-30,-30,-40,-50
    };

    private static final int[] PST_BISHOP = {
        -20,-10,-10,-10,-10,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5, 10, 10,  5,  0,-10,
        -10,  5,  5, 10, 10,  5,  5,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10, 10, 10, 10, 10, 10, 10,-10,
        -10,  5,  0,  0,  0,  0,  5,-10,
        -20,-10,-10,-10,-10,-10,-10,-20
    };

    private static final int[] PST_ROOK = {
         0,  0,  0,  0,  0,  0,  0,  0,
         5, 10, 10, 10, 10, 10, 10,  5,  // randul 7 — tura activa
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
         0,  0,  0,  5,  5,  0,  0,  0   // coloana d/e usor mai buna
    };

    private static final int[] PST_QUEEN = {
        -20,-10,-10, -5, -5,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5,  5,  5,  5,  0,-10,
         -5,  0,  5,  5,  5,  5,  0, -5,
          0,  0,  5,  5,  5,  5,  0, -5,
        -10,  5,  5,  5,  5,  5,  0,-10,
        -10,  0,  5,  0,  0,  0,  0,-10,
        -20,-10,-10, -5, -5,-10,-10,-20
    };

    // Regele — in deschidere/mijloc de joc vrea sa fie ascuns in spatele
    // pionilor (rocada), nu in centru
    private static final int[] PST_KING_MIDGAME = {
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -20,-30,-30,-40,-40,-30,-30,-20,
        -10,-20,-20,-20,-20,-20,-20,-10,
         20, 20,  0,  0,  0,  0, 20, 20,  // dupa rocada e ok
         20, 30, 10,  0,  0, 10, 30, 20
    };

    // -------------------------------------------------------------------------
    // Evaluarea principala
    // -------------------------------------------------------------------------

    // Returneaza scorul din perspectiva albului:
    //   pozitiv = bine pentru alb
    //   negativ = bine pentru negru
    public int evaluate(Board board) {
        int score = 0;

        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getSquare(sq);
            if (Piece.isEmpty(piece)) continue;

            int value = pieceValue(piece, sq);

            if (Piece.isWhite(piece)) {
                score += value;
            } else {
                score -= value;
            }
        }

        return score;
    }

    // Valoarea totala a unei piese = valoare de baza + bonus de pozitie
    private int pieceValue(int piece, int sq) {
        int type = Piece.type(piece);
        int base = PIECE_VALUES[type];
        int pst  = pstBonus(piece, sq);
        return base + pst;
    }

    // Bonus de pozitie din PST
    // Albul citeste tabelul normal (row 0 = baza lui)
    // Negrul oglindeste indexul (row 7 devine row 0 pentru el)
    private int pstBonus(int piece, int sq) {
        int index = Piece.isWhite(piece) ? sq : (63 - sq);

        return switch (Piece.type(piece)) {
            case Piece.PAWN   -> PST_PAWN[index];
            case Piece.KNIGHT -> PST_KNIGHT[index];
            case Piece.BISHOP -> PST_BISHOP[index];
            case Piece.ROOK   -> PST_ROOK[index];
            case Piece.QUEEN  -> PST_QUEEN[index];
            case Piece.KING   -> PST_KING_MIDGAME[index];
            default           -> 0;
        };
    }

    // Util: scorul din perspectiva jucatorului la mutare
    public int evaluate(Board board, int colorToMove) {
        int score = evaluate(board);
        return (colorToMove == Piece.WHITE) ? score : -score;
    }
}
