package chess;

import java.util.List;
import java.util.Scanner;

public class Main {

    private static final int DEPTH = 3;

    public static void main(String[] args) {
        // Mod UCI: "java chess.Main uci"  sau  primul argument = "uci"
        if (args.length > 0 && args[0].equalsIgnoreCase("uci")) {
            new Uci().run();
            return;
        }

        Board board       = new Board();
        InputParser parser = new InputParser();
        Search search     = new Search();
        Scanner scanner   = new Scanner(System.in);
        OpeningBook openingBook = OpeningBook.openConfigured();

        System.out.println("=== Chess Engine ===");
        System.out.println("Tu joci cu Alb. Scrie mutarile in format: e2e4");
        System.out.println("Comenzi: 'quit' pentru iesire, 'moves' pentru mutari disponibile");
        if (openingBook != null) {
            System.out.println("Opening book: " + openingBook.getPath());
        } else {
            System.out.println("Opening book: niciunul (engine-ul foloseste doar search)");
        }
        System.out.println();

        int colorToMove = Piece.WHITE;

        while (true) {
            board.print();

            // Verificam starea jocului la inceputul turului
            List<Move> legalMoves = parser.getLegalMoves(board, colorToMove);
            MoveGenerator gen = new MoveGenerator();

            if (legalMoves.isEmpty()) {
                if (gen.isInCheck(board, colorToMove)) {
                    String loser = (colorToMove == Piece.WHITE) ? "Alb" : "Negru";
                    System.out.println("*** SAH MAT! " + loser + " a pierdut. ***");
                } else {
                    System.out.println("*** PAT! Remiza. ***");
                }
                break;
            }

            if (colorToMove == Piece.WHITE) {
                // --- Tura jucatorului ---
                if (gen.isInCheck(board, Piece.WHITE)) {
                    System.out.println("  !! Esti in SAH !!");
                }

                Move move = null;
                while (move == null) {
                    System.out.print("Mutarea ta: ");
                    String input = scanner.nextLine().trim();

                    if (input.equals("quit")) {
                        System.out.println("La revedere!");
                        closeOpeningBook(openingBook);
                        return;
                    }
                    if (input.equals("moves")) {
                        System.out.print("Mutari posibile: ");
                        for (Move m : legalMoves) System.out.print(m + " ");
                        System.out.println();
                        continue;
                    }

                    move = parser.parse(input, board, Piece.WHITE);
                    if (move == null) {
                        System.out.println("  Mutare invalida sau ilegala. Incearca din nou.");
                    }
                }

                board.makeMove(move);
                colorToMove = Piece.BLACK;

            } else {
                // --- Tura engineului ---
                if (gen.isInCheck(board, Piece.BLACK)) {
                    System.out.println("  (Negru e in SAH)");
                }

                Move best = null;
                if (openingBook != null) {
                    OpeningBook.BookMove bookMove = openingBook.findBookMove(board, Piece.BLACK);
                    if (bookMove != null) {
                        best = bookMove.move;
                        System.out.println("[BOOK HIT] " + best
                            + "  (weight " + bookMove.weight
                            + ", " + bookMove.candidates + " candidates, "
                            + openingBook.getPath() + ")");
                    } else {
                        System.out.println("[BOOK MISS] pozitia nu e in "
                            + openingBook.getPath() + " — calculeaza...");
                    }
                } else {
                    System.out.println("[BOOK SKIP] niciun book incarcat — calculeaza...");
                }

                if (best == null) {
                    best = search.findBestMove(board, Piece.BLACK, DEPTH);
                }

                if (best == null) {
                    System.out.println("Engine-ul nu are mutari — joc terminat.");
                    break;
                }

                System.out.println("Engine joaca: " + best);
                board.makeMove(best);
                colorToMove = Piece.WHITE;
            }
        }

        scanner.close();
        closeOpeningBook(openingBook);
    }

    private static void closeOpeningBook(OpeningBook openingBook) {
        if (openingBook == null) return;
        try {
            openingBook.close();
        } catch (Exception ignored) {
        }
    }
}
