package chess;

// Stocheaza tot ce nu poate fi dedus dupa unmakeMove:
// piesa capturata, en passant, drepturile de rocada
public class GameState {
    public final int capturedPiece;
    public final int enPassantSquare;
    public final boolean[] castlingRights;

    public GameState(int capturedPiece, int enPassantSquare, boolean[] castlingRights) {
        this.capturedPiece   = capturedPiece;
        this.enPassantSquare = enPassantSquare;
        // copiem array-ul — altfel referinta s-ar modifica
        this.castlingRights  = castlingRights.clone();
    }
}
