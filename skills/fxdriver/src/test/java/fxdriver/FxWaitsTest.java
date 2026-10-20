package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Group;
import javafx.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class FxWaitsTest {
    @BeforeAll
    static void startToolkit() throws InterruptedException {
        final var started = new CountDownLatch(1);
        try {
            Platform.startup(
                    () -> {
                        Platform.setImplicitExit(false);
                        started.countDown();
                    });
            assertTrue(started.await(10, TimeUnit.SECONDS));
        } catch (final IllegalStateException alreadyStarted) {
            Platform.setImplicitExit(false);
        }
    }

    @Test
    void returnsImmediateObservation() throws Exception {
        final var result = FxWaits.until(() -> "ready", "ready"::equals, List::of, 1_000);

        assertEquals("ready", result.value());
        assertTrue(result.satisfied());
        assertTrue(result.observed());
        assertEquals(1, result.polls());
    }

    @Test
    void propertyChangeWakesWait() throws Exception {
        final var value = new SimpleBooleanProperty(false);
        final var started = new CountDownLatch(1);
        final var result =
                callAsync(
                        () ->
                                FxWaits.until(
                                        value::get,
                                        Boolean.TRUE::equals,
                                        () -> {
                                            started.countDown();
                                            return List.of(value);
                                        },
                                        1_000));

        assertTrue(started.await(1, TimeUnit.SECONDS));
        onFxThread(() -> value.set(true));

        assertTrue(result.get(1, TimeUnit.SECONDS).satisfied());
    }

    @Test
    void fallbackFindsReplacementSignal() throws Exception {
        final var first = new SimpleBooleanProperty(false);
        final var replacement = new SimpleBooleanProperty(false);
        final var target = new AtomicReference<>(first);
        final var result =
                callAsync(
                        () ->
                                FxWaits.until(
                                        () -> target.get().get(),
                                        Boolean.TRUE::equals,
                                        () -> List.of(target.get()),
                                        1_000));

        Thread.sleep(30);
        onFxThread(
                () -> {
                    target.set(replacement);
                    replacement.set(true);
                });

        assertTrue(result.get(1, TimeUnit.SECONDS).satisfied());
    }

    @Test
    void timeoutUnsubscribesSignals() throws Exception {
        final var signal = new TestObservable();
        final var result =
                FxWaits.until(() -> false, Boolean.TRUE::equals, () -> List.of(signal), 100);

        assertFalse(result.satisfied());
        assertTrue(result.observed());
        onFxThread(() -> {});
        assertEquals(0, signal.listenerCount());
        signal.fire();
        assertEquals(0, signal.listenerCount());
    }

    @Test
    void propagatesProbeFailure() {
        final var failure = new IllegalStateException("broken probe");

        final var thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                FxWaits.until(
                                        () -> {
                                            throw failure;
                                        },
                                        ignored -> false,
                                        List::of,
                                        1_000));

        assertSame(failure, thrown);
    }

    @Test
    void observesNextLayoutPulse() throws Exception {
        final var scene = new AtomicReference<Scene>();
        final var stage = new AtomicReference<javafx.stage.Stage>();
        onFxThread(
                () -> {
                    final var window = new javafx.stage.Stage();
                    scene.set(new Scene(new Group(), 200, 100));
                    window.setScene(scene.get());
                    window.setX(40);
                    window.setY(40);
                    stage.set(window);
                    window.show();
                });
        try {
            assertTrue(FxWaits.nextLayout(scene.get(), 1_000));
        } finally {
            onFxThread(() -> stage.get().close());
        }
    }

    @Test
    void queuedProbeCannotSatisfyAbsenceOrRunAfterTimeout() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var probes = new AtomicInteger();
        Platform.runLater(
                () -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            final var result =
                    FxWaits.until(
                            () -> probes.incrementAndGet(), value -> value == 0, List::of, 50);
            assertFalse(result.satisfied());
            assertFalse(result.observed());
            assertEquals(0, result.polls());
        } finally {
            release.countDown();
        }
        onFxThread(() -> {});
        assertEquals(0, probes.get());
    }

    @Test
    void modalProbeDoesNotReenterOrEvaluatePredicateAfterTimeout() throws Exception {
        final var shown = new CountDownLatch(1);
        final var returned = new CountDownLatch(1);
        final var stage = new AtomicReference<javafx.stage.Stage>();
        final var probes = new AtomicInteger();
        final var predicates = new AtomicInteger();
        final var invocation =
                callAsync(
                        () ->
                                FxWaits.until(
                                        () -> {
                                            if (probes.incrementAndGet() == 1) {
                                                final var dialog = new javafx.stage.Stage();
                                                dialog.initModality(
                                                        javafx.stage.Modality.APPLICATION_MODAL);
                                                dialog.setScene(new Scene(new Group(), 200, 100));
                                                dialog.setX(40);
                                                dialog.setY(40);
                                                dialog.setOnShown(event -> shown.countDown());
                                                stage.set(dialog);
                                                dialog.showAndWait();
                                                returned.countDown();
                                            }
                                            return true;
                                        },
                                        value -> {
                                            predicates.incrementAndGet();
                                            return value;
                                        },
                                        List::of,
                                        350));
        try {
            assertTrue(shown.await(2, TimeUnit.SECONDS));
            final var result = invocation.get(2, TimeUnit.SECONDS);
            assertFalse(result.satisfied());
            assertEquals(1, result.polls());
            assertEquals(1, probes.get());
            assertEquals(0, predicates.get());
        } finally {
            onFxThread(
                    () -> {
                        if (stage.get() != null) stage.get().close();
                    });
        }
        assertTrue(returned.await(2, TimeUnit.SECONDS));
        onFxThread(() -> {});
        assertEquals(0, predicates.get());
    }

    @Test
    void shortLayoutTimeoutsDoNotThrowOnFxThread() throws Exception {
        final var scene = new AtomicReference<Scene>();
        final var stage = new AtomicReference<javafx.stage.Stage>();
        final var previous = new AtomicReference<Thread.UncaughtExceptionHandler>();
        final var failures = new java.util.concurrent.ConcurrentLinkedQueue<Throwable>();
        onFxThread(
                () -> {
                    final var thread = Thread.currentThread();
                    previous.set(thread.getUncaughtExceptionHandler());
                    thread.setUncaughtExceptionHandler(
                            (owner, problem) -> {
                                failures.add(problem);
                                if (previous.get() != null)
                                    previous.get().uncaughtException(owner, problem);
                            });
                    final var window = new javafx.stage.Stage();
                    scene.set(new Scene(new Group(), 200, 100));
                    window.setScene(scene.get());
                    window.setX(40);
                    window.setY(40);
                    stage.set(window);
                    window.show();
                });
        try {
            for (var i = 0; i < 100; i++) FxWaits.nextLayout(scene.get(), 1);
            onFxThread(() -> {});
            assertTrue(failures.isEmpty(), failures.toString());
        } finally {
            onFxThread(
                    () -> {
                        if (stage.get() != null) stage.get().close();
                        Thread.currentThread().setUncaughtExceptionHandler(previous.get());
                    });
        }
    }

    private static <T> FutureTask<T> callAsync(final ThrowingSupplier<T> action) {
        final var task = new FutureTask<T>(action::get);
        Thread.ofVirtual().start(task);
        return task;
    }

    private static void onFxThread(final ThrowingRunnable action) throws Exception {
        final var task =
                new FutureTask<Void>(
                        () -> {
                            action.run();
                            return null;
                        });
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }

    private static final class TestObservable implements Observable {
        private final List<InvalidationListener> listeners = new ArrayList<>();

        @Override
        public void addListener(final InvalidationListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(final InvalidationListener listener) {
            listeners.remove(listener);
        }

        private int listenerCount() {
            return listeners.size();
        }

        private void fire() {
            for (final var listener : List.copyOf(listeners)) {
                listener.invalidated(this);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
