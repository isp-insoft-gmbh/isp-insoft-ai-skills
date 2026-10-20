package fxdriver;

import java.util.LinkedHashMap;
import java.util.Map;
import javafx.scene.Node;
import javafx.scene.web.WebView;

/** Loaded only for WebView targets, keeping javafx.web optional in attached applications. */
final class FxWeb {
    private FxWeb() {}

    static Object execute(final Node node, final String script) {
        return ((WebView) node).getEngine().executeScript(script);
    }

    static Map<String, Object> state(final Node node) {
        final var web = (WebView) node;
        final var engine = web.getEngine();
        final var fields = new LinkedHashMap<String, Object>();
        putText(fields, "location", engine.getLocation());
        putText(fields, "title", engine.getTitle());
        fields.put("zoom", web.getZoom());
        fields.put("fontScale", web.getFontScale());
        fields.put("loadState", engine.getLoadWorker().getState().name());
        return fields;
    }

    private static void putText(
            final Map<String, Object> fields, final String key, final String value) {
        if (value == null) return;
        fields.put(key, value.length() > 512 ? value.substring(0, 512) : value);
        fields.put(key + "Truncated", value.length() > 512);
    }
}
