package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class FxDriverDataIT {
    private FxDriverTestHarness.Session session;

    @BeforeAll
    void start() throws Exception {
        session = FxDriverTestHarness.launch(FxDriverDataApp.class, "failsafe-data");
        FxDriverTestHarness.awaitVisible(session.endpoint(), "project-table");
    }

    @BeforeEach
    void reset() throws Exception {
        assertOk("fire", "{\"nodeId\":\"project-reset\"}");
        assertWait("stable");
    }

    @AfterAll
    void stop() throws Exception {
        session.close();
    }

    @Test
    void drivesProjectCollections() throws Exception {
        assertContains("listItems", "{\"nodeId\":\"recent-projects\"}", "Cygnus");
        assertOk(
                "selectListItem",
                "{\"nodeId\":\"recent-projects\",\"itemText\":\"Borealis\","
                        + "\"activate\":false}");
        assertOk("selectIndex", "{\"nodeId\":\"project-table\",\"index\":1}");
        assertOk("scrollToIndex", "{\"nodeId\":\"project-tree\",\"index\":1}");
        assertOk("collapse", "{\"nodeId\":\"project-details\"}");
        assertOk("expand", "{\"nodeId\":\"project-details\"}");
        assertOk("scrollToIndex", "{\"nodeId\":\"recent-projects\",\"index\":2}");
        assertOk("click", "{\"textExact\":\"Cygnus\"}");
        assertWait("Opened Cygnus");
    }

    @Test
    void reportsDuplicateAndMissDiagnostics() throws Exception {
        final var duplicate = call("click", "{\"textExact\":\"Open\"}");
        assertTrue(duplicate.contains("\"ok\":true"), duplicate);
        assertTrue(duplicate.contains("\"matches\":2"), duplicate);
        assertTrue(duplicate.contains("\"ambiguity\":"), duplicate);
        assertEquals(3, occurrences(duplicate, "\"selectorPath\""), duplicate);
        assertTrue(duplicate.contains("button#open-primary"), duplicate);

        final var miss = call("click", "{\"nodeId\":\"missing-project\"}");
        assertTrue(miss.contains("\"ok\":false"), miss);
        assertTrue(miss.contains("\"target\":null"), miss);
        assertTrue(miss.contains("\"matches\":0"), miss);

        final var delayed = call("fire", "{\"nodeId\":\"open-primary\",\"highlightMs\":25}");
        assertTrue(delayed.contains("\"target\":"), delayed);
    }

    @Test
    void reportsNearMatchesAndScreenshotOrientation() throws Exception {
        final var wait = call("wait", "{\"textExact\":\"Boreali\",\"timeoutMs\":100}");
        assertTrue(wait.contains("\"ok\":false"), wait);
        assertTrue(wait.contains("\"nearMatches\":"), wait);
        assertTrue(wait.contains("Borealis"), wait);
        assertTrue(wait.contains("\"selectorPath\":"), wait);
        assertTrue(occurrences(wait, "\"selectorPath\"") <= 5, wait);
        assertTrue(wait.length() < 12_000, wait);
        final var missingId = call("wait", "{\"nodeId\":\"project-tabl\",\"timeoutMs\":100}");
        assertTrue(missingId.contains("project-table"), missingId);
        assertTrue(occurrences(missingId, "\"selectorPath\"") <= 5, missingId);
        final var success = call("wait", "{\"textExact\":\"Borealis\",\"timeoutMs\":100}");
        assertFalse(success.contains("\"nearMatches\""), success);

        final var windowPath = session.artifacts().resolve("window.png").toAbsolutePath();
        final var screenshot = call("screenshot", "{\"path\":\"" + jsonPath(windowPath) + "\"}");
        assertTrue(Files.isRegularFile(windowPath));
        assertTrue(screenshot.contains("\"activeWindow\":"), screenshot);
        assertTrue(screenshot.contains("\"title\":\"Project Browser\""), screenshot);
        assertTrue(screenshot.contains("\"visibleTextSample\":"), screenshot);
        assertTrue(screenshot.contains("Borealis"), screenshot);
        assertTrue(screenshot.length() < 12_000, screenshot);

        final var nodePath = session.artifacts().resolve("table.png").toAbsolutePath();
        final var node =
                call(
                        "screenshot",
                        "{\"target\":\"node\",\"nodeId\":\"project-table\",\"path\":\""
                                + jsonPath(nodePath)
                                + "\"}");
        assertTrue(Files.isRegularFile(nodePath));
        assertTrue(node.contains("\"activeWindow\":"), node);
        assertTrue(node.contains("\"title\":\"Project Browser\""), node);
        assertTrue(node.contains("Borealis"), node);
        assertTrue(node.length() < 12_000, node);
    }

    @Test
    void inspectsTableAndTreeTableModels() throws Exception {
        final var byText =
                call(
                        "tableCell",
                        "{\"nodeId\":\"project-table\",\"tableText\":\"PRJ-102\","
                                + "\"column\":\"project-name\"}");
        assertTrue(byText.contains("\"rowIndex\":1"), byText);
        assertTrue(byText.contains("\"columnIndex\":1"), byText);
        assertTrue(byText.contains("\"value\":\"Borealis\""), byText);
        assertTrue(byText.contains("\"ok\":true"), byText);

        final var treeCell =
                call(
                        "tableCell",
                        "{\"nodeId\":\"project-tree-table\",\"rowIndex\":1,"
                                + "\"columnIndex\":0}");
        assertTrue(treeCell.contains("PRJ-102"), treeCell);
        assertTrue(treeCell.contains("\"ok\":true"), treeCell);

        assertContains(
                "tableCell",
                "{\"nodeId\":\"project-table\",\"rowIndex\":99,\"columnIndex\":0}",
                "NO_ROW");
        assertContains(
                "tableCell",
                "{\"nodeId\":\"project-table\",\"rowIndex\":0,\"column\":\"missing\"}",
                "NO_COLUMN");
        assertContains(
                "tableCell",
                "{\"nodeId\":\"project-filter\",\"rowIndex\":0,\"columnIndex\":0}",
                "NO_TABLE");
        assertContains("tableCell", "{\"nodeId\":\"project-table\",\"columnIndex\":0}", "NO_ROW");
        assertContains("tableCell", "{\"nodeId\":\"project-table\",\"rowIndex\":0}", "NO_COLUMN");
    }

    @Test
    void firesMenuBarButtonSplitAndContextItems() throws Exception {
        assertMenu(
                "{\"nodeId\":\"project-menu\",\"path\":[\"File\",\"Save Workspace\"]}",
                "Saved workspace");
        assertMenu("{\"nodeId\":\"project-actions\",\"itemText\":\"Delete\"}", "Deleted project");
        assertMenu(
                "{\"nodeId\":\"project-more-actions\",\"itemText\":\"Export\"}",
                "Exported project");
        assertMenu("{\"nodeId\":\"project-table\",\"itemText\":\"Archive\"}", "Archived project");

        final var missing =
                call(
                        "fireMenuItem",
                        "{\"nodeId\":\"project-menu\",\"path\":[\"File\",\"Missing\"]}");
        assertTrue(missing.contains("NO_MENU_ITEM"), missing);
        final var disabled =
                call(
                        "fireMenuItem",
                        "{\"nodeId\":\"project-actions\",\"itemText\":\"Delete permanently\"}");
        assertTrue(disabled.contains("DISABLED"), disabled);
        final var omitted = call("fireMenuItem", "{\"nodeId\":\"project-menu\"}");
        assertTrue(omitted.contains("NO_MENU_ITEM"), omitted);
        assertTrue(omitted.contains("\"fired\":0"), omitted);
    }

    @Test
    void summarizesProjectOrientation() throws Exception {
        assertOk("setText", "{\"nodeId\":\"project-filter\",\"value\":\"focused\"}");
        final var summary = call("snapshotSummary", "{}");
        assertTrue(summary.contains("\"windows\":"), summary);
        assertTrue(summary.contains("\"buttons\":"), summary);
        assertTrue(summary.contains("\"textFields\":"), summary);
        assertTrue(summary.contains("\"menus\":"), summary);
        assertTrue(summary.contains("File > Save Workspace"), summary);
        assertTrue(summary.contains("\"id\":\"project-actions\""), summary);
        assertTrue(summary.contains("\"items\":[\"Delete\",\"Delete permanently\"]"), summary);
        assertTrue(summary.contains("\"id\":\"project-more-actions\""), summary);
        assertTrue(summary.contains("\"items\":[\"Export\"]"), summary);
        assertTrue(summary.contains("\"id\":\"project-table\""), summary);
        assertTrue(summary.contains("\"items\":[\"Archive\"]"), summary);
        assertTrue(summary.contains("\"tables\":"), summary);
        assertTrue(summary.contains("\"selectedTabs\":"), summary);
        assertTrue(summary.contains("\"focusedNode\":"), summary);
        assertTrue(summary.contains("\"visibleTextSample\":"), summary);
        assertTrue(summary.contains("Project Browser"), summary);
        assertTrue(summary.contains("project-filter"), summary);
        assertTrue(summary.length() < 16_384, "summary bytes=" + summary.length());
    }

    @Test
    void boundsQuietWaiting() throws Exception {
        final var normal = call("click", "{\"nodeId\":\"open-primary\"}");
        assertFalse(normal.contains("\"quiet\""), normal);

        final var quiet =
                call(
                        "click",
                        "{\"nodeId\":\"open-primary\",\"untilQuietMs\":100,"
                                + "\"timeoutMs\":2000}");
        assertTrue(quiet.contains("\"quiet\":{\"ok\":true"), quiet);

        final var started = System.nanoTime();
        final var unstable =
                call(
                        "click",
                        "{\"nodeId\":\"project-refresh\",\"untilQuietMs\":200,"
                                + "\"timeoutMs\":450}");
        final var elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue(unstable.contains("\"QUIET_TIMEOUT\""), unstable);
        assertTrue(unstable.contains("\"quiet\":{\"ok\":false"), unstable);
        assertTrue(elapsedMs >= 400 && elapsedMs < 1_000, "elapsed=" + elapsedMs);
    }

    private void assertMenu(final String params, final String expectedStatus) throws Exception {
        final var response = call("fireMenuItem", params);
        assertTrue(response.contains("\"fired\":1"), response);
        assertTrue(response.contains("\"ok\":true"), response);
        assertWait(expectedStatus);
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

    private static String jsonPath(final java.nio.file.Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private static int occurrences(final String text, final String needle) {
        var count = 0;
        var at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }
}
