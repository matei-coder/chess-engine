package chess;

public class Board {

    // Tabla: 64 de patrate, indexate 0-63
    // Indexarea: square = row * 8 + col
    // row 0 = randul 1 (al albului), row 7 = randul 8 (al negrului)
    private int[] squares = new int[64];

    // -1 daca nu e posibil en passant, altfel indexul patratului tinta
    private int enPassantSquare = -1;

    // Drepturi de rocada: [alba mica, alba mare, neagra mica, neagra mare]
    private boolean[] castlingRights = { true, true, true, true };

    public Board() {
        loadStartingPosition();
    }

    // Pozitia initiala standard
    private void loadStartingPosition() {
        // Rangul 1 (row 0) — piesele albe
        squares[0]  = Piece.WHITE | Piece.ROOK;
        squares[1]  = Piece.WHITE | Piece.KNIGHT;
        squares[2]  = Piece.WHITE | Piece.BISHOP;
        squares[3]  = Piece.WHITE | Piece.QUEEN;
        squares[4]  = Piece.WHITE | Piece.KING;
        squares[5]  = Piece.WHITE | Piece.BISHOP;
        squares[6]  = Piece.WHITE | Piece.KNIGHT;
        squares[7]  = Piece.WHITE | Piece.ROOK;

        // Rangul 2 (row 1) — pioniil albi
        for (int col = 0; col < 8; col++) {
            squares[8 + col] = Piece.WHITE | Piece.PAWN;
        }

        // Rangurile 3-6 (row 2-5) — libere
        for (int sq = 16; sq < 48; sq++) {
            squares[sq] = Piece.NONE;
        }

        // Rangul 7 (row 6) — pionii negri
        for (int col = 0; col < 8; col++) {
            squares[48 + col] = Piece.BLACK | Piece.PAWN;
        }

        // Rangul 8 (row 7) — piesele negre
        squares[56] = Piece.BLACK | Piece.ROOK;
        squares[57] = Piece.BLACK | Piece.KNIGHT;
        squares[58] = Piece.BLACK | Piece.BISHOP;
        squares[59] = Piece.BLACK | Piece.QUEEN;
        squares[60] = Piece.BLACK | Piece.KING;
        squares[61] = Piece.BLACK | Piece.BISHOP;
        squares[62] = Piece.BLACK | Piece.KNIGHT;
        squares[63] = Piece.BLACK | Piece.ROOK;
    }

    public int getSquare(int index) {
        return squares[index];
    }

    public int getSquare(int row, int col) {
        return squares[row * 8 + col];
    }

    public void setSquare(int index, int piece) {
        squares[index] = piece;
    }

    public int getEnPassantSquare() { return enPassantSquare; }
    public void setEnPassantSquare(int sq) { enPassantSquare = sq; }

    public boolean[] getCastlingRights() { return castlingRights; }
    public void setCastlingRight(int index, boolean value) { castlingRights[index] = value; }

    // -------------------------------------------------------------------------
    // makeMove / unmakeMove
    // -------------------------------------------------------------------------

