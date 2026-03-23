package chess;

public class Main {
    public static void main(String[] args) {
        Board board = new Board();
        board.print();

        Search search = new Search();

        System.out.println("Caut cea mai buna mutare pentru alb (adancime 3)...");
        Move best = search.findBestMove(board, Piece.WHITE, 3);
        System.out.println("Cea mai buna mutare: " + best);
    }
}
