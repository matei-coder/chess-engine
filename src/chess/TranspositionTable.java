package chess;

// Transposition Table cu strategie "always replace".
//
// Indexarea e maskata cu (size - 1), deci size trebuie sa fie putere de 2.
// Pentru o cerere de N MB, alegem cea mai mare putere de 2 care incape.
//
// Layout per intrare: 16 bytes
//   long key   — hash-ul complet (cu side-to-move XOR-uit)
//   long data  — depth(8) | flag(2) | score(s16) | move(16)
//
// Flag-uri:
//   EXACT  — scor exact
//   LOWER  — scorul real e >= score (beta cutoff)
//   UPPER  — scorul real e <= score (search-ul a esuat sub alpha)
public class TranspositionTable {

    public static final int FLAG_EXACT = 0;
    public static final int FLAG_LOWER = 1;
    public static final int FLAG_UPPER = 2;

    public static final int NO_MOVE = 0;

    private long[] keys;
    private long[] data;
    private int    mask;
    private int    sizeEntries;

    public TranspositionTable(int sizeMB) {
        resize(sizeMB);
    }

    public void resize(int sizeMB) {
        long bytes   = (long) sizeMB * 1024L * 1024L;
        long entries = Math.max(1, bytes / 16L);
        int  pow2    = 1;
        while ((long) pow2 * 2L <= entries) pow2 *= 2;
        this.sizeEntries = pow2;
        this.mask        = pow2 - 1;
        this.keys        = new long[pow2];
        this.data        = new long[pow2];
    }

    public void clear() {
        java.util.Arrays.fill(keys, 0L);
        java.util.Arrays.fill(data, 0L);
    }

    public int sizeEntries() { return sizeEntries; }

    // XOR trick pentru lock-free safety in Lazy SMP:
    //   store: keys[idx] = key XOR data; data[idx] = data
    //   probe: valid daca (keys[idx] XOR data[idx]) == key
    // Torn writes (un thread schimba keys, alt thread schimba data) sunt
    // detectate ca miss (mai sigur decat hit invalid).

    public long probe(long key) {
        int idx = (int)(key & mask);
        long storedKey = keys[idx];
        long storedData = data[idx];
        if ((storedKey ^ storedData) == key) return storedData;
        return 0L;
    }

    public boolean keyMatches(long key) {
        int idx = (int)(key & mask);
        return (keys[idx] ^ data[idx]) == key;
    }

    public void store(long key, int depth, int score, int flag, int packedMove) {
        int idx = (int)(key & mask);
        long packed = pack(depth, flag, score, packedMove);
        keys[idx] = key ^ packed;
        data[idx] = packed;
    }

    // -------------------------------------------------------------------------
    // Helpers de unpacking
    // -------------------------------------------------------------------------
    public static int unpackMove(long d)  { return (int)(d & 0xFFFFL); }
    public static int unpackScore(long d) { return (short)((d >> 16) & 0xFFFFL); }
    public static int unpackDepth(long d) { return (int)((d >> 32) & 0xFFL); }
    public static int unpackFlag(long d)  { return (int)((d >> 40) & 0x3L); }

    private static long pack(int depth, int flag, int score, int move) {
        long packed = 0L;
        packed |= (move & 0xFFFFL);
        packed |= ((long)(short) score & 0xFFFFL) << 16;
        packed |= ((long)(depth & 0xFF)) << 32;
        packed |= ((long)(flag  & 0x3)) << 40;
        return packed;
    }

    // -------------------------------------------------------------------------
    // Codarea unei Move in 15 biti pentru a o stoca in entry
    // Layout: flag(3) | from(6) | to(6)
    // -------------------------------------------------------------------------
    public static int encodeMove(Move m) {
        if (m == null) return NO_MOVE;
        return ((m.flag() & 0x7) << 12) | ((m.from() & 0x3F) << 6) | (m.to() & 0x3F);
    }

    public static Move decodeMove(int packed) {
        if (packed == NO_MOVE) return null;
        int to   = packed & 0x3F;
        int from = (packed >> 6) & 0x3F;
        int flag = (packed >> 12) & 0x7;
        return new Move(from, to, flag);
    }
}
