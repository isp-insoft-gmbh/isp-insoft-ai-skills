package fxdriver;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Json {
    private Json() {}

    static boolean hasKey(final String body, final String key) {
        return fieldColon(body, key) >= 0;
    }

    static boolean hasTopLevelKey(final String body, final String key) {
        var depth = 0;
        for (var i = 0; i < body.length(); i++) {
            final var c = body.charAt(i);
            if (c == '"') {
                final var end = stringEnd(body, i);
                var colon = end;
                while (colon < body.length() && Character.isWhitespace(body.charAt(colon))) {
                    colon++;
                }
                if (depth == 1
                        && colon < body.length()
                        && body.charAt(colon) == ':'
                        && key.equals(decodeString(body, i, end))) {
                    return true;
                }
                i = end - 1;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return false;
    }

    static boolean hasInvalidStringEscape(final String json) {
        var inString = false;
        for (var i = 0; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (!inString) {
                inString = c == '"';
                continue;
            }
            if (c == '"') {
                inString = false;
            } else if (c == '\\') {
                if (++i >= json.length()) {
                    return true;
                }
                final var escaped = json.charAt(i);
                if (escaped == 'u') {
                    if (i + 4 >= json.length()) {
                        return true;
                    }
                    for (var digit = 1; digit <= 4; digit++) {
                        if (Character.digit(json.charAt(i + digit), 16) < 0) {
                            return true;
                        }
                    }
                    i += 4;
                } else if ("\"\\/bfnrt".indexOf(escaped) < 0) {
                    return true;
                }
            } else if (c < 0x20) {
                return true;
            }
        }
        return false;
    }

    static String repairInvalidStringEscapes(final String json) {
        final var out = new StringBuilder(json.length());
        var cursor = 0;
        while (cursor < json.length()) {
            final var quote = json.indexOf('"', cursor);
            if (quote < 0) {
                out.append(json, cursor, json.length());
                break;
            }
            final var end = stringEnd(json, quote);
            out.append(json, cursor, quote);
            final var string = json.substring(quote, end);
            if (hasInvalidStringEscape(string)) {
                out.append('"')
                        .append(string.substring(1, string.length() - 1).replace("\\", "\\\\"))
                        .append('"');
            } else {
                out.append(string);
            }
            cursor = end;
        }
        return out.toString();
    }

    static String id(final String body) {
        final var colon = fieldColon(body, "id");
        if (colon < 0) {
            return "null";
        }
        var end = colon + 1;
        while (end < body.length() && Character.isWhitespace(body.charAt(end))) {
            end++;
        }
        final var start = end;
        while (end < body.length() && ",}\n\r\t ".indexOf(body.charAt(end)) < 0) {
            end++;
        }
        final var value = body.substring(start, end);
        return value.isBlank() ? "null" : value;
    }

    static int intValue(final String body, final String key, final int fallback) {
        final var colon = fieldColon(body, key);
        if (colon < 0) {
            return fallback;
        }
        var start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        var end = start;
        while (end < body.length()
                && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) {
            end++;
        }
        try {
            return Integer.parseInt(body.substring(start, end));
        } catch (final RuntimeException exception) {
            return fallback;
        }
    }

    static int requiredInt(final String json, final String key) throws IOException {
        final var fallback = Integer.MIN_VALUE;
        final var value = intValue(json, key, fallback);
        if (value == fallback) {
            throw new IOException("missing JSON field: " + key);
        }
        return value;
    }

    static boolean booleanValue(final String body, final String key, final boolean fallback) {
        final var colon = fieldColon(body, key);
        if (colon < 0) {
            return fallback;
        }
        final var value = body.substring(colon + 1).stripLeading();
        if (value.startsWith("true")) {
            return true;
        }
        if (value.startsWith("false")) {
            return false;
        }
        return fallback;
    }

    static String string(final String body, final String key) {
        final var colon = fieldColon(body, key);
        if (colon < 0) {
            return "";
        }
        var quote = colon + 1;
        while (quote < body.length() && Character.isWhitespace(body.charAt(quote))) {
            quote++;
        }
        if (quote >= body.length() || body.charAt(quote) != '"') {
            return "";
        }
        return decodeString(body, quote, stringEnd(body, quote));
    }

    static String scalar(final String body, final String key) {
        final var colon = fieldColon(body, key);
        if (colon < 0) {
            return "";
        }
        var start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        if (start < body.length() && body.charAt(start) == '"') {
            return decodeString(body, start, stringEnd(body, start));
        }
        var end = start;
        while (end < body.length() && ",}]\n\r\t ".indexOf(body.charAt(end)) < 0) {
            end++;
        }
        return body.substring(start, end);
    }

    static String requiredString(final String json, final String key) throws IOException {
        if (!hasKey(json, key)) {
            throw new IOException("missing JSON field: " + key);
        }
        return string(json, key);
    }

    static List<String> stringArray(final String body, final String key) {
        final var colon = fieldColon(body, key);
        if (colon < 0) {
            return List.of();
        }
        var start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }
        if (start >= body.length() || body.charAt(start) != '[') {
            return List.of();
        }
        start++;
        final var items = new ArrayList<String>();
        while (start < body.length()) {
            while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
                start++;
            }
            if (start >= body.length() || body.charAt(start) == ']') {
                break;
            }
            if (body.charAt(start) == ',') {
                start++;
                continue;
            }
            if (body.charAt(start) != '"') {
                start++;
                continue;
            }
            final var end = stringEnd(body, start);
            items.add(decodeString(body, start, end));
            start = end;
        }
        return items;
    }

    static String strings(final List<String> values) {
        final var out = new StringBuilder();
        for (var i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(escape(values.get(i))).append('"');
        }
        return out.toString();
    }

    static String escape(final String value) {
        final var out = new StringBuilder();
        appendString(out, value);
        return out.substring(1, out.length() - 1);
    }

    static Object parse(final String json) {
        final var parser = new Parser(Objects.requireNonNull(json));
        final var value = parser.value();
        parser.whitespace();
        if (!parser.done()) {
            throw parser.error("trailing input");
        }
        return value;
    }

    static Map<String, Object> object(final String json) {
        final var value = parse(json);
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            final var object = (Map<String, Object>) map;
            return object;
        }
        throw new IllegalArgumentException("JSON value is not an object");
    }

    static String stringify(final Object value) {
        final var out = new StringBuilder();
        appendJson(out, value);
        return out.toString();
    }

    private static void appendJson(final StringBuilder out, final Object value) {
        switch (value) {
            case null -> out.append("null");
            case String string -> appendString(out, string);
            case Boolean bool -> out.append(bool);
            case Byte _, Short _, Integer _, Long _, BigDecimal _ -> out.append(value);
            case Float number -> appendFiniteNumber(out, number.doubleValue());
            case Double number -> appendFiniteNumber(out, number);
            case Map<?, ?> map -> {
                out.append('{');
                var first = true;
                for (final var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("JSON object key is not a string");
                    }
                    if (!first) {
                        out.append(',');
                    }
                    appendString(out, key);
                    out.append(':');
                    appendJson(out, entry.getValue());
                    first = false;
                }
                out.append('}');
            }
            case Iterable<?> values -> {
                out.append('[');
                var first = true;
                for (final var item : values) {
                    if (!first) {
                        out.append(',');
                    }
                    appendJson(out, item);
                    first = false;
                }
                out.append(']');
            }
            default ->
                    throw new IllegalArgumentException(
                            "unsupported JSON value: " + value.getClass());
        }
    }

    private static void appendFiniteNumber(final StringBuilder out, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("JSON number is not finite");
        }
        out.append(value);
    }

    private static void appendString(final StringBuilder out, final String value) {
        out.append('"');
        for (var i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20
                            || (Character.isHighSurrogate(c)
                                    && (i + 1 >= value.length()
                                            || !Character.isLowSurrogate(value.charAt(i + 1))))
                            || (Character.isLowSurrogate(c)
                                    && (i == 0
                                            || !Character.isHighSurrogate(value.charAt(i - 1))))) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static int fieldColon(final String body, final String key) {
        var i = 0;
        while (i < body.length()) {
            if (body.charAt(i) != '"') {
                i++;
                continue;
            }
            final var end = stringEnd(body, i);
            var cursor = end;
            while (cursor < body.length() && Character.isWhitespace(body.charAt(cursor))) {
                cursor++;
            }
            if (cursor < body.length()
                    && body.charAt(cursor) == ':'
                    && key.equals(decodeString(body, i, end))) {
                return cursor;
            }
            i = end;
        }
        return -1;
    }

    private static int stringEnd(final String body, final int quote) {
        var escaped = false;
        for (var i = quote + 1; i < body.length(); i++) {
            final var c = body.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i + 1;
            }
        }
        return body.length();
    }

    private static String decodeString(final String body, final int quote, final int end) {
        final var out = new StringBuilder();
        var i = quote + 1;
        while (i < end - 1) {
            final var c = body.charAt(i++);
            if (c == '\\' && i < end - 1) {
                final var escaped = body.charAt(i++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (i + 4 <= end - 1) {
                            out.append((char) Integer.parseInt(body.substring(i, i + 4), 16));
                            i += 4;
                        }
                    }
                    case '"', '\\', '/' -> out.append(escaped);
                    default -> out.append(escaped);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final class Parser {
        private final String source;
        private int index;
        private int depth;

        Parser(final String source) {
            this.source = source;
        }

        Object value() {
            if (++depth > 128) throw error("maximum nesting depth exceeded");
            try {
                whitespace();
                if (done()) throw error("expected value");
                return switch (source.charAt(index)) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            } finally {
                depth--;
            }
        }

        private Map<String, Object> object() {
            final var object = new LinkedHashMap<String, Object>();
            index++;
            whitespace();
            if (consume('}')) {
                return object;
            }
            while (true) {
                whitespace();
                if (!consume('"')) {
                    throw error("expected object key");
                }
                index--;
                final var key = string();
                whitespace();
                expect(':');
                final var value = value();
                if (object.containsKey(key)) {
                    throw error("duplicate object key: " + key);
                }
                object.put(key, value);
                whitespace();
                if (consume('}')) {
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            final var values = new ArrayList<>();
            index++;
            whitespace();
            if (consume(']')) {
                return values;
            }
            while (true) {
                values.add(value());
                whitespace();
                if (consume(']')) {
                    return values;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            final var out = new StringBuilder();
            while (!done()) {
                final var c = source.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("unescaped control character");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (done()) {
                    throw error("unfinished escape");
                }
                switch (source.charAt(index++)) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicode());
                    default -> throw error("invalid escape");
                }
            }
            throw error("unterminated string");
        }

        private char unicode() {
            if (index + 4 > source.length()) {
                throw error("unfinished unicode escape");
            }
            final var digits = source.substring(index, index + 4);
            if (!digits.chars()
                    .allMatch(
                            c ->
                                    c >= '0' && c <= '9'
                                            || c >= 'a' && c <= 'f'
                                            || c >= 'A' && c <= 'F')) {
                throw error("invalid unicode escape");
            }
            try {
                final var value = (char) Integer.parseInt(digits, 16);
                index += 4;
                return value;
            } catch (final NumberFormatException exception) {
                throw error("invalid unicode escape");
            }
        }

        private Object literal(final String literal, final Object value) {
            if (!source.startsWith(literal, index)) {
                throw error("invalid literal");
            }
            index += literal.length();
            return value;
        }

        private Number number() {
            final var start = index;
            consume('-');
            if (consume('0')) {
                if (!done() && digit(source.charAt(index))) {
                    throw error("leading zero");
                }
            } else {
                digits();
            }
            if (consume('.')) {
                digits();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                digits();
            }
            final var text = source.substring(start, index);
            try {
                if (!text.contains(".") && !text.contains("e") && !text.contains("E")) {
                    try {
                        return Long.valueOf(text);
                    } catch (final NumberFormatException exception) {
                        return new BigDecimal(text);
                    }
                }
                return new BigDecimal(text);
            } catch (final NumberFormatException exception) {
                throw error("invalid number");
            }
        }

        private void digits() {
            final var start = index;
            while (!done() && digit(source.charAt(index))) {
                index++;
            }
            if (index == start) {
                throw error("expected digit");
            }
        }

        private static boolean digit(final char value) {
            return value >= '0' && value <= '9';
        }

        private void whitespace() {
            while (!done() && " \n\r\t".indexOf(source.charAt(index)) >= 0) {
                index++;
            }
        }

        private boolean consume(final char expected) {
            if (!done() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(final char expected) {
            if (!consume(expected)) {
                throw error("expected '" + expected + "'");
            }
        }

        private boolean done() {
            return index == source.length();
        }

        IllegalArgumentException error(final String message) {
            return new IllegalArgumentException(message + " at JSON offset " + index);
        }
    }
}
