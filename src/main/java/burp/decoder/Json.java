package burp.decoder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser + pretty-printer.
 * Keeps object key order (LinkedHashMap) so decoded output matches the request.
 */
final class Json {

    private Json() {}

    /** Parse JSON text into Map / List / String / Number / Boolean / null. Throws on invalid input. */
    static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.readValue();
        p.skipWs();
        if (!p.end()) {
            throw new JsonException("Trailing characters at " + p.pos);
        }
        return v;
    }

    /** True if the string is (after trimming) plausibly a JSON object/array. */
    static boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        char c = t.charAt(0);
        return c == '{' || c == '[';
    }

    static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb, 0);
        return sb.toString();
    }

    /** Serialize with no whitespace - used to rebuild the wire body. */
    static String compact(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCompact(value, sb);
        return sb.toString();
    }

    private static void writeCompact(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (i++ > 0) sb.append(',');
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeCompact(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                writeCompact(list.get(i), sb);
            }
            sb.append(']');
        } else if (v instanceof String s) {
            writeString(s, sb);
        } else {
            sb.append(v.toString());
        }
    }

    private static void write(Object v, StringBuilder sb, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                pad(sb, indent + 1);
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(": ");
                write(e.getValue(), sb, indent + 1);
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) { sb.append("[]"); return; }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                pad(sb, indent + 1);
                write(list.get(i), sb, indent + 1);
                if (i < list.size() - 1) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append(']');
        } else if (v instanceof String s) {
            writeString(s, sb);
        } else {
            // Number (Raw) or Boolean -> raw token
            sb.append(v.toString());
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) sb.append("  ");
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    static final class JsonException extends RuntimeException {
        JsonException(String msg) { super(msg); }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        boolean end() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Object readValue() {
            skipWs();
            if (end()) throw new JsonException("Unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't', 'f' -> readBool();
                case 'n' -> readNull();
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (!end() && s.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWs();
                if (end() || s.charAt(pos) != '"') throw new JsonException("Expected key at " + pos);
                String key = readString();
                skipWs();
                if (end() || s.charAt(pos) != ':') throw new JsonException("Expected ':' at " + pos);
                pos++;
                Object val = readValue();
                map.put(key, val);
                skipWs();
                if (end()) throw new JsonException("Unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') break;
                if (c != ',') throw new JsonException("Expected ',' or '}' at " + (pos - 1));
            }
            return map;
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (!end() && s.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                Object val = readValue();
                list.add(val);
                skipWs();
                if (end()) throw new JsonException("Unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') break;
                if (c != ',') throw new JsonException("Expected ',' or ']' at " + (pos - 1));
            }
            return list;
        }

        private String readString() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening quote
            while (true) {
                if (end()) throw new JsonException("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    if (end()) throw new JsonException("Unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'u'  -> {
                            if (pos + 4 > s.length()) throw new JsonException("Bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("Bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object readBool() {
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new JsonException("Invalid literal at " + pos);
        }

        private Object readNull() {
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            throw new JsonException("Invalid literal at " + pos);
        }

        private Object readNumber() {
            int start = pos;
            while (!end()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
                else break;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) throw new JsonException("Invalid value at " + start);
            // Keep as raw token to avoid precision loss; writer prints verbatim.
            return new Raw(num);
        }
    }

    /** Numeric token printed verbatim. */
    static final class Raw {
        final String text;
        Raw(String text) { this.text = text; }
        @Override public String toString() { return text; }
    }
}
