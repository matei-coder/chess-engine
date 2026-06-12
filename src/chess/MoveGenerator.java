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

    // Genereaza doar capturi si promotii — folosit de quiescence search
    public List<Move> generateCaptures(Board board, int colorToMove) {
        List<Move> moves = new ArrayList<>();

        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getSquare(sq);
            if (Piece.isEmpty(piece) || Piece.color(piece) != colorToMove) continue;

            switch (Piece.type(piece)) {
                case Piece.PAWN   -> generatePawnCaptures(board, sq, colorToMove, moves);
                case Piece.KNIGHT -> generateKnightCaptures(board, sq, colorToMove, moves);
                case Piece.BISHOP -> generateSlidingCaptures(board, sq, colorToMove, BISHOP_DIRS, moves);
                case Piece.ROOK   -> generateSlidingCaptures(board, sq, colorToMove, ROOK_DIRS, moves);
                case Piece.QUEEN  -> generateSlidingCaptures(board, sq, colorToMove, QUEEN_DIRS, moves);
                case Piece.KING   -> generateKingCaptures(board, sq, colorToMove, moves);
            }
        }

        return moves;
    }

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
    // Metode helper — doar capturi (pentru quiescence search)
    // -------------------------------------------------------------------------

    private void generatePawnCaptures(Board board, int sq, int color, List<Move> moves) {
        int opponent = (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;
        int row      = sq / 8;
        int col      = sq % 8;
        int promoRow = (color == Piece.WHITE) ? 6 : 1;
        int targetRow = (color == Piece.WHITE) ? row + 1 : row - 1;

        for (int dc : new int[]{ -1, 1 }) {
            int targetCol = col + dc;
            if (targetCol < 0 || targetCol > 7) continue;
            int targetSq = targetRow * 8 + targetCol;

            int target = board.getSquare(targetSq);
            if (!Piece.isEmpty(target) && Piece.color(target) == opponent) {
                if (row == promoRow) {
                    addPromotions(sq, targetSq, moves);
                } else {
                    moves.add(new Move(sq, targetSq));
                }
            }

            if (targetSq == board.getEnPassantSquare()) {
                moves.add(new Move(sq, targetSq, Move.FLAG_EN_PASSANT));
            }
        }

        // Promotie fara captura (pas simplu) — tot o mutare "zgomotoasa"
        int dir     = (color == Piece.WHITE) ? 8 : -8;
        int oneStep = sq + dir;
        if (row == promoRow && isValidSquare(oneStep) && Piece.isEmpty(board.getSquare(oneStep))) {
            addPromotions(sq, oneStep, moves);
        }
    }

    private void generateKnightCaptures(Board board, int sq, int color, List<Move> moves) {
        int col = sq % 8;
        for (int jump : KNIGHT_JUMPS) {
            int target = sq + jump;
            if (!isValidSquare(target)) continue;
            if (Math.abs(col - target % 8) > 2) continue;
            int piece = board.getSquare(target);
            if (!Piece.isEmpty(piece) && Piece.color(piece) != color) {
                moves.add(new Move(sq, target));
            }
        }
    }

    private void generateSlidingCaptures(Board board, int sq, int color, int[] dirs, List<Move> moves) {
        for (int dir : dirs) {
            int current = sq;
            while (true) {
                int prevCol = current % 8;
                current += dir;
                if (!isValidSquare(current)) break;
                int curCol = current % 8;
                if (Math.abs(prevCol - curCol) > 1 &&
                    (dir == 1 || dir == -1 || dir == 9 || dir == -9 || dir == 7 || dir == -7)) break;

                int piece = board.getSquare(current);
                if (!Piece.isEmpty(piece)) {
                    if (Piece.color(piece) != color) moves.add(new Move(sq, current));
                    break; // oprim in orice caz — e blocat
                }
            }
        }
    }

    private void generateKingCaptures(Board board, int sq, int color, List<Move> moves) {
        int col = sq % 8;
        for (int dir : KING_MOVES) {
            int target = sq + dir;
            if (!isValidSquare(target)) continue;
            if (Math.abs(col - target % 8) > 1) continue;
            int piece = board.getSquare(target);
            if (!Piece.isEmpty(piece) && Piece.color(piece) != color) {
                moves.add(new Move(sq, target));
            }
        }
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
        int opp = (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;

        // Regele nu poate roca daca e in sah
        if (isSquareAttacked(board, sq, opp)) return;

        if (color == Piece.WHITE && sq == 4) {
            // Rocada mica alba (e1-g1)
            // Patratele f1(5) si g1(6) trebuie sa fie libere si neatacare
            if (rights[0] &&
                Piece.isEmpty(board.getSquare(5)) &&
                Piece.isEmpty(board.getSquare(6)) &&
                !isSquareAttacked(board, 5, opp) &&
                !isSquareAttacked(board, 6, opp)) {
                moves.add(new Move(sq, 6, Move.FLAG_CASTLING));
            }
            // Rocada mare alba (e1-c1)
            // Patratele d1(3) si c1(2) nu pot fi atacate (b1(1) doar liber)
            if (rights[1] &&
                Piece.isEmpty(board.getSquare(3)) &&
                Piece.isEmpty(board.getSquare(2)) &&
                Piece.isEmpty(board.getSquare(1)) &&
                !isSquareAttacked(board, 3, opp) &&
                !isSquareAttacked(board, 2, opp)) {
                moves.add(new Move(sq, 2, Move.FLAG_CASTLING));
            }
        }

        if (color == Piece.BLACK && sq == 60) {
            // Rocada mica neagra (e8-g8)
            if (rights[2] &&
                Piece.isEmpty(board.getSquare(61)) &&
                Piece.isEmpty(board.getSquare(62)) &&
                !isSquareAttacked(board, 61, opp) &&
                !isSquareAttacked(board, 62, opp)) {
                moves.add(new Move(sq, 62, Move.FLAG_CASTLING));
            }
            // Rocada mare neagra (e8-c8)
            if (rights[3] &&
                Piece.isEmpty(board.getSquare(59)) &&
                Piece.isEmpty(board.getSquare(58)) &&
                Piece.isEmpty(board.getSquare(57)) &&
                !isSquareAttacked(board, 59, opp) &&
                !isSquareAttacked(board, 58, opp)) {
                moves.add(new Move(sq, 58, Move.FLAG_CASTLING));
            }
        }
    }

    private boolean isValidSquare(int sq) {
        return sq >= 0 && sq < 64;
    }

    // -------------------------------------------------------------------------
    // DETECTIA SAHULUI
    // -------------------------------------------------------------------------

    // Returneaza true daca `color` e in sah pe tabla curenta.
    // Foloseste cache-ul de rege din Board (O(1) vs O(64) scan).
    public boolean isInCheck(Board board, int color) {
        int kingSq = board.getKingSq(color);
        if (kingSq == -1) return false;
        int attacker = (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;
        return isSquareAttacked(board, kingSq, attacker);
    }

    // Backward-compat — deleguăm la cache. Niciun caller intern nu mai e activ.
    public int findKing(Board board, int color) {
        return board.getKingSq(color);
    }

    // Returneaza true daca patratul `sq` e atacat de `attackerColor`
    // Logica: plecam din `sq` si "vedem" daca gasim un atacator in fiecare directie
    public boolean isSquareAttacked(Board board, int sq, int attackerColor) {
        int col = sq % 8;

        // --- Atacuri de tura / regina pe linii ortogonale ---
        for (int dir : ROOK_DIRS) {
            int cur = sq;
            while (true) {
                int prevCol = cur % 8;
                cur += dir;
                if (!isValidSquare(cur)) break;
                int curCol = cur % 8;
                if ((dir == 1 || dir == -1) && Math.abs(prevCol - curCol) != 1) break;

                int piece = board.getSquare(cur);
                if (!Piece.isEmpty(piece)) {
                    if (Piece.color(piece) == attackerColor) {
                        int t = Piece.type(piece);
                        if (t == Piece.ROOK || t == Piece.QUEEN) return true;
                    }
                    break; // blocat de o piesa, indiferent de culoare
                }
            }
        }

        // --- Atacuri de nebun / regina pe diagonale ---
        for (int dir : BISHOP_DIRS) {
            int cur = sq;
            while (true) {
                int prevCol = cur % 8;
                cur += dir;
                if (!isValidSquare(cur)) break;
                int curCol = cur % 8;
                if (Math.abs(prevCol - curCol) != 1) break; // wrap-around

                int piece = board.getSquare(cur);
                if (!Piece.isEmpty(piece)) {
                    if (Piece.color(piece) == attackerColor) {
                        int t = Piece.type(piece);
                        if (t == Piece.BISHOP || t == Piece.QUEEN) return true;
                    }
                    break;
                }
            }
        }

        // --- Atacuri de cal ---
        for (int jump : KNIGHT_JUMPS) {
            int target = sq + jump;
            if (!isValidSquare(target)) continue;
            if (Math.abs(col - target % 8) > 2) continue;
            int piece = board.getSquare(target);
            if (!Piece.isEmpty(piece) && Piece.color(piece) == attackerColor
                    && Piece.type(piece) == Piece.KNIGHT) return true;
        }

        // --- Atacuri de pion ---
        // Un pion alb ataca in sus-diagonal, unul negru in jos-diagonal
        int pawnDir = (attackerColor == Piece.WHITE) ? -1 : 1; // din ce directie vin atacurile
        for (int dc : new int[]{ -1, 1 }) {
            int targetRow = sq / 8 + pawnDir;
            int targetCol = col + dc;
            if (targetRow < 0 || targetRow > 7 || targetCol < 0 || targetCol > 7) continue;
            int target = targetRow * 8 + targetCol;
            int piece = board.getSquare(target);
            if (!Piece.isEmpty(piece) && Piece.color(piece) == attackerColor
                    && Piece.type(piece) == Piece.PAWN) return true;
        }

        // --- Atacuri de rege ---
        for (int dir : KING_MOVES) {
            int target = sq + dir;
            if (!isValidSquare(target)) continue;
            if (Math.abs(col - target % 8) > 1) continue;
            int piece = board.getSquare(target);
            if (!Piece.isEmpty(piece) && Piece.color(piece) == attackerColor
                    && Piece.type(piece) == Piece.KING) return true;
        }

        return false;
    }
}
