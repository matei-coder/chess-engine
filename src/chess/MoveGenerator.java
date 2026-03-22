package chess;

import java.util.ArrayList;
import java.util.List;

public class MoveGenerator {

    // Directiile de miscare pentru piesele sliding:
    // Tura: N, S, E, W  (+8, -8, +1, -1)
    // Nebun: NE, NW, SE, SW  (+9, +7, -7, -9)
    // Regina: toate 8
    private static final int[] ROOK_DIRS   = { 8, -8, 1, -1 };
    private static final int[] BISHOP_DIRS = { 9, 7, -7, -9 };
    private static final int[] QUEEN_DIRS  = { 8, -8, 1, -1, 9, 7, -7, -9 };

    // Offseturile calului fata de pozitia curenta
    private static final int[] KNIGHT_JUMPS = { 17, 15, 10, 6, -6, -10, -15, -17 };

    // Offseturile regelui (un pas in orice directie)
    private static final int[] KING_MOVES = { 8, -8, 1, -1, 9, 7, -7, -9 };

    public List<Move> generateMoves(Board board, int colorToMove) {
        List<Move> moves = new ArrayList<>();

        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getSquare(sq);
            if (Piece.isEmpty(piece) || Piece.color(piece) != colorToMove) continue;

            switch (Piece.type(piece)) {
                case Piece.PAWN   -> generatePawnMoves(board, sq, colorToMove, moves);
                case Piece.KNIGHT -> generateKnightMoves(board, sq, colorToMove, moves);
                case Piece.BISHOP -> generateSlidingMoves(board, sq, colorToMove, BISHOP_DIRS, moves);
                case Piece.ROOK   -> generateSlidingMoves(board, sq, colorToMove, ROOK_DIRS, moves);
                case Piece.QUEEN  -> generateSlidingMoves(board, sq, colorToMove, QUEEN_DIRS, moves);
                case Piece.KING   -> generateKingMoves(board, sq, colorToMove, moves);
            }
        }

