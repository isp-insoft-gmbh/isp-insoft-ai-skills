package fxdriver;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming animated GIF (GIF89a) encoder for UI screencasts. Frames are delta rects with
 * accumulated display durations; palettes are exact for up to 256 distinct colors and median-cut
 * quantized beyond that. Output is streamed as frames arrive, so a truncated file stays playable up
 * to the last flushed frame.
 */
final class Gif implements AnimationSink {
    private static final int CLEAR_CODE = 256;
    private static final int END_CODE = 257;

    private final OutputStream out;
    private final Map<Integer, Integer> globalIndex = new HashMap<>();
    // The pending frame is held back until its display duration is known (next frame or close).
    private byte[] pendingIndices;
    private byte[] pendingPalette;
    private int pendingX;
    private int pendingY;
    private int pendingW;
    private int pendingH;
    private int pendingDelayMs;
    private int delayCarryMs;
    private boolean closed;

    // LZW state, reused across frames. A slot is live only when its generation stamp is current,
    // which avoids clearing the table for every (often tiny) delta-rect frame.
    private final int[] hashKeys = new int[1 << 13];
    private final int[] hashGenerations = new int[1 << 13];
    private final int[] hashValues = new int[1 << 13];
    private int hashGeneration;
    private final byte[] block = new byte[255];
    private int blockLength;
    private int bitBuffer;
    private int bitCount;
    private int codeSize;
    private int maxCode;
    private int freeCode;
    private boolean clearPending;

    Gif(final Path path, final int width, final int height, final int[] firstFrame)
            throws IOException {
        out = new BufferedOutputStream(Files.newOutputStream(path));
        try {
            writeHeader(width, height, firstFrame);
        } catch (IOException | RuntimeException exception) {
            out.close();
            throw exception;
        }
    }

    private void writeHeader(final int width, final int height, final int[] firstFrame)
            throws IOException {
        out.write(new byte[] {'G', 'I', 'F', '8', '9', 'a'});
        u16(width);
        u16(height);
        out.write(0xF7); // global color table present, 256 entries, 8-bit color resolution
        out.write(0); // background color index
        out.write(0); // pixel aspect ratio
        out.write(palette(firstFrame, globalIndex));
        // NETSCAPE2.0 application extension: loop forever
        out.write(
                new byte[] {
                    0x21,
                    (byte) 0xFF,
                    0x0B,
                    'N',
                    'E',
                    'T',
                    'S',
                    'C',
                    'A',
                    'P',
                    'E',
                    '2',
                    '.',
                    '0',
                    3,
                    1,
                    0,
                    0,
                    0
                });
        pendingIndices = indices(firstFrame, globalIndex);
        pendingPalette = null;
        pendingX = 0;
        pendingY = 0;
        pendingW = width;
        pendingH = height;
    }

    /** Queues a changed rect; the previously queued frame is written with its accumulated delay. */
    @Override
    public void frame(final int[] rect, final int x, final int y, final int w, final int h)
            throws IOException {
        flushPending();
        pendingIndices = indices(rect, globalIndex);
        if (pendingIndices == null) {
            final var local = new HashMap<Integer, Integer>();
            pendingPalette = palette(rect, local);
            pendingIndices = indices(rect, local);
        } else {
            pendingPalette = null;
        }
        pendingX = x;
        pendingY = y;
        pendingW = w;
        pendingH = h;
        pendingDelayMs = 0;
    }

    /** Extends the display duration of the most recently queued frame. */
    @Override
    public void extendDelayMs(final int ms) throws IOException {
        pendingDelayMs += ms;
        // GIF frame delays cap at 655350 ms; burn the budget and re-arm with a 1x1 no-op rect.
        if (pendingIndices != null && pendingDelayMs >= 600_000) {
            final var index = pendingIndices[0];
            final var palette = pendingPalette;
            final var x = pendingX;
            final var y = pendingY;
            flushPending();
            pendingIndices = new byte[] {index};
            pendingPalette = palette;
            pendingX = x;
            pendingY = y;
            pendingW = 1;
            pendingH = 1;
            pendingDelayMs = 0;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flushPending();
            out.write(0x3B); // trailer
        } finally {
            out.close();
        }
    }

