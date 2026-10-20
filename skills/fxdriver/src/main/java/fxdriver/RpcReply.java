package fxdriver;

import java.util.LinkedHashMap;
import java.util.Map;

record RpcReply(
        String jsonrpc,
        Object id,
        Object result,
        Object error,
        boolean hasId,
        boolean hasResult,
        boolean hasError) {
    static RpcReply parse(final String response) {
        final var envelope = Json.object(response);
        return new RpcReply(
                envelope.get("jsonrpc") instanceof String version ? version : null,
                envelope.get("id"),
                envelope.get("result"),
                envelope.get("error"),
                envelope.containsKey("id"),
                envelope.containsKey("result"),
                envelope.containsKey("error"));
    }

    boolean failed() {
        return hasError || (result instanceof Map<?, ?> map && Boolean.FALSE.equals(map.get("ok")));
    }

    String completion() {
        if (result instanceof Map<?, ?> map && map.get("completion") instanceof String completion) {
            return completion;
        }
        return null;
    }

    Object actionId() {
        if (result instanceof Map<?, ?> map) {
            return map.get("actionId");
        }
        return null;
    }

    String toJson() {
        return Json.stringify(toObject());
    }

    Map<String, Object> toObject() {
        final var envelope = new LinkedHashMap<String, Object>();
        if (jsonrpc != null) {
            envelope.put("jsonrpc", jsonrpc);
        }
        if (hasId) {
            envelope.put("id", id);
        }
        if (hasResult) {
            envelope.put("result", result);
        }
        if (hasError) {
            envelope.put("error", error);
        }
        return envelope;
    }
}
