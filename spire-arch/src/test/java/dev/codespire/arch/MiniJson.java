package dev.codespire.arch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict, minimal JSON reader for the file-shape checks in this module: objects become
 * {@link LinkedHashMap}, arrays {@link ArrayList}, numbers {@link BigDecimal}, and {@code true},
 * {@code false}, {@code null} their Java values.
 *
 * <p>It exists so this module keeps depending on nothing but JUnit (see its build file). It rejects
 * anything that is not one complete JSON value, so a truncated or hand-broken file fails the check
 * that reads it instead of parsing as something smaller.
 */
final class MiniJson {

    private final String text;
    private int at;

    private MiniJson(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        MiniJson reader = new MiniJson(text);
        reader.skipSpace();
        Object value = reader.value();
        reader.skipSpace();
        if (reader.at != text.length()) {
            throw reader.error("text after the JSON value");
        }
        return value;
    }

    private Object value() {
        if (at >= text.length()) {
            throw error("unexpected end of input");
        }
        char c = text.charAt(at);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        skipSpace();
        if (peek() == '}') {
            at++;
            return result;
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error("expected a key");
            }
            String key = string();
            skipSpace();
            expect(':');
            skipSpace();
            if (result.containsKey(key)) {
                throw error("duplicate key " + key);
            }
            result.put(key, value());
            skipSpace();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect('}');
            return result;
        }
    }

    private List<Object> array() {
        List<Object> result = new ArrayList<>();
        at++;
        skipSpace();
        if (peek() == ']') {
            at++;
            return result;
        }
        while (true) {
            skipSpace();
            result.add(value());
            skipSpace();
            if (peek() == ',') {
                at++;
                continue;
            }
            expect(']');
            return result;
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                }
                default -> throw error("bad escape \\" + escaped);
            }
        }
    }

    private Object literal(String word, Object result) {
        if (!text.startsWith(word, at)) {
            throw error("expected " + word);
        }
        at += word.length();
        return result;
    }

    private BigDecimal number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (start == at) {
            throw error("unexpected character '" + text.charAt(at) + "'");
        }
        return new BigDecimal(text.substring(start, at));
    }

    private void skipSpace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }

    private char peek() {
        if (at >= text.length()) {
            throw error("unexpected end of input");
        }
        return text.charAt(at);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        at++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("invalid JSON at offset " + at + ": " + what);
    }
}
