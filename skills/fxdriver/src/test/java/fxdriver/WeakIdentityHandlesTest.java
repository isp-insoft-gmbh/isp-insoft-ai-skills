package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.ref.WeakReference;
import org.junit.jupiter.api.Test;

final class WeakIdentityHandlesTest {
    @Test
    void assignsHandlesByIdentity() {
        final var handles = new WeakIdentityHandles<EqualValue>();
        final var first = new EqualValue();
        final var equalButDistinct = new EqualValue();

        assertEquals(1, handles.id(first));
        assertEquals(1, handles.id(first));
        assertEquals(2, handles.id(equalButDistinct));
        assertSame(first, handles.resolve(1));
        assertSame(equalButDistinct, handles.resolve(2));
        assertEquals(2, handles.size());
        assertNull(handles.resolve(3));
        assertThrows(NullPointerException.class, () -> handles.id(null));
    }

    @Test
    void removesCollectedReferents() {
        final var handles = new WeakIdentityHandles<Object>();
        final var registration = registerDisposable(handles);

        for (var attempt = 0; attempt < 100 && registration.reference().get() != null; attempt++) {
            System.gc();
            final var pressure = new byte[1024 * 1024];
            pressure[0] = 1;
        }
        assertNull(registration.reference().get());

        for (var attempt = 0; attempt < 100 && handles.size() != 0; attempt++) {
            System.gc();
        }
        assertNull(handles.resolve(registration.id()));
        assertEquals(0, handles.size());
        assertEquals(2, handles.id(new Object()));
    }

    private static Registration registerDisposable(final WeakIdentityHandles<Object> handles) {
        final var value = new Object();
        return new Registration(handles.id(value), new WeakReference<>(value));
    }

    private record Registration(int id, WeakReference<Object> reference) {}

    private static final class EqualValue {
        @Override
        public boolean equals(final Object object) {
            return object instanceof EqualValue;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
}
