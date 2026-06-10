package chess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Incarca un fisier de stil JSON si aplica multiplicatorii pe StyleOrchestrator.
 *
 * Format suportat (doua forme):
 *
 *   Forma "named" (uman-friendly, sparse):
 *   {
 *     "version": 1,
 *     "description": "aggressive blitz",
 *     "modifiers": {
 *       "MAT_PAWN": 1.3,
 *       "MAT_QUEEN": 1.1,
 *       "PST_PAWN": 1.2
 *     }
 *   }
 *   - Cheile nementionate raman la 1.0 (neutre).
 *   - O cheie poate fi un "broadcast group" (ex. "PST_PAWN" multiplica toate
 *     32 valorile PST_PAWN simultan).
 *
 *   Forma "vector" (compatibila cu output direct MLP):
 *   {
 *     "version": 1,
 *     "modifiers": [1.0, 1.0, 1.3, ..., 1.0]   // exact 200 elemente
 *   }
 *
 * Returneaza descrierea (sau null daca lipseste).
 *
 * Erori: throw IllegalArgumentException pentru JSON malformat sau vector
 * de marime gresita. Cheile necunoscute in forma named sunt ignorate cu
 * un mesaj logat la stderr (nu opresc parse-ul).
 */
public final class StyleLoader {

    private StyleLoader() {} // static-only

    public static String loadFromFile(Path path, StyleOrchestrator style) throws IOException {
        String json = Files.readString(path);
        return loadFromString(json, style);
    }

    public static String loadFromString(String json, StyleOrchestrator style) {
        Object root = new JsonParser(json).parseValue();
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("style JSON must be an object at top level");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> obj = (Map<String, Object>) root;

        // (Optional) version check — momentan acceptam orice
        Object versionObj = obj.get("version");
        if (versionObj != null && !(versionObj instanceof Number)) {
            throw new IllegalArgumentException("'version' must be a number");
        }

        Object description = obj.get("description");
        String descStr = (description instanceof String) ? (String) description : null;

        Object modifiers = obj.get("modifiers");
        if (modifiers == null) {
            throw new IllegalArgumentException("'modifiers' field is required");
        }

        float[] vector = new float[StyleOrchestrator.NUM_FEATURES];
        java.util.Arrays.fill(vector, 1.0f); // default = neutru

        if (modifiers instanceof List) {
            applyVectorForm((List<?>) modifiers, vector);
        } else if (modifiers instanceof Map) {
            applyNamedForm((Map<?, ?>) modifiers, vector);
        } else {
            throw new IllegalArgumentException("'modifiers' must be an object or array");
        }

        style.applyStyleModifiers(vector);
        return descStr;
    }

    private static void applyVectorForm(List<?> list, float[] vector) {
        if (list.size() != StyleOrchestrator.NUM_FEATURES) {
            throw new IllegalArgumentException(
                "vector form requires exactly " + StyleOrchestrator.NUM_FEATURES
                    + " elements, got " + list.size());
        }
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (!(v instanceof Number)) {
                throw new IllegalArgumentException("modifiers[" + i + "] must be a number");
            }
            vector[i] = ((Number) v).floatValue();
        }
    }

    private static void applyNamedForm(Map<?, ?> map, float[] vector) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("modifier key must be a string");
            }
            String name = (String) entry.getKey();
            Object v = entry.getValue();
            if (!(v instanceof Number)) {
                throw new IllegalArgumentException("modifier '" + name + "' must be a number");
            }
            float mult = ((Number) v).floatValue();

            int[] indices = StyleOrchestrator.BROADCAST_GROUPS.get(name);
            if (indices == null) {
                System.err.println("[StyleLoader] unknown feature name: " + name + " (ignored)");
                continue;
            }
            for (int idx : indices) {
                vector[idx] = mult;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Minimal recursive-descent JSON parser
    // -------------------------------------------------------------------------
    // Suporta: object, array, string, number (int/float/exponent), true, false, null.
    // String escapes suportate: quote, backslash, slash, n/t/r/b/f, unicode \\uXXXX.
    // NU suporta: comentarii.
    // -------------------------------------------------------------------------
    private static final class JsonParser {
        private final String src;
        private int pos;

        JsonParser(String src) {
            this.src = src;
            this.pos = 0;
        }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) throw err("unexpected end of input");
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                    else throw err("unexpected char '" + c + "'");
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWs();
                if (peek() != '"') throw err("expected string key");
                String key = parseString();
                skipWs();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == '}') { pos++; return map; }
                throw err("expected ',' or '}'");
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                Object val = parseValue();
                list.add(val);
                skipWs();
                char c = peek();
                if (c == ',') { pos++; continue; }
                if (c == ']') { pos++; return list; }
                throw err("expected ',' or ']'");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) throw err("unterminated escape");
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > src.length()) throw err("invalid \\u escape");
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw err("invalid escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            boolean isFloat = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            String s = src.substring(start, pos);
            return isFloat ? (Number) Double.parseDouble(s) : (Number) Long.parseLong(s);
        }

        private Boolean parseBool() {
            if (src.startsWith("true", pos))  { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw err("expected true/false");
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) { pos += 4; return null; }
            throw err("expected null");
        }

        private void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private char peek() {
            if (pos >= src.length()) throw err("unexpected end of input");
            return src.charAt(pos);
        }

        private void expect(char c) {
            if (pos >= src.length() || src.charAt(pos) != c) {
                throw err("expected '" + c + "'");
            }
            pos++;
        }

        private IllegalArgumentException err(String msg) {
            return new IllegalArgumentException("JSON parse error at pos " + pos + ": " + msg);
        }
    }
}
