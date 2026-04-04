package chess;

import java.util.List;

public class InputParser {

    private final MoveGenerator generator = new MoveGenerator();

    // Parseaza un string de forma "e2e4" sau "e7e8q" (promotie)
    // si returneaza mutarea corespunzatoare din lista de mutari legale
    // Returneaza null daca inputul e invalid sau mutarea nu e legala
    public Move parse(String input, Board board, int colorToMove) {
        input = input.trim().toLowerCase();

        if (input.length() < 4 || input.length() > 5) return null;

        int from = parseSquare(input.substring(0, 2));
        int to   = parseSquare(input.substring(2, 4));

        if (from == -1 || to == -1) return null;

        // Promotia — caracterul optional de la final (q, r, b, n)
        int promoFlag = Move.FLAG_NONE;
        if (input.length() == 5) {
            promoFlag = switch (input.charAt(4)) {
                case 'q' -> Move.FLAG_PROMO_QUEEN;
                case 'r' -> Move.FLAG_PROMO_ROOK;
                case 'b' -> Move.FLAG_PROMO_BISHOP;
                case 'n' -> Move.FLAG_PROMO_KNIGHT;
                default  -> Move.FLAG_NONE;
            };
            if (promoFlag == Move.FLAG_NONE) return null; // caracter invalid
        }

        // Cautam mutarea in lista de mutari legale
        List<Move> legalMoves = getLegalMoves(board, colorToMove);
        for (Move move : legalMoves) {
            if (move.from() != from || move.to() != to) continue;

            // Daca e promotie, trebuie sa se potriveasca si flag-ul
            if (move.isPromotion()) {
                if (move.flag() == promoFlag) return move;
            } else {
                return move;
            }
        }

        return null; // mutare ilegala
    }

    // Genereaza doar mutarile legale (filtrate de sah)
    public List<Move> getLegalMoves(Board board, int colorToMove) {
        List<Move> pseudoLegal = generator.generateMoves(board, colorToMove);
        List<Move> legal = new java.util.ArrayList<>();

        for (Move move : pseudoLegal) {
            GameState state = board.makeMove(move);
            if (!generator.isInCheck(board, colorToMove)) {
                legal.add(move);
            }
            board.unmakeMove(move, state);
        }

        return legal;
    }

    // Converteste "e2" → index 0-63, sau -1 daca e invalid
    public static int parseSquare(String s) {
        if (s.length() != 2) return -1;
        int col = s.charAt(0) - 'a'; // 'a'=0, 'h'=7
        int row = s.charAt(1) - '1'; // '1'=0, '8'=7
        if (col < 0 || col > 7 || row < 0 || row > 7) return -1;
        return row * 8 + col;
    }
}
