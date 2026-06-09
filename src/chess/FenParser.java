package chess;

public class FenParser {

    public static final String STARTING_FEN =
        "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    // Incarca un FEN in board si returneaza culoarea care muta
    public static int parse(String fen, Board board) {
        board.clear();
        String[] parts = fen.trim().split("\\s+");

        // --- 1. Plasarea pieselor ---
        // FEN incepe de la rank 8 (row=7 in indexarea noastra) spre rank 1 (row=0)
        int row = 7, col = 0;
        for (char c : parts[0].toCharArray()) {
            if (c == '/') {
                row--;
                col = 0;
            } else if (Character.isDigit(c)) {
                col += (c - '0'); // patrate goale
            } else {
                board.setSquare(row * 8 + col, charToPiece(c));
                col++;
            }
        }

        // --- 2. Culoarea care muta ---
        int colorToMove = Piece.WHITE;
        if (parts.length > 1 && parts[1].equals("b")) {
            colorToMove = Piece.BLACK;
        }

        // --- 3. Drepturile de rocada ---
        if (parts.length > 2) {
            String cr = parts[2];
            board.setCastlingRight(0, cr.contains("K")); // alba mica
            board.setCastlingRight(1, cr.contains("Q")); // alba mare
            board.setCastlingRight(2, cr.contains("k")); // neagra mica
            board.setCastlingRight(3, cr.contains("q")); // neagra mare
        }

        // --- 4. En passant ---
        if (parts.length > 3 && !parts[3].equals("-")) {
            board.setEnPassantSquare(InputParser.parseSquare(parts[3]));
        }

        // --- 5. Halfmove clock (pentru regula celor 50 de mutari) ---
        if (parts.length > 4) {
            try {
                board.setHalfmoveClock(Integer.parseInt(parts[4]));
            } catch (NumberFormatException ignored) {}
        }

        return colorToMove;
    }

    // Litera FEN → piesa codificata ca int
    private static int charToPiece(char c) {
        int color = Character.isUpperCase(c) ? Piece.WHITE : Piece.BLACK;
        int type = switch (Character.toLowerCase(c)) {
            case 'p' -> Piece.PAWN;
            case 'n' -> Piece.KNIGHT;
            case 'b' -> Piece.BISHOP;
            case 'r' -> Piece.ROOK;
            case 'q' -> Piece.QUEEN;
            case 'k' -> Piece.KING;
            default  -> Piece.NONE;
        };
        return color | type;
    }
}
