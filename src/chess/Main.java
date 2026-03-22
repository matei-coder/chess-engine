package chess;

public class Main {
    public static void main(String[] args) {
        Board board = new Board();
        board.print();

        Evaluator eval = new Evaluator();
        int score = eval.evaluate(board);
        System.out.println("Scor pozitie initiala: " + score);
        // Pozitia initiala e simetrica → scorul trebuie sa fie 0
    }
}
