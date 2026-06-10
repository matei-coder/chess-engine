package chess;

import java.io.IOException;
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
    private static final String ENGINE_AUTHOR = "mchiriac";

    private Board  board        = new Board();
    private int    colorToMove  = Piece.WHITE;

    private final Search      search      = new Search();
    private final InputParser inputParser = new InputParser();
    private boolean     ownBook     = true;
    private OpeningBook openingBook = OpeningBook.openConfigured();

    // Istoricul jocului — hash-urile pozitiilor INAINTE de fiecare mutare aplicata.
    // Trimis catre Search pentru detectarea repetitiei.
    private long[] gameHistory    = new long[1024];
    private int    gameHistoryLen = 0;

    // Thread-ul de search (background). E null cand niciun search nu ruleaza.
    private Thread searchThread = null;

    // Timpul alocat search-ului curent, salvat pentru transitia ponderhit.
    // (Cand suntem in ponder mode si primim ponderhit, folosim asta.)
    private long pendingPonderTimeMs = 3000;

    // Anunta UCI ca suportam pondering — apare in handshake
    private void announcePonder() {
        System.out.println("option name Ponder type check default true");
    }

    // Bucla principala UCI — ruleaza pana primim "quit"
    public void run() {
        Scanner scanner = new Scanner(System.in);

        while (scanner.hasNextLine()) {
            String   line   = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            String[] tokens = line.split("\\s+");

            switch (tokens[0]) {
                case "uci"        -> handleUci();
                case "isready"    -> { waitForSearch(false); System.out.println("readyok"); }
                case "ucinewgame" -> { waitForSearch(true);  handleNewGame(); }
                case "position"   -> { waitForSearch(true);  handlePosition(tokens); }
                case "go"         -> { waitForSearch(true);  handleGo(tokens); }
                case "setoption"  -> handleSetOption(tokens);
                case "stop"       -> handleStop();
                case "ponderhit"  -> handlePonderhit();
                case "quit"       -> { waitForSearch(true); closeOpeningBook(); return; }
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
        System.out.println("option name OwnBook type check default true");
        String defaultBook = OpeningBook.configuredPath();
        System.out.println("option name BookFile type string default " + (defaultBook != null ? defaultBook : ""));
        System.out.println("option name StyleFile type string default ");
        announcePonder();
        System.out.println("uciok");
    }

    // ------------------------------------------------------------------
    // ucinewgame — reseteaza pozitia
    // ------------------------------------------------------------------
    private void handleNewGame() {
        board          = new Board();
        colorToMove    = Piece.WHITE;
        gameHistoryLen = 0;
        search.clearHash();
    }

    // ------------------------------------------------------------------
    // position startpos [moves e2e4 ...]
    // position fen <fen> [moves e2e4 ...]
    // ------------------------------------------------------------------
    private void handlePosition(String[] tokens) {
        if (tokens.length < 2) return;

        int movesIndex = -1; // indicele primei mutari din lista "moves"
        gameHistoryLen = 0;  // resetam istoricul pentru fiecare pozitie noua

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
            // FenParser nu actualizeaza hash-ul Zobrist — recalculam de la zero
            board.recomputeZobristHash();
            if (i < tokens.length && tokens[i].equals("moves")) {
                movesIndex = i + 1;
            }
        }

        // Aplicam mutarile din lista (istoricul jocului curent).
        // INAINTE de fiecare mutare inregistram hash-ul (cu side XOR) pentru
        // detectarea repetitiei in search.
        if (movesIndex != -1) {
            for (int i = movesIndex; i < tokens.length; i++) {
                long hash = board.getZobristHash()
                    ^ (colorToMove == Piece.BLACK ? Board.ZOBRIST_BLACK_SIDE : 0);
                if (gameHistoryLen < gameHistory.length) {
                    gameHistory[gameHistoryLen++] = hash;
                }
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
        boolean isPonder = false;

        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].equals("ponder")) { isPonder = true; continue; }
            if (i + 1 >= tokens.length) break;
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

        // Calculam timpul alocat search-ului (folosit fie acum, fie la ponderhit)
        long allocatedMs;
        int  searchDepth;
        if (depth > 0) {
            allocatedMs = Long.MAX_VALUE / 2; // sub limita, ca search-ul sa fie limitat doar de depth
            searchDepth = depth;
        } else if (moveTimeMs > 0) {
            allocatedMs = moveTimeMs;
            searchDepth = 99;
        } else if (colorToMove == Piece.WHITE && wtime > 0) {
            allocatedMs = allocateTime(wtime, winc);
            searchDepth = 99;
        } else if (colorToMove == Piece.BLACK && btime > 0) {
            allocatedMs = allocateTime(btime, binc);
            searchDepth = 99;
        } else {
            allocatedMs = 3000;
            searchDepth = 99;
        }
        pendingPonderTimeMs = allocatedMs;

        // Book — doar in mod normal, nu in ponder (in ponder cautam intentionat).
        // Daca pondering-ul nimereste in book, GUI-ul nu primeste "bestmove"
        // pana cand nu se intampla ponderhit/stop — pierdem oportunitatea.
        if (!isPonder) {
            OpeningBook.BookMove bookMove = ownBook && openingBook != null
                ? openingBook.findBookMove(board, colorToMove)
                : null;
            if (bookMove != null) {
                System.out.println("info string book HIT " + bookMove.move
                    + " weight " + bookMove.weight
                    + " candidates " + bookMove.candidates
                    + " (" + openingBook.getPath() + ")");
                System.out.println("bestmove " + bookMove.move);
                System.out.flush();
                return;
            }

            if (!ownBook) {
                System.out.println("info string book SKIP — OwnBook=false, searching");
            } else if (openingBook == null) {
                System.out.println("info string book SKIP — no book file loaded, searching");
            } else {
                System.out.println("info string book MISS — position not in "
                    + openingBook.getPath() + ", searching");
            }
        }

        // Pasam istoricul jocului pentru detectarea repetitiei
        search.setGameHistory(gameHistory, gameHistoryLen);

        // Snapshot pentru worker (board-ul si culoarea sunt impartite — nu modificam
        // pana cand worker-ul termina; vezi waitForSearch in switch-ul principal)
        final Board boardRef    = board;
        final int   colorRef    = colorToMove;
        final int   finalDepth  = searchDepth;
        final long  finalTimeMs = allocatedMs;
        final boolean ponderFlag = isPonder;

        Thread t = new Thread(() -> {
            Move best   = search.runSearch(boardRef, colorRef, finalDepth, finalTimeMs, ponderFlag);
            Move ponder = search.getPonderMove(boardRef, colorRef, best);

            StringBuilder out = new StringBuilder("bestmove ")
                .append(best != null ? best.toString() : "0000");
            if (ponder != null) out.append(" ponder ").append(ponder);
            System.out.println(out);
            System.out.flush();
        }, "Search-Worker");
        searchThread = t;
        t.start();
    }

    // Anunta search-ul ca s-a intamplat ponderhit — switch din ponder mode la
    // mod cu deadline. Search-ul curent continua, nu pornim unul nou.
    private void handlePonderhit() {
        search.ponderhit(pendingPonderTimeMs);
    }

    // Anunta search-ul sa abandoneze imediat. Worker-ul va printa bestmove si va muri.
    private void handleStop() {
        search.stop();
    }

    // Asteapta thread-ul de search sa se termine. Daca abortFirst, ii spunem si sa se opreasca.
    private void waitForSearch(boolean abortFirst) {
        Thread t = searchThread;
        if (t == null || !t.isAlive()) return;
        if (abortFirst) search.stop();
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        searchThread = null;
    }

    private void handleSetOption(String[] tokens) {
        StringBuilder name = new StringBuilder();
        StringBuilder value = new StringBuilder();
        boolean readingName = false;
        boolean readingValue = false;

        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].equalsIgnoreCase("name")) {
                readingName = true;
                readingValue = false;
                continue;
            }
            if (tokens[i].equalsIgnoreCase("value")) {
                readingName = false;
                readingValue = true;
                continue;
            }

            if (readingName) {
                if (name.length() > 0) name.append(' ');
                name.append(tokens[i]);
            } else if (readingValue) {
                if (value.length() > 0) value.append(' ');
                value.append(tokens[i]);
            }
        }

        String optionName = name.toString().trim();
        String optionValue = value.toString().trim();

        if (optionName.equalsIgnoreCase("OwnBook")) {
            ownBook = parseCheck(optionValue);
        } else if (optionName.equalsIgnoreCase("BookFile")) {
            setBookFile(optionValue);
        } else if (optionName.equalsIgnoreCase("Hash")) {
            try {
                int mb = Integer.parseInt(optionValue);
                search.setHashSizeMB(mb);
            } catch (NumberFormatException ignored) {}
        } else if (optionName.equalsIgnoreCase("StyleFile")) {
            setStyleFile(optionValue);
        }
    }

    // Incarca un fisier de stil JSON sau, daca path-ul e gol, reseteaza la baseline.
    private void setStyleFile(String path) {
        if (path == null || path.isBlank()) {
            search.getStyle().resetToBaseline();
            System.out.println("info string style reset to baseline");
            return;
        }
        try {
            String desc = StyleLoader.loadFromFile(java.nio.file.Path.of(path), search.getStyle());
            System.out.println("info string style loaded from " + path
                + (desc != null ? " (\"" + desc + "\")" : ""));
        } catch (java.io.IOException e) {
            System.out.println("info string failed to read style file " + path + ": " + e.getMessage());
        } catch (RuntimeException e) {
            System.out.println("info string invalid style file " + path + ": " + e.getMessage());
        }
    }

    private boolean parseCheck(String value) {
        return value.equalsIgnoreCase("true")
            || value.equalsIgnoreCase("yes")
            || value.equals("1");
    }

    private void setBookFile(String path) {
        if (path.isBlank()) {
            closeOpeningBook();
            openingBook = null;
            return;
        }

        try {
            OpeningBook nextBook = new OpeningBook(path);
            closeOpeningBook();
            openingBook = nextBook;
        } catch (IOException e) {
            System.out.println("info string could not load opening book " + path);
        }
    }

    private void closeOpeningBook() {
        if (openingBook == null) return;
        try {
            openingBook.close();
        } catch (IOException ignored) {
        } finally {
            openingBook = null;
        }
    }

    // Aloca ~1/30 din timpul ramas + increment (formula simpla dar practica)
    private long allocateTime(long remainingMs, long incMs) {
        return Math.max(50 , Math.min(remainingMs / 30 + incMs, 5*1000));
    }

    private int opponent(int color) {
        return (color == Piece.WHITE) ? Piece.BLACK : Piece.WHITE;
    }
}