        return moves;
    }

    // -------------------------------------------------------------------------
    // PION
    // -------------------------------------------------------------------------
    private void generatePawnMoves(Board board, int sq, int color, List<Move> moves) {
        int dir    = (color == Piece.WHITE) ? 8 : -8;   // directia de inaintare
        int startRow = (color == Piece.WHITE) ? 1 : 6;  // randul de start (pentru dublu pas)
        int promoRow = (color == Piece.WHITE) ? 6 : 1;  // randul din care urmatorul pas = promotie
        int opponent = (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;

        int row = sq / 8;
        int col = sq % 8;

        // 1. Pas simplu inainte
        int oneStep = sq + dir;
        if (isValidSquare(oneStep) && Piece.isEmpty(board.getSquare(oneStep))) {
            if (row == promoRow) {
                addPromotions(sq, oneStep, moves);
            } else {
                moves.add(new Move(sq, oneStep));

                // 2. Dublu pas de pe randul de start
                if (row == startRow) {
                    int twoStep = sq + dir * 2;
                    if (Piece.isEmpty(board.getSquare(twoStep))) {
                        moves.add(new Move(sq, twoStep, Move.FLAG_DOUBLE_PAWN));
                    }
                }
            }
        }

        // 3. Capturi diagonale
        int[] captureCols = { col - 1, col + 1 };
        for (int targetCol : captureCols) {
            if (targetCol < 0 || targetCol > 7) continue;
            int targetSq = (row + dir / 8) * 8 + targetCol; // row + 1 sau row - 1
            // recalculam corect:
            int targetRow = row + (dir > 0 ? 1 : -1);
            targetSq = targetRow * 8 + targetCol;

            if (!isValidSquare(targetSq)) continue;

            int target = board.getSquare(targetSq);
            if (!Piece.isEmpty(target) && Piece.color(target) == opponent) {
                if (row == promoRow) {
                    addPromotions(sq, targetSq, moves);
                } else {
                    moves.add(new Move(sq, targetSq));
                }
            }

            // 4. En passant
            if (targetSq == board.getEnPassantSquare()) {
                moves.add(new Move(sq, targetSq, Move.FLAG_EN_PASSANT));
            }
        }
    }

    private void addPromotions(int from, int to, List<Move> moves) {
        moves.add(new Move(from, to, Move.FLAG_PROMO_QUEEN));
        moves.add(new Move(from, to, Move.FLAG_PROMO_ROOK));
        moves.add(new Move(from, to, Move.FLAG_PROMO_BISHOP));
        moves.add(new Move(from, to, Move.FLAG_PROMO_KNIGHT));
    }

    // -------------------------------------------------------------------------
    // CAL (jumping piece)
    // -------------------------------------------------------------------------
    private void generateKnightMoves(Board board, int sq, int color, List<Move> moves) {
        int col = sq % 8;

        for (int jump : KNIGHT_JUMPS) {
            int target = sq + jump;
            if (!isValidSquare(target)) continue;

            // Verificam "wrap-around": calul nu poate sari de pe coloana A pe H
            int targetCol = target % 8;
            if (Math.abs(col - targetCol) > 2) continue;

            int piece = board.getSquare(target);
            if (Piece.isEmpty(piece) || Piece.color(piece) != color) {
                moves.add(new Move(sq, target));
            }
        }
    }

    // -------------------------------------------------------------------------
    // PIESE SLIDING (tura, nebun, regina)
    // -------------------------------------------------------------------------
    private void generateSlidingMoves(Board board, int sq, int color, int[] dirs, List<Move> moves) {
        int col = sq % 8;

        for (int dir : dirs) {
            int current = sq;

            while (true) {
                int prevCol = current % 8;
                current += dir;

                if (!isValidSquare(current)) break;

                // Verificam wrap-around pe orizontala
                int curCol = current % 8;
                if (Math.abs(prevCol - curCol) > 1 &&
                    (dir == 1 || dir == -1 || dir == 9 || dir == -9 || dir == 7 || dir == -7)) {
                    break;
                }

                int piece = board.getSquare(current);
                if (Piece.isEmpty(piece)) {
                    moves.add(new Move(sq, current));
                } else {
                    if (Piece.color(piece) != color) {
                        moves.add(new Move(sq, current)); // captura
                    }
                    break; // blocat
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // REGE
    // -------------------------------------------------------------------------
    private void generateKingMoves(Board board, int sq, int color, List<Move> moves) {
        int col = sq % 8;

        for (int dir : KING_MOVES) {
            int target = sq + dir;
            if (!isValidSquare(target)) continue;

            // Wrap-around check
            int targetCol = target % 8;
            if (Math.abs(col - targetCol) > 1) continue;

            int piece = board.getSquare(target);
            if (Piece.isEmpty(piece) || Piece.color(piece) != color) {
                moves.add(new Move(sq, target));
            }
        }

        // Rocada — verificam drepturile si ca drumul e liber
        generateCastlingMoves(board, sq, color, moves);
    }

    private void generateCastlingMoves(Board board, int sq, int color, List<Move> moves) {
        boolean[] rights = board.getCastlingRights();

        if (color == Piece.WHITE && sq == 4) {
            // Rocada mica alba (e1-g1)
            if (rights[0] &&
                Piece.isEmpty(board.getSquare(5)) &&
                Piece.isEmpty(board.getSquare(6))) {
                moves.add(new Move(sq, 6, Move.FLAG_CASTLING));
            }
            // Rocada mare alba (e1-c1)
            if (rights[1] &&
                Piece.isEmpty(board.getSquare(3)) &&
                Piece.isEmpty(board.getSquare(2)) &&
                Piece.isEmpty(board.getSquare(1))) {
                moves.add(new Move(sq, 2, Move.FLAG_CASTLING));
            }
        }

        if (color == Piece.BLACK && sq == 60) {
            // Rocada mica neagra (e8-g8)
            if (rights[2] &&
                Piece.isEmpty(board.getSquare(61)) &&
                Piece.isEmpty(board.getSquare(62))) {
                moves.add(new Move(sq, 62, Move.FLAG_CASTLING));
            }
            // Rocada mare neagra (e8-c8)
            if (rights[3] &&
                Piece.isEmpty(board.getSquare(59)) &&
                Piece.isEmpty(board.getSquare(58)) &&
                Piece.isEmpty(board.getSquare(57))) {
                moves.add(new Move(sq, 58, Move.FLAG_CASTLING));
            }
        }
    }

    private boolean isValidSquare(int sq) {
        return sq >= 0 && sq < 64;
    }
}
