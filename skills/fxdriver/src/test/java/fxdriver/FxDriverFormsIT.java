package fxdriver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FxDriverFormsIT {
    private FxDriverTestHarness.Session session;

    @BeforeAll
    void start() throws Exception {
        session = FxDriverTestHarness.launch(FxDriverFormsApp.class, "failsafe-forms");
        FxDriverTestHarness.awaitVisible(session.endpoint(), "profile-name");
    }

    @BeforeEach
    void reset() throws Exception {
        assertOk("fire", "{\"nodeId\":\"settings-reset\"}");
        assertOk("setText", "{\"nodeId\":\"profile-name\",\"value\":\"Grace Hopper\"}");
    }

    @AfterAll
    void stop() throws Exception {
        session.close();
    }

    @Test
    void editsAndSavesSettings() throws Exception {
        assertOk("setText", "{\"nodeId\":\"profile-name\",\"value\":\"Grace\"}");
        assertOk("type", "{\"nodeId\":\"profile-name\",\"value\":\" Hopper\",\"replace\":false}");
        assertOk("clear", "{\"nodeId\":\"profile-notes\"}");
        assertOk("setValue", "{\"nodeId\":\"theme-choice\",\"value\":\"Dark\"}");
        assertOk("setValue", "{\"nodeId\":\"review-date\",\"value\":\"2026-07-16\"}");
        assertOk("setValue", "{\"nodeId\":\"zoom-level\",\"value\":125}");
        assertOk("increment", "{\"nodeId\":\"retention-days\",\"steps\":2}");
        assertOk("showPopup", "{\"nodeId\":\"density-combo\"}");
        assertOk("hidePopup", "{\"nodeId\":\"density-combo\"}");
        assertOk("fire", "{\"nodeId\":\"settings-save\"}");
        assertWait("Saved settings for Grace Hopper");
    }

    @Test
    void exposesValidationAndControlState() throws Exception {
        assertOk("fire", "{\"nodeId\":\"expert-mode\"}");
        assertOk("fire", "{\"nodeId\":\"release-channel-beta\"}");
        assertContains(
                "assert", "{\"nodeId\":\"settings-export\",\"enabled\":false}", "\"ok\":true");
        assertOk("clear", "{\"nodeId\":\"profile-name\"}");
        assertWait("Name is required");
        assertContains("assert", "{\"nodeId\":\"settings-save\",\"enabled\":false}", "\"ok\":true");
    }

    @Test
    void dispatchesNamedChordAndLiteralKeys() throws Exception {
        assertOk("key", "{\"nodeId\":\"profile-name\",\"key\":\"ENTER\"}");
        assertWait("Submitted Grace Hopper");
        assertOk("key", "{\"nodeId\":\"profile-name\",\"key\":\"CTRL+S\"}");
        assertWait("Keyboard save");
        final var chars = call("key", "{\"nodeId\":\"profile-name\",\"chars\":\"xy\"}");
        assertTrue(chars.contains("\"sent\":2"), chars);
        assertTrue(chars.contains("\"target\":"), chars);
        final var space = call("key", "{\"nodeId\":\"profile-name\",\"chars\":\" \"}");
        assertTrue(space.contains("\"sent\":1"), space);
        final var newline = call("key", "{\"nodeId\":\"profile-name\",\"chars\":\"\\n\"}");
        assertTrue(newline.contains("\"sent\":1"), newline);
        assertOk("key", "{\"key\":\"ESC\"}");
        assertWait("Cancelled keyboard input");
        final var missing = call("key", "{\"nodeId\":\"missing\",\"key\":\"ENTER\"}");
        assertTrue(missing.contains("\"ok\":false"), missing);
        assertTrue(missing.contains("NO_FOCUS"), missing);
    }

    @Test
    void preservesRedactionAndStateDiagnostics() throws Exception {
        final var summary = call("snapshotSummary", "{}");
        assertTrue(summary.contains("\"controls\":"), summary);
        assertTrue(summary.contains("\"id\":\"theme-choice\""), summary);
        assertTrue(summary.contains("\"value\":\"System\""), summary);
        assertTrue(summary.contains("\"id\":\"retention-days\""), summary);
        assertTrue(summary.contains("\"value\":\"30\""), summary);
        assertTrue(summary.contains("\"id\":\"notifications-enabled\""), summary);
        assertTrue(summary.contains("\"selected\":true"), summary);

        final var snapshot = call("snapshot", "{}");
        assertTrue(snapshot.contains("\"id\":\"profile-password\""), snapshot);
        assertTrue(snapshot.contains("••••"), snapshot);
        assertFalse(snapshot.contains("\"value\":\"secret\""), snapshot);

        final var run =
                call(
                        "run",
                        "{\"steps\":[{\"op\":\"setText\",\"nodeId\":\"profile-name\","
                                + "\"value\":\"Run value\"},{\"op\":\"wait\","
                                + "\"textExact\":\"Run value\"}],\"returnState\":\"compact\"}");
        assertTrue(run.contains("\"ok\":true"), run);
        assertTrue(run.contains("\"target\":"), run);
        assertTrue(run.contains("\"state\":"), run);
        assertTrue(run.contains("profile-name"), run);
    }

    private String call(final String method, final String params) throws Exception {
        return FxDriverTestHarness.rpc(session.endpoint(), method, params);
    }

    private void assertOk(final String method, final String params) throws Exception {
        final var response = call(method, params);
        assertTrue(response.contains("\"ok\":true"), response);
    }

    private void assertContains(final String method, final String params, final String expected)
            throws Exception {
        final var response = call(method, params);
        assertTrue(response.contains(expected), response);
    }

    private void assertWait(final String text) throws Exception {
        assertContains(
                "wait", "{\"textExact\":\"" + text + "\",\"timeoutMs\":1000}", "\"ok\":true");
    }
}
