package fxdriver;

import java.io.Closeable;
import java.io.IOException;

interface AnimationSink extends Closeable {
    void frame(int[] rect, int x, int y, int w, int h) throws IOException;

    void extendDelayMs(int ms) throws IOException;
}
