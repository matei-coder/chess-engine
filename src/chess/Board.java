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
