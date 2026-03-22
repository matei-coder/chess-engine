package chess;

public class Piece {

    // Tipurile de piese — folosim constante int pentru eficienta
    public static final int NONE   = 0;
    public static final int PAWN   = 1;
    public static final int KNIGHT = 2;
    public static final int BISHOP = 3;
    public static final int ROOK   = 4;
    public static final int QUEEN  = 5;
    public static final int KING   = 6;

    // Culorile
    public static final int WHITE = 8;
    public static final int BLACK = 16;

    // Reprezentam o piesa ca un singur int: culoare | tip
    // Ex: pion alb = WHITE | PAWN = 8 | 1 = 9
    //     tura neagra = BLACK | ROOK = 16 | 4 = 20

    public static int type(int piece) {
        return piece & 0b00111; // ultimii 3 biti = tipul
    }

    public static int color(int piece) {
        return piece & 0b11000; // bitii 3-4 = culoarea
    }

    public static boolean isWhite(int piece) {
        return color(piece) == WHITE;
    }

    public static boolean isBlack(int piece) {
        return color(piece) == BLACK;
    }

    public static boolean isEmpty(int piece) {
        return piece == NONE;
    }

    // Util pentru debugging
    public static char toChar(int piece) {
        char c = switch (type(piece)) {
            case PAWN   -> 'p';
            case KNIGHT -> 'n';
            case BISHOP -> 'b';
            case ROOK   -> 'r';
            case QUEEN  -> 'q';
            case KING   -> 'k';
            default     -> '.';
        };
        return isWhite(piece) ? Character.toUpperCase(c) : c;
    }
}