    private void flushPending() throws IOException {
        if (pendingIndices == null) {
            return;
        }
        final var totalMs = pendingDelayMs + delayCarryMs;
        final var delayCs = Math.min(0xFFFF, Math.max(1, totalMs / 10));
        // The carry may be negative (a frame forced to the 1 cs floor borrows from the next).
        delayCarryMs = totalMs - delayCs * 10;
        // graphic control extension: do-not-dispose keeps previous content outside the rect
        out.write(
                new byte[] {
                    0x21, (byte) 0xF9, 4, 0x04, (byte) (delayCs & 0xFF), (byte) (delayCs >> 8), 0, 0
                });
        out.write(0x2C); // image descriptor
        u16(pendingX);
        u16(pendingY);
        u16(pendingW);
        u16(pendingH);
        if (pendingPalette == null) {
            out.write(0);
        } else {
            out.write(0x87); // local color table, 256 entries
            out.write(pendingPalette);
        }
        lzw(pendingIndices);
        pendingIndices = null;
        pendingPalette = null;
    }

    private void u16(final int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /** A 768-byte palette for the pixels, filling the color-to-index mapping. */
    private static byte[] palette(final int[] pixels, final Map<Integer, Integer> index) {
        final var counts = new HashMap<Integer, int[]>();
        for (final var pixel : pixels) {
            counts.computeIfAbsent(pixel & 0xFFFFFF, __ -> new int[1])[0]++;
        }
        final var palette = new byte[768];
        if (counts.size() <= 256) {
            var next = 0;
            for (final var color : counts.keySet()) {
                writeColor(palette, next, color);
                index.put(color, next++);
            }
            return palette;
        }
        final var all = new ArrayList<int[]>(counts.size());
        counts.forEach((color, count) -> all.add(new int[] {color, count[0]}));
        final var boxes = new ArrayList<List<int[]>>();
        boxes.add(all);
        while (boxes.size() < 256) {
            final var box = widestBox(boxes);
            if (box == null) {
                break;
            }
            boxes.add(splitBox(box));
        }
        for (var i = 0; i < boxes.size(); i++) {
            writeColor(palette, i, averageColor(boxes.get(i)));
            for (final var entry : boxes.get(i)) {
                index.put(entry[0], i);
            }
        }
        return palette;
    }

    /** Maps pixels through the palette, or null when a color is missing from the index. */
    private static byte[] indices(final int[] pixels, final Map<Integer, Integer> index) {
        final var indices = new byte[pixels.length];
        for (var i = 0; i < pixels.length; i++) {
            final var mapped = index.get(pixels[i] & 0xFFFFFF);
            if (mapped == null) {
                return null;
            }
            indices[i] = mapped.byteValue();
        }
        return indices;
    }

    private static void writeColor(final byte[] palette, final int index, final int color) {
        palette[index * 3] = (byte) (color >> 16);
        palette[index * 3 + 1] = (byte) (color >> 8);
        palette[index * 3 + 2] = (byte) color;
    }

    private static List<int[]> widestBox(final List<List<int[]>> boxes) {
        List<int[]> widest = null;
        var widestRange = 0;
        for (final var box : boxes) {
            if (box.size() < 2) {
                continue;
            }
            final var range = channelRange(box)[1];
            if (range > widestRange) {
                widestRange = range;
                widest = box;
            }
        }
        return widest;
    }

    /** The widest channel of the box as {shift, range}, where shift is 16 (r), 8 (g) or 0 (b). */
    private static int[] channelRange(final List<int[]> box) {
        final var min = new int[] {255, 255, 255};
        final var max = new int[] {0, 0, 0};
        for (final var entry : box) {
            for (var channel = 0; channel < 3; channel++) {
                final var value = (entry[0] >> (16 - channel * 8)) & 0xFF;
                min[channel] = Math.min(min[channel], value);
                max[channel] = Math.max(max[channel], value);
            }
        }
        var shift = 16;
        var range = max[0] - min[0];
        if (max[1] - min[1] > range) {
            shift = 8;
            range = max[1] - min[1];
        }
        if (max[2] - min[2] > range) {
            shift = 0;
            range = max[2] - min[2];
        }
        return new int[] {shift, range};
    }

    /**
     * Splits the box at the count-weighted median of its widest channel; returns the upper half.
     */
    private static List<int[]> splitBox(final List<int[]> box) {
        final var shift = channelRange(box)[0];
        box.sort(Comparator.comparingInt(entry -> (entry[0] >> shift) & 0xFF));
        var total = 0L;
        for (final var entry : box) {
            total += entry[1];
        }
        var cut = box.size() - 1;
        var accumulated = 0L;
        for (var i = 0; i < box.size() - 1; i++) {
            accumulated += box.get(i)[1];
            if (accumulated * 2 >= total) {
                cut = i + 1;
                break;
            }
        }
        final var upper = new ArrayList<int[]>(box.subList(cut, box.size()));
        box.subList(cut, box.size()).clear();
        return upper;
    }

    private static int averageColor(final List<int[]> box) {
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (final var entry : box) {
            final long count = entry[1];
            r += ((entry[0] >> 16) & 0xFF) * count;
            g += ((entry[0] >> 8) & 0xFF) * count;
            b += (entry[0] & 0xFF) * count;
            n += count;
        }
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private void lzw(final byte[] pixels) throws IOException {
        out.write(8); // minimum LZW code size
        hashGeneration++;
        blockLength = 0;
        bitBuffer = 0;
        bitCount = 0;
        codeSize = 9;
        maxCode = (1 << codeSize) - 1;
        freeCode = END_CODE + 1;
        clearPending = false;
        emit(CLEAR_CODE);
        var prefix = pixels[0] & 0xFF;
        for (var i = 1; i < pixels.length; i++) {
            final var pixel = pixels[i] & 0xFF;
            final var key = (prefix << 8) | pixel;
            final var slot = slotFor(key);
            if (hashGenerations[slot] == hashGeneration) {
                prefix = hashValues[slot];
                continue;
            }
            emit(prefix);
            prefix = pixel;
            if (freeCode < 4096) {
                hashKeys[slot] = key;
                hashGenerations[slot] = hashGeneration;
                hashValues[slot] = freeCode++;
            } else {
                hashGeneration++;
                freeCode = END_CODE + 1;
                clearPending = true;
                emit(CLEAR_CODE);
            }
        }
        emit(prefix);
        emit(END_CODE);
        while (bitCount > 0) {
            block[blockLength++] = (byte) bitBuffer;
            bitBuffer >>>= 8;
            bitCount -= 8;
            if (blockLength == 255) {
                flushBlock();
            }
        }
        flushBlock();
        out.write(0); // block terminator
    }

    private void emit(final int code) throws IOException {
        bitBuffer |= code << bitCount;
        bitCount += codeSize;
        while (bitCount >= 8) {
            block[blockLength++] = (byte) bitBuffer;
            bitBuffer >>>= 8;
            bitCount -= 8;
            if (blockLength == 255) {
                flushBlock();
            }
        }
        // Mirror of the decoder's growth rule: widen after the code that fills the current size.
        if (freeCode > maxCode || clearPending) {
            if (clearPending) {
                codeSize = 9;
                maxCode = (1 << codeSize) - 1;
                clearPending = false;
            } else {
                codeSize++;
                maxCode = codeSize == 12 ? 4096 : (1 << codeSize) - 1;
            }
        }
    }

    private int slotFor(final int key) {
        var slot = (key * 0x9E3779B1) >>> 19;
        while (hashGenerations[slot] == hashGeneration && hashKeys[slot] != key) {
            slot = (slot + 1) & (hashKeys.length - 1);
        }
        return slot;
    }

    private void flushBlock() throws IOException {
        if (blockLength > 0) {
            out.write(blockLength);
            out.write(block, 0, blockLength);
            blockLength = 0;
        }
    }
}
