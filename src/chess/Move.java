package chess;

public class Move {

    // Codificam o mutare intr-un singur int pentru eficienta:
    // biti  0- 5 : patrat sursa   (0-63)
    // biti  6-11 : patrat destinatie (0-63)
    // biti 12-15 : flags (tipul mutarii)

    public static final int FLAG_NONE          = 0;
    public static final int FLAG_EN_PASSANT    = 1;
    public static final int FLAG_CASTLING      = 2;
    public static final int FLAG_PROMO_QUEEN   = 3;
    public static final int FLAG_PROMO_ROOK    = 4;
    public static final int FLAG_PROMO_BISHOP  = 5;
    public static final int FLAG_PROMO_KNIGHT  = 6;
    public static final int FLAG_DOUBLE_PAWN   = 7; // pion avansat 2 patrate

    private final int move; // datele mutarii codificate

    public Move(int from, int to) {
        this(from, to, FLAG_NONE);
    }

    public Move(int from, int to, int flag) {
        this.move = from | (to << 6) | (flag << 12);
    }

    public int from() {
        return move & 0x3F; // ultimii 6 biti
    }

    public int to() {
        return (move >> 6) & 0x3F;
    }

    public int flag() {
        return (move >> 12) & 0xF;
    }

    public boolean isPromotion() {
        int f = flag();
        return f >= FLAG_PROMO_QUEEN && f <= FLAG_PROMO_KNIGHT;
    }

    public boolean isEnPassant()  { return flag() == FLAG_EN_PASSANT; }
    public boolean isCastling()   { return flag() == FLAG_CASTLING; }
    public boolean isDoublePawn() { return flag() == FLAG_DOUBLE_PAWN; }

    // Conversie in notatie algebrica (ex: "e2e4", "e7e8q")
    @Override
    public String toString() {
        String s = squareName(from()) + squareName(to());
        return switch (flag()) {
            case FLAG_PROMO_QUEEN  -> s + "q";
            case FLAG_PROMO_ROOK   -> s + "r";
            case FLAG_PROMO_BISHOP -> s + "b";
            case FLAG_PROMO_KNIGHT -> s + "n";
            default -> s;
        };
    }

    public static String squareName(int sq) {
        char file = (char) ('a' + (sq % 8));
        char rank = (char) ('1' + (sq / 8));
        return "" + file + rank;
    }
}
