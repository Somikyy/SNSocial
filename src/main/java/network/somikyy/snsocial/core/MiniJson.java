/*
 * SNSocial - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.snsocial.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader for Telegram and VK responses.
 *
 * <p>Hand-rolled rather than shaded: SNSocial ships zero runtime dependencies, and both APIs
 * return small, flat, well-formed documents where a full JSON library buys nothing. The parser
 * accepts exactly the JSON grammar (RFC 8259) and throws {@link JsonException} on anything
 * else - a malformed API response must surface as an error, not as a silently missing field.
 *
 * <p>Mapping: object → {@code Map<String,Object>} (insertion order kept), array →
 * {@code List<Object>}, string → {@code String}, number → {@code Long} when integral and it
 * fits, otherwise {@code Double}, plus {@code Boolean} and {@code null}. Longs matter here:
 * Telegram user IDs already exceed 2^31, so anything that narrows to int corrupts real IDs.
 */
public final class MiniJson {

    /** Malformed input. The message carries the offset to make API-response bugs reportable. */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonException(String message, int at) {
            super(message + " (offset " + at + ")");
        }
    }

    private final String text;
    private int pos;

    private MiniJson(String text) {
        this.text = text;
    }

    /** Parses one JSON document; trailing non-whitespace is an error. */
    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw new JsonException("trailing characters after JSON value", p.pos);
        }
        return value;
    }

    /** {@code parse} + cast to object, for documents whose root must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected a JSON object at the root", 0);
        }
        return (Map<String, Object>) value;
    }

    // ------------------------------------------------------------------ navigation helpers

    /** {@code map.get(key)} as a nested object, or null if absent or not an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** {@code map.get(key)} as a list, or an empty list if absent or not an array. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    /** {@code map.get(key)} as a string, or null. */
    public static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v instanceof String s ? s : null;
    }

    /** {@code map.get(key)} as a long, or the fallback. Accepts integral doubles too. */
    public static long asLong(Map<String, Object> map, String key, long fallback) {
        Object v = map == null ? null : map.get(key);
        if (v instanceof Long l) {
            return l;
        }
        if (v instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return (long) (double) d;
        }
        return fallback;
    }

    /** {@code map.get(key)} as a boolean, or the fallback. */
    public static boolean asBool(Map<String, Object> map, String key, boolean fallback) {
        Object v = map == null ? null : map.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    // ------------------------------------------------------------------------------ parser

    private Object readValue() {
        if (pos >= text.length()) {
            throw new JsonException("unexpected end of input", pos);
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonException("expected a quoted key", pos);
            }
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw new JsonException("expected ':' after key", pos);
            }
            pos++;
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new JsonException("expected ',' or '}' in object", pos);
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new JsonException("expected ',' or ']' in array", pos);
        }
    }

    private String readString() {
        StringBuilder sb = new StringBuilder();
        pos++; // opening quote
        while (true) {
            if (pos >= text.length()) {
                throw new JsonException("unterminated string", pos);
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) {
                throw new JsonException("unterminated escape", pos);
            }
            char e = text.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > text.length()) {
                        throw new JsonException("truncated \\u escape", pos);
                    }
                    try {
                        sb.append((char) Integer.parseInt(text, pos, pos + 4, 16));
                    } catch (NumberFormatException ex) {
                        throw new JsonException("bad \\u escape", pos);
                    }
                    pos += 4;
                    break;
                default:
                    throw new JsonException("unknown escape '\\" + e + "'", pos - 1);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
            pos++;
        }
        boolean integral = true;
        if (pos < text.length() && text.charAt(pos) == '.') {
            integral = false;
            pos++;
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            integral = false;
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                pos++;
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        String token = text.substring(start, pos);
        if (token.isEmpty() || "-".equals(token)) {
            throw new JsonException("expected a value", start);
        }
        try {
            if (integral) {
                return Long.parseLong(token);
            }
            return Double.parseDouble(token);
        } catch (NumberFormatException ex) {
            // An integer wider than long is legal JSON; both APIs stay far below that,
            // but degrade to double rather than fail so parsing never lies about shape.
            return Double.parseDouble(token);
        }
    }

    private void expect(String literal) {
        if (!text.startsWith(literal, pos)) {
            throw new JsonException("expected '" + literal + "'", pos);
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= text.length()) {
            throw new JsonException("unexpected end of input", pos);
        }
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }
}
