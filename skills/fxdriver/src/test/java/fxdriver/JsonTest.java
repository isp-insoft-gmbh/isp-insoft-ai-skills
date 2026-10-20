package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JsonTest {
    @Test
    void parsesAndStringifiesNestedEscapedJsonInInsertionOrder() {
        final var value =
                Json.object(
                        "{\"first\":null,\"escaped\":\"\\\"\\\\\\/\\b\\f\\n"
                                + "\\r"
                                + "\\t\\u00e4\",\"nested\":[true,false,12.5,{\"last\":2}]}");

        assertEquals(List.of("first", "escaped", "nested"), new ArrayList<>(value.keySet()));
        assertNull(value.get("first"));
        assertEquals("\"\\/\b\f\n\r\tä", value.get("escaped"));
        final var nested = assertInstanceOf(List.class, value.get("nested"));
        assertEquals(new BigDecimal("12.5"), nested.get(2));
        assertEquals(
                "{\"first\":null,\"escaped\":\"\\\"\\\\/\\b\\f\\n"
                        + "\\r"
                        + "\\tä\",\"nested\":[true,false,12.5,{\"last\":2}]}",
                Json.stringify(value));
    }

    @Test
    void rejectsMalformedTrailingAndDuplicateInput() {
        for (final var json :
                List.of(
                        "{",
                        "[1,]",
                        "true false",
                        "{\"key\":1,\"key\":2}",
                        "\"\\x\"",
                        "\"\\u+123\"",
                        "\"\\u-001\"")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(json), json);
        }
        assertThrows(IllegalArgumentException.class, () -> Json.object("[]"));
    }

    @Test
    void escapesControlsAndUnpairedSurrogatesWithoutCorruptingPairs() {
        final var value =
                "\"\\\b\f\n\r\t"
                        + (char) 1
                        + (char) 0xd800
                        + "x"
                        + (char) 0xdc00
                        + new String(Character.toChars(0x1f680));
        final var encoded = Json.stringify(value);
        assertTrue(encoded.contains("\\ud800"));
        assertTrue(encoded.contains("\\udc00"));
        assertEquals(value, Json.parse(encoded));
        assertEquals(value, Json.parse("\"" + Json.escape(value) + "\""));
    }

    @Test
    void rejectsExcessiveNestingBeforeStackOverflow() {
        assertInstanceOf(List.class, Json.parse("[".repeat(64) + "0" + "]".repeat(64)));
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.parse("[".repeat(256) + "0" + "]".repeat(256)));
    }

    @Test
    void replyDistinguishesTextAndNestedErrorDataFromFailures() {
        final var successful =
                RpcReply.parse(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"request-1\",\"result\":{\"ok\":true,\"text\":\"error\",\"value\":\"error\",\"data\":{\"error\":{\"message\":\"not"
                            + " a failure\"}}}}");

        assertFalse(successful.failed());
        assertEquals("request-1", successful.id());
        assertInstanceOf(Map.class, successful.result());
        assertNull(successful.error());
    }

    @Test
    void replyTracksPendingAndFailedStatesWithoutChangingIdType() {
        final var pending =
                RpcReply.parse(
                        "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true,\"completion\":\"pending\",\"actionId\":4}}");
        final var resultFailure =
                RpcReply.parse(
                        "{\"jsonrpc\":\"2.0\",\"id\":8,\"result\":{\"ok\":false,\"completion\":\"completed\"}}");
        final var envelopeFailure =
                RpcReply.parse(
                        "{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-1,\"message\":\"bad"
                                + " \\\\ input\"}}");

        assertFalse(pending.failed());
        assertEquals("pending", pending.completion());
        assertEquals(4L, pending.actionId());
        assertEquals(7L, pending.id());
        assertEquals(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true,\"completion\":\"pending\",\"actionId\":4}}",
                pending.toJson());
        assertTrue(resultFailure.failed());
        assertTrue(envelopeFailure.failed());
    }
}
