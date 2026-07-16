package fxdriver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class Json {
    private Json() {}

    static boolean hasKey(final String body, final String key) {
        return fieldColon(body, key) >= 0;
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
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
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
}
