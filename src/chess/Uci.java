package chess;

import java.util.Scanner;

// Implementarea protocolului UCI (Universal Chess Interface)
//
// Fluxul standard:
//   GUI → engine:  uci
//   engine → GUI:  id name ...\n id author ...\n uciok
//   GUI → engine:  isready
//   engine → GUI:  readyok
//   GUI → engine:  position startpos moves e2e4 e7e5
//   GUI → engine:  go movetime 1000
//   engine → GUI:  info depth 1 score cp 30 nodes 20 time 2
//                  info depth 2 ...
//                  bestmove d2d4
//
public class Uci {

    private static final String ENGINE_NAME   = "ChessEngine";
    private static final String ENGINE_AUTHOR = "Student";

    private Board  board        = new Board();
    private int    colorToMove  = Piece.WHITE;

    private final Search      search      = new Search();
    private final InputParser inputParser = new InputParser();

    // Bucla principala UCI — ruleaza pana primim "quit"
    public void run() {
        Scanner scanner = new Scanner(System.in);

        while (scanner.hasNextLine()) {
            String   line   = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] tokens = line.split("\\s+");

            switch (tokens[0]) {
                case "uci"        -> handleUci();
                case "isready"    -> System.out.println("readyok");
                case "ucinewgame" -> handleNewGame();
                case "position"   -> handlePosition(tokens);
                case "go"         -> handleGo(tokens);
                case "setoption"  -> { /* ignoram optiunile — nu crash */ }
                case "stop"       -> { /* ignoram — fara thread separat deocamdata */ }
                case "ponderhit"  -> { /* ignoram */ }
                case "quit"       -> { return; }
            }
            // flush explicit — unele GUI-uri asteapta sa vada raspunsul imediat
            System.out.flush();
        }
    }

    // ------------------------------------------------------------------
    // uci — identifica engineul
    // ------------------------------------------------------------------
    private void handleUci() {
        System.out.println("id name "   + ENGINE_NAME);
        System.out.println("id author " + ENGINE_AUTHOR);
        // Declarăm opțiunile standard — valorile sunt ignorate deocamdată
        System.out.println("option name Hash type spin default 16 min 1 max 128");
        System.out.println("option name Threads type spin default 1 min 1 max 1");
        System.out.println("option name Move Overhead type spin default 30 min 0 max 5000");
        System.out.println("uciok");
    }

    // ------------------------------------------------------------------
    // ucinewgame — reseteaza pozitia
    // ------------------------------------------------------------------
    private void handleNewGame() {
        board        = new Board();
        colorToMove  = Piece.WHITE;
    }

    // ------------------------------------------------------------------
    // position startpos [moves e2e4 ...]
    // position fen <fen> [moves e2e4 ...]
    // ------------------------------------------------------------------
    private void handlePosition(String[] tokens) {
        if (tokens.length < 2) return;

        int movesIndex = -1; // indicele primei mutari din lista "moves"

        if (tokens[1].equals("startpos")) {
            board       = new Board();
            colorToMove = Piece.WHITE;
            for (int i = 2; i < tokens.length; i++) {
                if (tokens[i].equals("moves")) { movesIndex = i + 1; break; }
            }

        } else if (tokens[1].equals("fen")) {
            // FEN = tokens[2..] pana la "moves" sau pana la final
            StringBuilder fen = new StringBuilder();
            int i = 2;
            while (i < tokens.length && !tokens[i].equals("moves")) {
                fen.append(tokens[i]).append(' ');
                i++;
            }
            board       = new Board();
            colorToMove = FenParser.parse(fen.toString().trim(), board);
            if (i < tokens.length && tokens[i].equals("moves")) {
                movesIndex = i + 1;
            }
        }

        // Aplicam mutarile din lista (istoricul jocului curent)
        if (movesIndex != -1) {
            for (int i = movesIndex; i < tokens.length; i++) {
                Move move = inputParser.parse(tokens[i], board, colorToMove);
                if (move != null) {
                    board.makeMove(move);
                    colorToMove = opponent(colorToMove);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // go [depth n] [movetime ms] [wtime ms btime ms [winc ms] [binc ms]]
    // ------------------------------------------------------------------
    private void handleGo(String[] tokens) {
        int  depth       = -1;
        long moveTimeMs  = -1;
        long wtime = -1, btime = -1, winc = 0, binc = 0;

        for (int i = 1; i < tokens.length - 1; i++) {
            try {
                switch (tokens[i]) {
                    case "depth"    -> depth      = Integer.parseInt(tokens[i + 1]);
                    case "movetime" -> moveTimeMs = Long.parseLong(tokens[i + 1]);
                    case "wtime"    -> wtime       = Long.parseLong(tokens[i + 1]);
                    case "btime"    -> btime       = Long.parseLong(tokens[i + 1]);
                    case "winc"     -> winc        = Long.parseLong(tokens[i + 1]);
                    case "binc"     -> binc        = Long.parseLong(tokens[i + 1]);
                }
            } catch (NumberFormatException ignored) {}
        }

        Move best;

        if (depth > 0) {
            // Cautare la adancime fixa
            best = search.findBestMove(board, colorToMove, depth);

        } else if (moveTimeMs > 0) {
            // Timp fix per mutare
            best = search.findBestMoveInTime(board, colorToMove, moveTimeMs);

        } else if (colorToMove == Piece.WHITE && wtime > 0) {
            // Control de timp — aloca o portie din timpul ramas
            best = search.findBestMoveInTime(board, colorToMove, allocateTime(wtime, winc));

        } else if (colorToMove == Piece.BLACK && btime > 0) {
            best = search.findBestMoveInTime(board, colorToMove, allocateTime(btime, binc));

        } else {
            // Fallback: 3 secunde
            best = search.findBestMoveInTime(board, colorToMove, 3000);
        }

        // "bestmove" este singurul raspuns obligatoriu la comanda "go"
        System.out.println("bestmove " + (best != null ? best : "0000"));
        System.out.flush();
    }

    // Aloca ~1/30 din timpul ramas + increment (formula simpla dar practica)
    private long allocateTime(long remainingMs, long incMs) {
        return Math.max(remainingMs / 30 + incMs, 50);
    }

    private int opponent(int color) {
        return (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;
    }
}
