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

    // Halfmove clock — numara semi-mutari de la ultima captura/mutare de pion.
    // La 100 (50 mutari complete) se aplica regula celor 50 de mutari → remiza.
    private int halfmoveClock = 0;

    // -------------------------------------------------------------------------
    // Tablouri Zobrist — numere random per (piesa, patrat) si stari auxiliare
    // -------------------------------------------------------------------------
    // pieceIndex: 0-5 = White P,N,B,R,Q,K; 6-11 = Black P,N,B,R,Q,K
    private static final long[][] ZOBRIST_PIECE    = new long[12][64];
    private static final long[]   ZOBRIST_CASTLING = new long[4];
    private static final long[]   ZOBRIST_EP       = new long[8]; // indexat dupa coloana
    public  static final long     ZOBRIST_BLACK_SIDE;

    static {
        java.util.Random rng = new java.util.Random(0x1234ABCD5678EFL);
        for (int p = 0; p < 12; p++)
            for (int sq = 0; sq < 64; sq++)
                ZOBRIST_PIECE[p][sq] = rng.nextLong();
        for (int i = 0; i < 4; i++) ZOBRIST_CASTLING[i] = rng.nextLong();
        for (int i = 0; i < 8; i++) ZOBRIST_EP[i]       = rng.nextLong();
        ZOBRIST_BLACK_SIDE = rng.nextLong();
    }

    // Hash-ul pozitiei (fara side-to-move — XOR-uit extern de Search)
    private long zobristHash = 0L;

    public Board() {
        loadStartingPosition();
        recomputeZobristHash();
    }

    // Pozitia initiala standard
    private void loadStartingPosition() {
        squares[0]  = Piece.WHITE | Piece.ROOK;
        squares[1]  = Piece.WHITE | Piece.KNIGHT;
        squares[2]  = Piece.WHITE | Piece.BISHOP;
        squares[3]  = Piece.WHITE | Piece.QUEEN;
        squares[4]  = Piece.WHITE | Piece.KING;
        squares[5]  = Piece.WHITE | Piece.BISHOP;
        squares[6]  = Piece.WHITE | Piece.KNIGHT;
        squares[7]  = Piece.WHITE | Piece.ROOK;

        for (int col = 0; col < 8; col++) {
            squares[8 + col] = Piece.WHITE | Piece.PAWN;
        }

        for (int sq = 16; sq < 48; sq++) {
            squares[sq] = Piece.NONE;
        }

        for (int col = 0; col < 8; col++) {
            squares[48 + col] = Piece.BLACK | Piece.PAWN;
        }

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

    // Sterge toate piesele si reseteaza starea (folosit de FenParser)
    public void clear() {
        java.util.Arrays.fill(squares, Piece.NONE);
        enPassantSquare = -1;
        castlingRights  = new boolean[]{ false, false, false, false };
        zobristHash     = 0L;
        halfmoveClock   = 0;
    }

    public int getEnPassantSquare() { return enPassantSquare; }
    public void setEnPassantSquare(int sq) { enPassantSquare = sq; }

    public boolean[] getCastlingRights() { return castlingRights; }
    public void setCastlingRight(int index, boolean value) { castlingRights[index] = value; }

    public int  getHalfmoveClock()        { return halfmoveClock; }
    public void setHalfmoveClock(int hmc) { halfmoveClock = hmc; }

    public long getZobristHash() { return zobristHash; }

    // Recalculeaza hash-ul de la zero pe baza starii curente.
    // Necesara dupa FenParser (care nu actualizeaza hash-ul incremental).
    public void recomputeZobristHash() {
        long h = 0L;
        for (int sq = 0; sq < 64; sq++) {
            int piece = squares[sq];
            if (!Piece.isEmpty(piece)) {
                h ^= ZOBRIST_PIECE[zobristPieceIndex(piece)][sq];
            }
        }
        for (int i = 0; i < 4; i++) {
            if (castlingRights[i]) h ^= ZOBRIST_CASTLING[i];
        }
        if (enPassantSquare >= 0) {
            h ^= ZOBRIST_EP[enPassantSquare % 8];
        }
        zobristHash = h;
    }

    private static int zobristPieceIndex(int piece) {
        return (Piece.type(piece) - 1) + (Piece.color(piece) == Piece.BLACK ? 6 : 0);
    }

    private void hashTogglePiece(int piece, int sq) {
        if (!Piece.isEmpty(piece)) {
            zobristHash ^= ZOBRIST_PIECE[zobristPieceIndex(piece)][sq];
        }
    }

    // -------------------------------------------------------------------------
    // makeMove / unmakeMove
    // -------------------------------------------------------------------------

    public GameState makeMove(Move move) {
        // Salvam starea curenta inainte sa o modificam (inclusiv hash-ul)
        GameState state = new GameState(
            squares[move.to()],
            enPassantSquare,
            castlingRights,
            zobristHash,
            halfmoveClock
        );

        int from  = move.from();
        int to    = move.to();
        int piece = squares[from];
        int color = Piece.color(piece);
        int capturedPiece = squares[to];
        int oldEp = enPassantSquare;

        // Halfmove clock: 0 daca e mutare de pion sau captura (inclusiv EP), altfel +1
        boolean isPawnMove = Piece.type(piece) == Piece.PAWN;
        boolean isCapture  = !Piece.isEmpty(capturedPiece) || move.isEnPassant();
        if (isPawnMove || isCapture) halfmoveClock = 0;
        else halfmoveClock++;

        // Resetam en passant — va fi setat din nou daca e dublu pas
        enPassantSquare = -1;

        switch (move.flag()) {
            case Move.FLAG_NONE, Move.FLAG_DOUBLE_PAWN -> {
                // Hash incremental: scoatem piesa din from, scoatem capturata, punem piesa pe to
                hashTogglePiece(piece, from);
                if (!Piece.isEmpty(capturedPiece)) hashTogglePiece(capturedPiece, to);
                hashTogglePiece(piece, to);

                squares[to]   = piece;
                squares[from] = Piece.NONE;
                if (move.isDoublePawn()) {
                    enPassantSquare = (from + to) / 2;
                }
            }
            case Move.FLAG_EN_PASSANT -> {
                int capturedPawnSq = (color == Piece.WHITE) ? to - 8 : to + 8;
                int capturedPawn   = squares[capturedPawnSq];

                hashTogglePiece(piece, from);
                hashTogglePiece(piece, to);
                hashTogglePiece(capturedPawn, capturedPawnSq);

                squares[to]   = piece;
                squares[from] = Piece.NONE;
                squares[capturedPawnSq] = Piece.NONE;
            }
            case Move.FLAG_CASTLING -> {
                hashTogglePiece(piece, from);
                hashTogglePiece(piece, to);

                squares[to]   = piece;
                squares[from] = Piece.NONE;

                // Mutam tura — si ii actualizam hash-ul
                int rookFrom, rookTo;
                if (to == 6)       { rookFrom = 7;  rookTo = 5;  }
                else if (to == 2)  { rookFrom = 0;  rookTo = 3;  }
                else if (to == 62) { rookFrom = 63; rookTo = 61; }
                else               { rookFrom = 56; rookTo = 59; } // to == 58
                int rook = squares[rookFrom];
                hashTogglePiece(rook, rookFrom);
                hashTogglePiece(rook, rookTo);
                squares[rookTo]   = rook;
                squares[rookFrom] = Piece.NONE;
            }
            default -> {
                // Promotie (cu sau fara captura)
                int promoPiece = switch (move.flag()) {
                    case Move.FLAG_PROMO_QUEEN  -> color | Piece.QUEEN;
                    case Move.FLAG_PROMO_ROOK   -> color | Piece.ROOK;
                    case Move.FLAG_PROMO_BISHOP -> color | Piece.BISHOP;
                    case Move.FLAG_PROMO_KNIGHT -> color | Piece.KNIGHT;
                    default -> piece;
                };

                hashTogglePiece(piece, from);
                if (!Piece.isEmpty(capturedPiece)) hashTogglePiece(capturedPiece, to);
                hashTogglePiece(promoPiece, to);

                squares[to]   = promoPiece;
                squares[from] = Piece.NONE;
            }
        }

        // EP-square: XOR-uim contributia veche si pe cea noua (daca difera)
        if (oldEp >= 0)            zobristHash ^= ZOBRIST_EP[oldEp % 8];
        if (enPassantSquare >= 0)  zobristHash ^= ZOBRIST_EP[enPassantSquare % 8];

        // Drepturi de rocada — XOR-uim bitii care s-au schimbat
        boolean[] oldRights = state.castlingRights;
        updateCastlingRights(from, to);
        for (int i = 0; i < 4; i++) {
            if (oldRights[i] != castlingRights[i]) {
                zobristHash ^= ZOBRIST_CASTLING[i];
            }
        }

        return state;
    }

    public void unmakeMove(Move move, GameState state) {
        int from  = move.from();
        int to    = move.to();
        int color = Piece.color(squares[to]);

        // Restauram starea anterioara — inclusiv hash-ul, evitand recalcul incremental
        enPassantSquare = state.enPassantSquare;
        castlingRights  = state.castlingRights;
        zobristHash     = state.zobristHash;
        halfmoveClock   = state.halfmoveClock;

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
        if (from == 4)  { castlingRights[0] = false; castlingRights[1] = false; }
        if (from == 60) { castlingRights[2] = false; castlingRights[3] = false; }
        if (from == 7  || to == 7)  castlingRights[0] = false;
        if (from == 0  || to == 0)  castlingRights[1] = false;
        if (from == 63 || to == 63) castlingRights[2] = false;
        if (from == 56 || to == 56) castlingRights[3] = false;
    }

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
