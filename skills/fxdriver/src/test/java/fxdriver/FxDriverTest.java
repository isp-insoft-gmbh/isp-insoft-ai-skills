package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FxDriverTest {
    @TempDir Path dir;

    @Test
    void jsonEscapeEscapesControlCharacters() {
        assertEquals("a\\\\b\\\"c\\nd\\r", FxDriver.jsonEscape("a\\b\"c\nd\r"));
    }

    @Test
    void jsonFieldReadersExtractEndpointFields() throws Exception {
        final var json = "{\"host\":\"127.0.0.1\",\"port\":12345,\"token\":\"abc\"}";

        assertEquals(12345, FxDriver.jsonInt(json, "port"));
        assertEquals("abc", FxDriver.jsonString(json, "token"));
    }

    @Test
    void jsonHelpersReadAgentShapes() {
        final var json =
                "{\"id\":7,\"ok\":true,\"name\":\"a\\nb\",\"types\":[\"Button\",\"TextField\"]}";

        assertEquals("7", Json.id(json));
        assertTrue(Json.hasKey(json, "ok"));
        assertTrue(Json.booleanValue(json, "ok", false));
        assertEquals("a\nb", Json.string(json, "name"));
        assertEquals(java.util.List.of("Button", "TextField"), Json.stringArray(json, "types"));
        assertEquals("\"a\",\"b\"", Json.strings(java.util.List.of("a", "b")));
        assertFalse(Json.hasKey("{\"message\":\"port\"}", "port"));
        assertEquals("ä", Json.string("{\"name\":\"\\u00e4\"}", "name"));
    }

    @Test
    void jsonFieldReadersFailLoudlyOnMissingFields() {
        final var json = "{}";

        assertThrows(Exception.class, () -> FxDriver.jsonInt(json, "port"));
        assertThrows(Exception.class, () -> FxDriver.jsonString(json, "token"));
    }

    @Test
    void imageSummaryReportsStableBasics() throws Exception {
        final var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        image.setRGB(1, 0, 0xff00ff00);
        image.setRGB(0, 1, 0xff0000ff);
        image.setRGB(1, 1, 0xffffffff);
        final var path = dir.resolve("summary.png");
        ImageIO.write(image, "png", path.toFile());

        final var summary = FxDriver.imageSummaryJson(path);

        assertTrue(summary.contains("\"width\":2"));
        assertTrue(summary.contains("\"height\":2"));
        assertTrue(summary.contains("\"avgColor\":{\"r\":127,\"g\":127,\"b\":127,\"a\":255}"));
        assertTrue(summary.contains("\"ahash\":"));
    }

    @Test
    void imageDiffReportsNoChangeForSameImage() throws Exception {
        final var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff000000);
        image.setRGB(1, 0, 0xff000000);
        image.setRGB(0, 1, 0xffffffff);
        image.setRGB(1, 1, 0xffffffff);
        final var path = dir.resolve("same.png");
        final var diff = dir.resolve("diff.png");
        ImageIO.write(image, "png", path.toFile());

        final var result = FxDriver.imageDiffJson(path, path, diff);

        assertTrue(result.contains("\"changedPixels\":0"));
        assertTrue(result.contains("\"changedPercent\":0.0000"));
        assertTrue(result.contains("\"bounds\":null"));
        assertTrue(result.contains("\"sizeMismatch\":false"));
        assertTrue(diff.toFile().isFile());
    }

    @Test
    void imageDiffReportsChangedBounds() throws Exception {
        final var before = new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
        final var after = new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
        before.setRGB(1, 1, 0xff000000);
        after.setRGB(1, 1, 0xffffffff);
        final var beforePath = dir.resolve("before.png");
        final var afterPath = dir.resolve("after.png");
        ImageIO.write(before, "png", beforePath.toFile());
        ImageIO.write(after, "png", afterPath.toFile());

        final var result = FxDriver.imageDiffJson(beforePath, afterPath, null);

        assertTrue(result.contains("\"changedPixels\":1"));
        assertTrue(result.contains("\"bounds\":{\"x\":1,\"y\":1,\"w\":1,\"h\":1}"));
        assertFalse(result.contains("\"diff\":\""));
    }
}