    public GameState makeMove(Move move) {
        // Salvam starea curenta inainte sa o modificam
        GameState state = new GameState(
            squares[move.to()],
            enPassantSquare,
            castlingRights
        );

        int from  = move.from();
        int to    = move.to();
        int piece = squares[from];
        int color = Piece.color(piece);

        // Resetam en passant — va fi setat din nou daca e dublu pas
        enPassantSquare = -1;

        switch (move.flag()) {
            case Move.FLAG_NONE, Move.FLAG_DOUBLE_PAWN -> {
                squares[to]   = piece;
                squares[from] = Piece.NONE;
                if (move.isDoublePawn()) {
                    // Patratul en passant e intre from si to
                    enPassantSquare = (from + to) / 2;
                }
            }
            case Move.FLAG_EN_PASSANT -> {
                squares[to]   = piece;
                squares[from] = Piece.NONE;
                // Pionul capturat e pe aceeasi coloana cu 'to', dar pe randul lui 'from'
                int capturedPawnSq = (color == Piece.WHITE) ? to - 8 : to + 8;
                squares[capturedPawnSq] = Piece.NONE;
            }
            case Move.FLAG_CASTLING -> {
                squares[to]   = piece;
                squares[from] = Piece.NONE;
                // Mutam si tura
                if (to == 6) {  // rocada mica alba
                    squares[5] = squares[7]; squares[7] = Piece.NONE;
                } else if (to == 2) {  // rocada mare alba
                    squares[3] = squares[0]; squares[0] = Piece.NONE;
                } else if (to == 62) { // rocada mica neagra
                    squares[61] = squares[63]; squares[63] = Piece.NONE;
                } else if (to == 58) { // rocada mare neagra
                    squares[59] = squares[56]; squares[56] = Piece.NONE;
                }
            }
            default -> {
                // Promotie
                int promoPiece = switch (move.flag()) {
                    case Move.FLAG_PROMO_QUEEN  -> color | Piece.QUEEN;
                    case Move.FLAG_PROMO_ROOK   -> color | Piece.ROOK;
                    case Move.FLAG_PROMO_BISHOP -> color | Piece.BISHOP;
                    case Move.FLAG_PROMO_KNIGHT -> color | Piece.KNIGHT;
                    default -> piece;
                };
                squares[to]   = promoPiece;
                squares[from] = Piece.NONE;
            }
        }

        // Actualizam drepturile de rocada daca regele sau tura s-au miscat
        updateCastlingRights(from, to);

        return state;
    }

    public void unmakeMove(Move move, GameState state) {
        int from  = move.from();
        int to    = move.to();
        int color = Piece.color(squares[to]);

        // Restauram starea anterioara
        enPassantSquare = state.enPassantSquare;
        castlingRights  = state.castlingRights;

        switch (move.flag()) {
            case Move.FLAG_NONE, Move.FLAG_DOUBLE_PAWN -> {
                squares[from] = squares[to];
                squares[to]   = state.capturedPiece;
            }
            case Move.FLAG_EN_PASSANT -> {
                squares[from] = squares[to];
                squares[to]   = Piece.NONE;
                int capturedPawnSq = (color == Piece.WHITE) ? to - 8 : to + 8;
                squares[capturedPawnSq] = (color == Piece.WHITE)
                    ? Piece.BLACK | Piece.PAWN
                    : Piece.WHITE | Piece.PAWN;
            }
            case Move.FLAG_CASTLING -> {
                squares[from] = squares[to];
                squares[to]   = Piece.NONE;
                // Refacem tura
                if (to == 6)  { squares[7]  = squares[5]; squares[5]  = Piece.NONE; }
                if (to == 2)  { squares[0]  = squares[3]; squares[3]  = Piece.NONE; }
                if (to == 62) { squares[63] = squares[61]; squares[61] = Piece.NONE; }
                if (to == 58) { squares[56] = squares[59]; squares[59] = Piece.NONE; }
            }
            default -> {
                // Promotie — refacem pionul
                squares[from] = color | Piece.PAWN;
                squares[to]   = state.capturedPiece;
            }
        }
    }

    private void updateCastlingRights(int from, int to) {
        // Daca regele s-a miscat, pierde ambele rocade
        if (from == 4)  { castlingRights[0] = false; castlingRights[1] = false; } // rege alb
        if (from == 60) { castlingRights[2] = false; castlingRights[3] = false; } // rege negru
        // Daca tura s-a miscat sau a fost capturata
        if (from == 7  || to == 7)  castlingRights[0] = false; // tura mica alba
        if (from == 0  || to == 0)  castlingRights[1] = false; // tura mare alba
        if (from == 63 || to == 63) castlingRights[2] = false; // tura mica neagra
        if (from == 56 || to == 56) castlingRights[3] = false; // tura mare neagra
    }

    // Afiseaza tabla in consola (util pentru debugging)
    public void print() {
        System.out.println("  a b c d e f g h");
        for (int row = 7; row >= 0; row--) {
            System.out.print((row + 1) + " ");
            for (int col = 0; col < 8; col++) {
                System.out.print(Piece.toChar(squares[row * 8 + col]) + " ");
            }
            System.out.println();
        }
        System.out.println();
    }
}
