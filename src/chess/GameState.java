package chess;

// Stocheaza tot ce nu poate fi dedus dupa unmakeMove:
// piesa capturata, en passant, drepturile de rocada, hash-ul Zobrist,
// halfmove clock (pentru regula celor 50 de mutari)
public class GameState {
    public final int capturedPiece;
    public final int enPassantSquare;
    public final boolean[] castlingRights;
    public final long zobristHash;
    public final int halfmoveClock;

    public GameState(int capturedPiece, int enPassantSquare, boolean[] castlingRights,
                     long zobristHash, int halfmoveClock) {
        this.capturedPiece   = capturedPiece;
        this.enPassantSquare = enPassantSquare;
        this.castlingRights  = castlingRights.clone();
        this.zobristHash     = zobristHash;
        this.halfmoveClock   = halfmoveClock;
    }
}
