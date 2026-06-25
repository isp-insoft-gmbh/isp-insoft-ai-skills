package fxdriver;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * Streaming APNG encoder for UI screencasts. Frames are written as full-color PNG chunks and only
 * the animation control frame count is patched on close, so normally finalized files are playable
 * by APNG-aware viewers.
 */
final class Apng implements AnimationSink {
    private static final byte[] SIGNATURE = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] IEND = new byte[0];

    private final RandomAccessFile out;
    private final long frameCountOffset;
    private final int width;
    private final int height;
    private int[] pending;
    private int pendingX;
    private int pendingY;
    private int pendingW;
    private int pendingH;
    private int pendingDelayMs;
    private int sequence;
    private int frames;
    private boolean closed;

    Apng(final Path path, final int width, final int height, final int[] firstFrame)
            throws IOException {
        this.width = width;
        this.height = height;
        out = new RandomAccessFile(path.toFile(), "rw");
        out.setLength(0);
        out.write(SIGNATURE);
        final var chunks = pngChunks(firstFrame, width, height);
        writeChunk("IHDR", chunks.ihdr());
        frameCountOffset = out.getFilePointer() + 8;
        writeChunk("acTL", ints(0, 0).array());
        pending = firstFrame.clone();
        pendingW = width;
        pendingH = height;
    }

    @Override
    public void frame(final int[] rect, final int x, final int y, final int w, final int h)
            throws IOException {
        writePending();
        pending = rect.clone();
        pendingX = x;
        pendingY = y;
        pendingW = w;
        pendingH = h;
    }

    @Override
    public void extendDelayMs(final int ms) throws IOException {
        pendingDelayMs = Math.max(1, pendingDelayMs + ms);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writePending();
            writeChunk("IEND", IEND);
            out.seek(frameCountOffset);
            out.writeInt(frames);
        } finally {
            out.close();
        }
    }

    private void writePending() throws IOException {
        if (pending == null) {
            return;
        }
        final var delay = Math.max(1, pendingDelayMs);
        final var png = pngChunks(pending, pendingW, pendingH);
        final var control = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN);
        control.putInt(sequence++);
        control.putInt(pendingW);
        control.putInt(pendingH);
        control.putInt(pendingX);
        control.putInt(pendingY);
        control.putShort((short) Math.min(65535, delay));
        control.putShort((short) 1000);
        control.put((byte) 0);
        control.put((byte) 0);
        writeChunk("fcTL", control.array());
        if (frames == 0) {
            for (final var idat : png.idats()) {
                writeChunk("IDAT", idat);
            }
        } else {
            for (final var idat : png.idats()) {
                final var data = ByteBuffer.allocate(4 + idat.length);
                data.order(ByteOrder.BIG_ENDIAN);
                data.putInt(sequence++);
                data.put(idat);
                writeChunk("fdAT", data.array());
            }
        }
        frames++;
        pending = null;
        pendingDelayMs = 0;
    }

    private static PngChunks pngChunks(final int[] argb, final int width, final int height)
            throws IOException {
        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        final var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        byte[] ihdr = null;
        final var idats = new ArrayList<byte[]>();
        final var png = bytes.toByteArray();
        var offset = SIGNATURE.length;
        while (offset + 12 <= png.length) {
            final var length = ByteBuffer.wrap(png, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            final var type = new String(png, offset + 4, 4, StandardCharsets.US_ASCII);
            final var data = Arrays.copyOfRange(png, offset + 8, offset + 8 + length);
            if ("IHDR".equals(type)) {
                ihdr = data;
            } else if ("IDAT".equals(type)) {
                idats.add(data);
            }
            offset += 12 + length;
        }
        if (ihdr == null || idats.isEmpty()) {
            throw new IOException("PNG encoder did not produce IHDR/IDAT chunks");
        }
        return new PngChunks(ihdr, idats);
    }

    private void writeChunk(final String type, final byte[] data) throws IOException {
        final var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(typeBytes);
        out.write(data);
        final var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }

    private static ByteBuffer ints(final int... values) {
        final var data = ByteBuffer.allocate(values.length * Integer.BYTES);
        data.order(ByteOrder.BIG_ENDIAN);
        for (final var value : values) {
            data.putInt(value);
        }
        return data;
    }

    private record PngChunks(byte[] ihdr, List<byte[]> idats) {}
}
