package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FxDriverFormsIT {
    private FxDriverTestHarness.Session session;
    @TempDir private Path images;

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
    void newerSiblingModalRemainsUsableWhileBackgroundStaysBlocked() throws Exception {
        final var outer = call("fire", "{\"nodeId\":\"sibling-dialogs\"}");
        assertEquals("pending", Json.string(outer, "completion"), outer);
        final var inner = call("fire", "{\"nodeId\":\"open-sibling\"}");
        assertEquals("pending", Json.string(inner, "completion"), inner);
        assertContains("fire", "{\"nodeId\":\"outer-close\"}", "BLOCKED");
        assertContains("setText", "{\"nodeId\":\"profile-name\",\"value\":\"Unsafe\"}", "BLOCKED");
        assertContains("key", "{\"nodeId\":\"profile-name\",\"key\":\"ENTER\"}", "BLOCKED");
        assertOk("fire", "{\"nodeId\":\"sibling-ok\"}");
        assertEquals(
                "completed",
                Json.string(
                        call(
                                "actionStatus",
                                "{\"actionId\":" + Json.intValue(inner, "actionId", -1) + "}"),
                        "completion"));
        assertContains("fire", "{\"nodeId\":\"settings-save\"}", "BLOCKED");
        assertOk("fire", "{\"nodeId\":\"outer-close\"}");
        assertEquals(
                "completed",
                Json.string(
                        call(
                                "actionStatus",
                                "{\"actionId\":" + Json.intValue(outer, "actionId", -1) + "}"),
                        "completion"));
        assertOk("assert", "{\"nodeId\":\"profile-name\",\"value\":\"Grace Hopper\"}");
        assertOk("fire", "{\"nodeId\":\"settings-save\"}");
        assertWait("Saved settings for Grace Hopper");
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
        assertTrue(snapshot.contains("[redacted]"));
        assertFalse(snapshot.contains("\"value\":\"secret\""));
        final var passwordReply =
                call("query", "{\"nodeId\":\"profile-password\",\"mode\":\"full\"}");
        final var nodes =
                (List<?>) ((Map<?, ?>) Json.object(passwordReply).get("result")).get("nodes");
        final var passwordState = (Map<?, ?>) ((Map<?, ?>) nodes.getFirst()).get("semantic");
        assertEquals("[redacted]", passwordState.get("value"));
        for (final var field :
                List.of(
                        "length",
                        "selectionStart",
                        "selectionEnd",
                        "selectedText",
                        "caretPosition",
                        "anchor")) {
            assertFalse(passwordState.containsKey(field), field);
        }

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

    @Test
    void opensEditsAndClosesBlockingDialogWithinOneRun() throws Exception {
        final var response =
                call(
                        "run",
                        """
                        {"steps":[
                          {"op":"fire","nodeId":"settings-dialog","highlightMs":20},
                          {"op":"assert","role":"DIALOG","count":1},
                          {"op":"setText","role":"TEXT_FIELD","accessible":"Dialog name","value":"Edited"},
                          {"op":"fire","nodeId":"dialog-save"},
                          {"op":"wait","textExact":"Dialog saved: Edited","timeoutMs":1000}
                        ],"returnState":"compact"}
                        """);
        assertTrue(Json.booleanValue(response, "ok", false), response);
        assertEquals("completed", Json.string(response, "completion"), response);
        assertEquals(5, Json.intValue(response, "executed", -1), response);
        assertTrue(response.contains("\"pendingActions\":[]"), response);
        assertFalse(response.contains("TimeoutException"), response);
        assertContains("assert", "{\"role\":\"DIALOG\",\"present\":false}", "\"ok\":true");
    }

    @Test
    void exposesPendingStatusAndHonorsDialogWindowScope() throws Exception {
        final var opened = call("fire", "{\"nodeId\":\"settings-dialog\"}");
        try {
            assertEquals("pending", Json.string(opened, "completion"), opened);
            assertTrue(opened.contains("Edit profile dialog"), opened);
            final var actionId = Json.intValue(opened, "actionId", -1);
            final var dialog =
                    call("query", "{\"role\":\"TEXT_FIELD\",\"accessible\":\"Dialog name\"}");
            final var window = Json.string(dialog, "window");
            assertFalse(window.isBlank(), dialog);
            assertContains("assert", "{\"nodeId\":\"profile-name\",\"count\":2}", "\"ok\":true");
            final var duplicateId = call("query", "{\"nodeId\":\"profile-name\"}");
            assertEquals(
                    2, duplicateId.split("\"id\":\"profile-name\"", -1).length - 1, duplicateId);
            assertContains(
                    "setText", "{\"nodeId\":\"profile-name\",\"value\":\"Unsafe\"}", "AMBIGUOUS");
            assertContains("fire", "{\"role\":\"BUTTON\",\"textExact\":\"Save\"}", "AMBIGUOUS");
            assertOk(
                    "setText",
                    "{\"nodeId\":\"profile-name\",\"window\":\""
                            + window
                            + "\",\"value\":\"Scoped\"}");
            final var path = images.resolve("dialog.png");
            final var screenshot =
                    call(
                            "screenshot",
                            "{\"window\":\""
                                    + window
                                    + "\",\"path\":\""
                                    + Json.escape(path.toString())
                                    + "\"}");
            assertTrue(screenshot.contains("Edit profile dialog"), screenshot);
            assertTrue(Files.size(path) > 0);
            assertOk(
                    "fire",
                    "{\"role\":\"BUTTON\",\"textExact\":\"Save\",\"window\":\"" + window + "\"}");
            assertWait("Dialog saved: Scoped");
            final var status = call("actionStatus", "{\"actionId\":" + actionId + "}");
            assertEquals("completed", Json.string(status, "completion"), status);
        } finally {
            call("key", "{\"role\":\"DIALOG\",\"key\":\"ESCAPE\"}");
        }
    }

    @Test
    void clickAndKeyboardAlsoYieldFromBlockingDialogs() throws Exception {
        for (final var method : List.of("click", "key")) {
            final var opened = call(method, "{\"nodeId\":\"settings-dialog\",\"key\":\"SPACE\"}");
            try {
                assertEquals("pending", Json.string(opened, "completion"), opened);
                assertOk("key", "{\"role\":\"DIALOG\",\"key\":\"ESCAPE\"}");
                assertWait("Dialog cancelled");
                final var status =
                        call(
                                "actionStatus",
                                "{\"actionId\":" + Json.intValue(opened, "actionId", -1) + "}");
                assertEquals("completed", Json.string(status, "completion"), status);
            } finally {
                call("key", "{\"role\":\"DIALOG\",\"key\":\"ESCAPE\"}");
            }
        }
    }

    @Test
    void lateDialogFailureStopsRunBeforeFollowingMutation() throws Exception {
        final var response =
                call(
                        "run",
                        """
                        {"steps":[
                          {"op":"fire","nodeId":"settings-dialog-fail"},
                          {"op":"fire","nodeId":"dialog-save"},
                          {"op":"setText","nodeId":"profile-name","value":"Must not execute"}
                        ]}
                        """);
        assertFalse(Json.booleanValue(response, "ok", true), response);
        assertEquals(1, Json.intValue(response, "failedStep", -1), response);
        assertEquals(2, Json.intValue(response, "executed", -1), response);
        assertTrue(response.contains("Fixture late failure"), response);
        final var name = call("query", "{\"nodeId\":\"profile-name\"}");
        assertTrue(name.contains("Grace Hopper"), name);
        assertFalse(name.contains("Must not execute"), name);
    }

    @Test
    void rejectsAmbiguousDisabledAndReadOnlyMutationsWithoutChangingState() throws Exception {
        assertContains(
                "assert",
                "{\"role\":\"BUTTON\",\"textExact\":\"Name\",\"count\":0}",
                "\"ok\":true");
        final var duplicate = call("query", "{\"nodeId\":\"duplicate-action\"}");
        assertEquals(2, duplicate.split("\"id\":\"duplicate-action\"", -1).length - 1, duplicate);
        assertContains("fire", "{\"nodeId\":\"duplicate-action\"}", "AMBIGUOUS");
        for (final var method : List.of("fire", "click", "key")) {
            assertContains(
                    method, "{\"nodeId\":\"settings-export\",\"key\":\"SPACE\"}", "DISABLED");
        }
        assertContains(
                "setText", "{\"nodeId\":\"disabled-input\",\"value\":\"Unsafe\"}", "DISABLED");
        assertContains(
                "setText", "{\"nodeId\":\"profile-readonly\",\"value\":\"Unsafe\"}", "READ_ONLY");
        assertTrue(call("query", "{\"nodeId\":\"disabled-input\"}").contains("Disabled value"));
        assertTrue(call("query", "{\"nodeId\":\"profile-readonly\"}").contains("Read-only value"));
    }

    @Test
    void excludesHiddenAncestorsAndExposesNamedCurrentControlState() throws Exception {
        final var summary = call("snapshotSummary", "{}");
        assertFalse(summary.contains("Hidden ancestor noise"), summary);
        assertFalse(summary.contains("hidden-input-"), summary);
        final var hidden = call("query", "{\"nodeId\":\"hidden-input-0\"}");
        assertTrue(hidden.contains("\"nodes\":[]"), hidden);
        assertContains(
                "assert",
                "{\"nodeId\":\"hidden-input-0\",\"visible\":false,\"count\":1}",
                "\"ok\":true");
        assertContains(
                "setText", "{\"nodeId\":\"hidden-input-0\",\"value\":\"Unsafe\"}", "NO_MATCH");
        final var named = call("query", "{\"role\":\"TEXT_FIELD\",\"accessible\":\"Name\"}");
        assertTrue(named.contains("\"name\":\"Name\""), named);
        final var check = call("query", "{\"nodeId\":\"notifications-enabled\"}");
        assertTrue(check.contains("\"selected\":true"), check);
        assertOk("fire", "{\"nodeId\":\"notifications-enabled\"}");
        final var unchecked = call("query", "{\"nodeId\":\"notifications-enabled\"}");
        assertTrue(unchecked.contains("\"selected\":false"), unchecked);
        assertTrue(call("query", "{\"nodeId\":\"theme-choice\"}").contains("\"value\":\"System\""));
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
