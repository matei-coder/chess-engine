package chess;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class OpeningBook implements Closeable {

    private static final int ENTRY_SIZE = 16;
    private static final int MAX_LEGAL_MOVES = 256;
    private static final String DEFAULT_BOOK_PATH = "books/gm2001.bin";
    private static final String DONNA_BOOK_ENV = "DONNA_BOOK";

    private final RandomAccessFile file;
    private final long entryCount;
    private final String path;
    private final InputParser parser = new InputParser();
    private final Random random = new Random();

    public static class BookMove {
        public final Move move;
        public final int weight;
        public final int candidates;

        private BookMove(Move move, int weight, int candidates) {
            this.move = move;
            this.weight = weight;
            this.candidates = candidates;
        }
    }

    private static class Entry {
        final long key;
        final int rawMove;
        final int weight;

        Entry(long key, int rawMove, int weight) {
            this.key = key;
            this.rawMove = rawMove;
            this.weight = weight;
        }
    }

    public OpeningBook(String path) throws IOException {
        this.path = path;
        this.file = new RandomAccessFile(path, "r");
        long length = file.length();
        if (length % ENTRY_SIZE != 0) {
            file.close();
            throw new IOException("invalid Polyglot book size: " + length);
        }
        this.entryCount = length / ENTRY_SIZE;
    }

    public static String configuredPath() {
        String envPath = System.getenv(DONNA_BOOK_ENV);
        if (envPath != null && !envPath.isBlank()) return envPath;
        if (Files.isRegularFile(Path.of(DEFAULT_BOOK_PATH))) return DEFAULT_BOOK_PATH;
        return null;
    }

    public static OpeningBook openConfigured() {
        String path = configuredPath();
        if (path == null) return null;
        try {
            return new OpeningBook(path);
        } catch (IOException ignored) {
            return null;
        }
    }

    public String getPath() {
        return path;
    }

    public synchronized BookMove findBookMove(Board board, int colorToMove) {
        long key = polyglotKey(board, colorToMove);

        try {
            long first = findFirstEntry(key);
            if (first < 0) return null;

            List<Move> legalMoves = parser.getLegalMoves(board, colorToMove);
            if (legalMoves.isEmpty()) return null;

            List<BookMove> candidates = new ArrayList<>();
            for (long index = first; index < entryCount && candidates.size() < MAX_LEGAL_MOVES; index++) {
                Entry entry = readEntry(index);
                if (entry.key != key) break;

                Move move = toLegalMove(entry.rawMove, legalMoves);
                if (move != null) {
                    candidates.add(new BookMove(move, entry.weight, 0));
                }
            }

            return chooseWeighted(candidates);
        } catch (IOException ignored) {
            return null;
        }
    }

    private BookMove chooseWeighted(List<BookMove> candidates) {
        if (candidates.isEmpty()) return null;

        long totalWeight = 0;
        for (BookMove candidate : candidates) {
            totalWeight += Math.max(0, candidate.weight);
        }

        BookMove chosen = candidates.get(0);
        if (totalWeight > 0) {
            long pick = nextLong(totalWeight);
            long seen = 0;
            for (BookMove candidate : candidates) {
                seen += Math.max(0, candidate.weight);
                if (pick < seen) {
                    chosen = candidate;
                    break;
                }
            }
        } else {
            for (BookMove candidate : candidates) {
                if (candidate.weight > chosen.weight) chosen = candidate;
            }
        }

        return new BookMove(chosen.move, chosen.weight, candidates.size());
    }

    private long nextLong(long bound) {
        long bits;
        long value;
        do {
            bits = random.nextLong() >>> 1;
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0L);
        return value;
    }

    private long findFirstEntry(long key) throws IOException {
        long low = 0;
        long high = entryCount;

        while (low < high) {
            long mid = (low + high) >>> 1;
            long midKey = readKey(mid);
            if (Long.compareUnsigned(midKey, key) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }

        if (low < entryCount && readKey(low) == key) return low;
        return -1;
    }

    private long readKey(long index) throws IOException {
        file.seek(index * ENTRY_SIZE);
        return file.readLong();
    }

    private Entry readEntry(long index) throws IOException {
        file.seek(index * ENTRY_SIZE);
        long key = file.readLong();
        int rawMove = file.readUnsignedShort();
        int weight = file.readUnsignedShort();
        file.readInt();
        return new Entry(key, rawMove, weight);
    }

    private Move toLegalMove(int rawMove, List<Move> legalMoves) {
        int to = rawMove & 0x3F;
        int from = (rawMove >>> 6) & 0x3F;
        int promotion = (rawMove >>> 12) & 0x7;

        to = normalizeCastlingTarget(from, to);
        int promotionFlag = promotionFlag(promotion);
        if (promotion != 0 && promotionFlag == Move.FLAG_NONE) return null;

        for (Move move : legalMoves) {
            if (move.from() != from || move.to() != to) continue;

            if (promotion == 0) {
                if (!move.isPromotion()) return move;
            } else if (move.flag() == promotionFlag) {
                return move;
            }
        }

        return null;
    }

    private static int normalizeCastlingTarget(int from, int to) {
        if (from == 4 && to == 7) return 6;   // e1h1 -> e1g1
        if (from == 4 && to == 0) return 2;   // e1a1 -> e1c1
        if (from == 60 && to == 63) return 62; // e8h8 -> e8g8
        if (from == 60 && to == 56) return 58; // e8a8 -> e8c8
        return to;
    }

    private static int promotionFlag(int promotion) {
        return switch (promotion) {
            case 1 -> Move.FLAG_PROMO_KNIGHT;
            case 2 -> Move.FLAG_PROMO_BISHOP;
            case 3 -> Move.FLAG_PROMO_ROOK;
            case 4 -> Move.FLAG_PROMO_QUEEN;
            default -> Move.FLAG_NONE;
        };
    }

    public static long polyglotKey(Board board, int colorToMove) {
        long key = 0;

        for (int sq = 0; sq < 64; sq++) {
            int piece = board.getSquare(sq);
            if (!Piece.isEmpty(piece)) {
                key ^= PolyglotRandom.RANDOM[64 * polyglotPieceIndex(piece) + sq];
            }
        }

        boolean[] castling = board.getCastlingRights();
        if (castling[0]) key ^= PolyglotRandom.RANDOM[768];
        if (castling[1]) key ^= PolyglotRandom.RANDOM[769];
        if (castling[2]) key ^= PolyglotRandom.RANDOM[770];
        if (castling[3]) key ^= PolyglotRandom.RANDOM[771];

        int ep = board.getEnPassantSquare();
        if (hasPolyglotEnPassant(board, colorToMove, ep)) {
            key ^= PolyglotRandom.RANDOM[772 + (ep % 8)];
        }

        if (colorToMove == Piece.WHITE) {
            key ^= PolyglotRandom.RANDOM[780];
        }

        return key;
    }

    private static int polyglotPieceIndex(int piece) {
        int base = switch (Piece.type(piece)) {
            case Piece.PAWN -> 0;
            case Piece.KNIGHT -> 2;
            case Piece.BISHOP -> 4;
            case Piece.ROOK -> 6;
            case Piece.QUEEN -> 8;
            case Piece.KING -> 10;
            default -> throw new IllegalArgumentException("empty piece");
        };
        return base + (Piece.color(piece) == Piece.WHITE ? 1 : 0);
    }

    private static boolean hasPolyglotEnPassant(Board board, int colorToMove, int epSquare) {
        if (epSquare < 0) return false;

        int epRow = epSquare / 8;
        int epCol = epSquare % 8;
        int pawnRow = colorToMove == Piece.WHITE ? epRow - 1 : epRow + 1;
        if (pawnRow < 0 || pawnRow > 7) return false;

        for (int dc : new int[]{ -1, 1 }) {
            int pawnCol = epCol + dc;
            if (pawnCol < 0 || pawnCol > 7) continue;

            int piece = board.getSquare(pawnRow * 8 + pawnCol);
            if (!Piece.isEmpty(piece)
                    && Piece.color(piece) == colorToMove
                    && Piece.type(piece) == Piece.PAWN) {
                return true;
            }
        }

        return false;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
