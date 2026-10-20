package fxdriver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class FxActionsTest {
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

    @AfterEach
    void closeFixtureWindows() throws Exception {
        onFxThread(
                () -> {
                    for (final var window : List.copyOf(Window.getWindows())) {
                        if (window instanceof Stage stage) {
                            stage.close();
                        }
                    }
                });
    }

    @Test
    void retainsLateModalFailureAfterNewerTerminalCompletions() throws Exception {
        final var stage = new AtomicReference<Stage>();
        final var invocation =
                callAsync(
                        () ->
                                FxActions.call(
                                        () -> {
                                            final var dialog = modalStage();
                                            stage.set(dialog);
                                            dialog.showAndWait();
                                            throw new IllegalStateException("late modal failure");
                                        }));
        final var actionId = pendingActionId(invocation);

        for (var i = 0; i < 129; i++) {
            final var completion = i;
            assertEquals(completion, FxActions.call(() -> completion));
        }
        onFxThread(() -> stage.get().close());

        final var status = awaitTerminalStatus(actionId);
        assertEquals("failed", status.completion());
        assertEquals("IllegalStateException", status.errorType());
        assertEquals("late modal failure", status.errorMessage());
    }

    @Test
    void cleanupFailureFailsSuccessfulActionBeforePublishingSuccess() {
        final var cleanupCalls = new AtomicInteger();

        final var failure =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                FxActions.call(
                                        () -> "value",
                                        0,
                                        () -> {},
                                        () -> {
                                            cleanupCalls.incrementAndGet();
                                            throw new IllegalStateException("cleanup failed");
                                        }));

        assertEquals("cleanup failed", failure.getMessage());
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void preservesActionFailureWhenCleanupAlsoFails() {
        final var actionFailure = new IllegalArgumentException("action failed");

        final var failure =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                FxActions.call(
                                        () -> {
                                            throw actionFailure;
                                        },
                                        0,
                                        () -> {},
                                        () -> {
                                            throw new IllegalStateException("cleanup failed");
                                        }));

        assertSame(actionFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("cleanup failed", failure.getSuppressed()[0].getMessage());
    }

    @Test
    void cancelsInvocationQueuedBehindBlockedFxThread() throws Exception {
        final var executed = new AtomicInteger();
        final var blocker = new CountDownLatch(1);
        Platform.runLater(
                () -> {
                    try {
                        blocker.await();
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
        final var invocation = callAsync(() -> FxActions.call(() -> executed.incrementAndGet()));

        try {
            final var failure =
                    assertThrows(
                            ExecutionException.class, () -> invocation.get(7, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof FxActions.TimedOut);
            final var timeout = (FxActions.TimedOut) failure.getCause();
            assertFalse(timeout.started());
            assertEquals("cancelled", timeout.completion());
            assertEquals("cancelled", FxActions.status(timeout.actionId()).completion());
        } finally {
            blocker.countDown();
        }
        onFxThread(() -> {});
        assertEquals(0, executed.get());
    }

    @Test
    void reportsOrdinaryAndNestedModalUnwind() throws Exception {
        final var outer = new AtomicReference<Stage>();
        final var nested = new AtomicReference<Stage>();
        final var actionId = new AtomicLong();
        final var invocation =
                callAsync(
                        () ->
                                FxActions.call(
                                        () -> {
                                            final var outerStage = modalStage();
                                            outer.set(outerStage);
                                            final Runnable[] openNested = new Runnable[1];
                                            openNested[0] =
                                                    () -> {
                                                        if (actionId.get() == 0) {
                                                            Platform.runLater(openNested[0]);
                                                            return;
                                                        }
                                                        final var nestedStage = modalStage();
                                                        nested.set(nestedStage);
                                                        nestedStage.setOnShown(
                                                                nestedIgnored ->
                                                                        Platform.runLater(
                                                                                () -> {
                                                                                    assertFalse(
                                                                                            FxActions
                                                                                                    .awaitingUnwind(
                                                                                                            actionId
                                                                                                                    .get()));
                                                                                    nestedStage
                                                                                            .close();
                                                                                    assertFalse(
                                                                                            FxActions
                                                                                                    .awaitingUnwind(
                                                                                                            actionId
                                                                                                                    .get()));
                                                                                    outerStage
                                                                                            .close();
                                                                                    assertTrue(
                                                                                            FxActions
                                                                                                    .awaitingUnwind(
                                                                                                            actionId
                                                                                                                    .get()));
                                                                                }));
                                                        nestedStage.showAndWait();
                                                    };
                                            outerStage.setOnShown(
                                                    ignored -> Platform.runLater(openNested[0]));
                                            outerStage.showAndWait();
                                            return null;
                                        }));
        actionId.set(pendingActionId(invocation));

        assertEquals("completed", awaitTerminalStatus(actionId.get()).completion());
        assertNotNull(outer.get());
        assertNotNull(nested.get());
    }

    @Test
    void retainsCancellationUntilBeforeAndCleanupFinish() throws Exception {
        for (final var cleanupFails : List.of(false, true)) {
            final var entered = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var executed = new AtomicInteger();
            final var cleaned = new AtomicInteger();
            final var invocation =
                    callAsync(
                            () ->
                                    FxActions.call(
                                            executed::incrementAndGet,
                                            0,
                                            () -> {
                                                entered.countDown();
                                                await(release);
                                            },
                                            () -> {
                                                cleaned.incrementAndGet();
                                                if (cleanupFails)
                                                    throw new IllegalStateException(
                                                            "cleanup failed");
                                            }));
            final FxActions.TimedOut timeout;
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                timeout = timeout(invocation);
                assertFalse(timeout.started());
                assertEquals("cancelling", timeout.completion());
                assertEquals("cancelling", FxActions.status(timeout.actionId()).completion());
                assertEquals(0, cleaned.get());
            } finally {
                release.countDown();
            }
            final var status = awaitTerminalStatus(timeout.actionId());
            assertEquals(cleanupFails ? "failed" : "cancelled", status.completion());
            assertEquals(cleanupFails ? "cleanup failed" : "", status.errorMessage());
            assertEquals(0, executed.get());
            assertEquals(1, cleaned.get());
        }
    }

    @Test
    void retainsSetupFailureWhileCleanupIsStillRunningAtTimeout() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var setupFailure = new IllegalArgumentException("setup failed");
        final var executed = new AtomicInteger();
        final var invocation =
                callAsync(
                        () ->
                                FxActions.call(
                                        executed::incrementAndGet,
                                        0,
                                        () -> {
                                            throw setupFailure;
                                        },
                                        () -> {
                                            entered.countDown();
                                            await(release);
                                            throw new IllegalStateException("cleanup failed");
                                        }));
        final FxActions.TimedOut timeout;
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            timeout = timeout(invocation);
            assertEquals("cancelling", FxActions.status(timeout.actionId()).completion());
        } finally {
            release.countDown();
        }
        final var status = awaitTerminalStatus(timeout.actionId());
        assertEquals("failed", status.completion());
        assertEquals("IllegalArgumentException", status.errorType());
        assertEquals("setup failed", status.errorMessage());
        assertEquals(1, setupFailure.getSuppressed().length);
        assertEquals(0, executed.get());
    }

    @Test
    void retainsCancelledModalSetupAndItsSensitiveCleanupAfter129Completions() throws Exception {
        final var stage = new AtomicReference<Stage>();
        final var shown = new CountDownLatch(1);
        final var executed = new AtomicInteger();
        final var cleaned = new AtomicInteger();
        final var invocation =
                callAsync(
                        () ->
                                FxActions.call(
                                        executed::incrementAndGet,
                                        0,
                                        () -> {
                                            final var dialog = modalStage();
                                            stage.set(dialog);
                                            dialog.setOnShown(event -> shown.countDown());
                                            dialog.showAndWait();
                                        },
                                        () -> {
                                            cleaned.incrementAndGet();
                                            throw new IllegalStateException(
                                                    "sentinel-cancelled-setup-secret");
                                        }));
        final FxActions.TimedOut timeout;
        try {
            assertTrue(shown.await(5, TimeUnit.SECONDS));
            timeout = timeout(invocation);
            assertEquals("cancelling", timeout.completion());
            for (var i = 0; i < 129; i++) {
                final var value = i;
                assertEquals(value, FxActions.call(() -> value));
            }
            FxActions.call(
                    () -> {
                        FxActions.sensitive();
                        return null;
                    });
            assertNotNull(FxActions.status(timeout.actionId()));
            assertEquals("cancelling", FxActions.status(timeout.actionId()).completion());
        } finally {
            onFxThread(
                    () -> {
                        if (stage.get() != null) stage.get().close();
                    });
        }
        final var status = awaitTerminalStatus(timeout.actionId());
        assertEquals("failed", status.completion());
        assertEquals("[redacted]", status.errorMessage());
        assertEquals(0, executed.get());
        assertEquals(1, cleaned.get());
    }

    @Test
    void redactsOuterActionAndCleanupFailuresAfterSensitiveReentrantAction() throws Exception {
        for (final var cleanupFails : List.of(false, true)) {
            final var stage = new AtomicReference<Stage>();
            final var invocation =
                    callAsync(
                            () ->
                                    FxActions.call(
                                            () -> {
                                                final var dialog = modalStage();
                                                stage.set(dialog);
                                                dialog.showAndWait();
                                                if (!cleanupFails)
                                                    throw new IllegalStateException(
                                                            "sentinel-outer-action-secret");
                                                return null;
                                            },
                                            0,
                                            () -> {},
                                            () -> {
                                                if (cleanupFails)
                                                    throw new IllegalStateException(
                                                            "sentinel-outer-cleanup-secret");
                                            }));
            final var actionId = pendingActionId(invocation);
            try {
                FxActions.call(
                        () -> {
                            FxActions.sensitive();
                            stage.get().close();
                            return null;
                        });
            } finally {
                onFxThread(
                        () -> {
                            if (stage.get() != null) stage.get().close();
                        });
            }
            final var status = awaitTerminalStatus(actionId);
            assertEquals("failed", status.completion());
            assertEquals("[redacted]", status.errorMessage());
        }
    }

    @Test
    void sensitiveFailureDoesNotExposeOriginalThrowableChain() {
        final var failure =
                assertThrows(
                        FxActions.SensitiveFailure.class,
                        () ->
                                FxActions.call(
                                        () -> {
                                            FxActions.sensitive();
                                            throw new IllegalArgumentException(
                                                    "sentinel-action-secret");
                                        },
                                        0,
                                        () -> {},
                                        () -> {
                                            throw new IllegalStateException(
                                                    "sentinel-cleanup-secret");
                                        }));
        assertEquals("IllegalArgumentException: [redacted]", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void reentrantActionInheritsSensitiveContext() throws Exception {
        final var stage = new AtomicReference<Stage>();
        final var invocation =
                callAsync(
                        () ->
                                FxActions.call(
                                        () -> {
                                            FxActions.sensitive();
                                            final var dialog = modalStage();
                                            stage.set(dialog);
                                            dialog.showAndWait();
                                            return null;
                                        }));
        final var actionId = pendingActionId(invocation);
        try {
            final var failure =
                    assertThrows(
                            FxActions.SensitiveFailure.class,
                            () ->
                                    FxActions.call(
                                            () -> {
                                                throw new IllegalStateException(
                                                        "sentinel-child-secret");
                                            }));
            assertEquals("IllegalStateException: [redacted]", failure.getMessage());
            assertNull(failure.getCause());
        } finally {
            onFxThread(() -> stage.get().close());
        }
        assertEquals("completed", awaitTerminalStatus(actionId).completion());
    }

    @Test
    void diagnosticGetterCannotBlockStatusLookupUnderEntryLock() throws Exception {
        final var stage = new AtomicReference<Stage>();
        final var invocation =
                callAsync(
                        () ->
                                FxActions.call(
                                        () -> {
                                            throw new IllegalStateException() {
                                                @Override
                                                public String getMessage() {
                                                    final var dialog = modalStage();
                                                    stage.set(dialog);
                                                    dialog.showAndWait();
                                                    return "diagnostic";
                                                }
                                            };
                                        }));
        final var actionId = pendingActionId(invocation);
        final var observed = new AtomicReference<FxActions.Status>();
        try {
            callAsync(() -> observed.set(FxActions.status(actionId))).get(2, TimeUnit.SECONDS);
            assertNotNull(observed.get());
            assertEquals("pending", observed.get().completion());
        } finally {
            onFxThread(() -> stage.get().close());
        }
        final var status = awaitTerminalStatus(actionId);
        assertEquals("failed", status.completion());
        assertEquals("diagnostic", status.errorMessage());
    }

    @Test
    void declaredSensitivitySurvivesFxDispatchWithoutLeakingToNextRequest() throws Exception {
        final var failure =
                FxActions.withSensitivity(
                        true,
                        () ->
                                assertThrows(
                                        FxActions.SensitiveFailure.class,
                                        () ->
                                                FxActions.call(
                                                        () -> {
                                                            assertTrue(
                                                                    FxActions.requestSensitive());
                                                            throw new IllegalStateException(
                                                                    "sentinel-declared-secret");
                                                        })));
        assertEquals("IllegalStateException: [redacted]", failure.getMessage());
        assertFalse(FxActions.requestSensitive());
        assertFalse(FxActions.call(FxActions::requestSensitive));
    }

    private static FxActions.TimedOut timeout(final FutureTask<?> invocation) throws Exception {
        final var failure =
                assertThrows(ExecutionException.class, () -> invocation.get(7, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof FxActions.TimedOut);
        return (FxActions.TimedOut) failure.getCause();
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new AssertionError("Fixture latch not released");
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static Stage modalStage() {
        final var stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        return stage;
    }

    private static long pendingActionId(final FutureTask<?> invocation) throws Exception {
        final var failure =
                assertThrows(ExecutionException.class, () -> invocation.get(10, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof FxActions.Pending);
        return ((FxActions.Pending) failure.getCause()).actionId();
    }

    private static FxActions.Status awaitTerminalStatus(final long actionId)
            throws InterruptedException {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final var status = FxActions.status(actionId);
            if (status != null
                    && List.of("completed", "failed", "cancelled").contains(status.completion())) {
                return status;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Action did not complete: " + actionId);
    }

    private static FutureTask<Void> callAsync(final ThrowingRunnable action) {
        final var task =
                new FutureTask<Void>(
                        () -> {
                            action.run();
                            return null;
                        });
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
