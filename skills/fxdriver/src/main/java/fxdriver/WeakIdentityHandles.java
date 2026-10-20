package fxdriver;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class WeakIdentityHandles<T> {
    private final ReferenceQueue<T> staleReferences = new ReferenceQueue<>();
    private final Map<HandleReference<T>, Integer> handles = new HashMap<>();
    private final Map<Integer, HandleReference<T>> references = new HashMap<>();
    private int nextId = 1;

    synchronized int id(final T value) {
        Objects.requireNonNull(value, "value");
        prune();
        final var existing = handles.get(new HandleReference<>(value));
        if (existing != null) {
            return existing;
        }
        final var id = nextId++;
        final var reference = new HandleReference<>(value, staleReferences);
        handles.put(reference, id);
        references.put(id, reference);
        return id;
    }

    synchronized T resolve(final int id) {
        prune();
        final var reference = references.get(id);
        return reference == null ? null : reference.get();
    }

    synchronized int size() {
        prune();
        return handles.size();
    }

    @SuppressWarnings("unchecked")
    synchronized void prune() {
        HandleReference<T> reference;
        while ((reference = (HandleReference<T>) staleReferences.poll()) != null) {
            final var id = handles.remove(reference);
            if (id != null) {
                references.remove(id);
            }
        }
    }

    private static final class HandleReference<T> extends WeakReference<T> {
        private final int identityHash;

        HandleReference(final T value) {
            super(value);
            identityHash = System.identityHashCode(value);
        }

        HandleReference(final T value, final ReferenceQueue<T> queue) {
            super(value, queue);
            identityHash = System.identityHashCode(value);
        }

        @Override
        public boolean equals(final Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof HandleReference<?> other)) {
                return false;
            }
            final var value = get();
            return value != null && value == other.get();
        }

        @Override
        public int hashCode() {
            return identityHash;
        }
    }
}
