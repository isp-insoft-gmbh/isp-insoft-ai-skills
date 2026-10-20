package fxdriver;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

final class FxActions {
    private static final int HISTORY_LIMIT = 128;
    private static final Map<Long, Entry> actions = new LinkedHashMap<>();
    private static final ArrayDeque<Long> terminalHistory = new ArrayDeque<>();
    private static long nextId;
    private static final ScopedValue<ActionScope> CURRENT = ScopedValue.newInstance();
    private static final ScopedValue<Boolean> REQUEST_SENSITIVE = ScopedValue.newInstance();

    private record ActionScope(long id, ActionScope parent) {}

    private FxActions() {}

    static <T> T withSensitivity(
            final boolean sensitive, final java.util.function.Supplier<T> action) {
        return ScopedValue.where(REQUEST_SENSITIVE, sensitive || requestSensitive())
                .call(action::get);
    }

    static boolean requestSensitive() {
        if (REQUEST_SENSITIVE.isBound()) return REQUEST_SENSITIVE.get();
        if (!CURRENT.isBound()) return false;
        final Entry entry;
        synchronized (actions) {
            entry = actions.get(CURRENT.get().id());
        }
        return entry != null && entry.requestSensitive;
    }

    static <T> T call(final Callable<T> action) throws Exception {
        return call(action, 0, () -> {}, () -> {});
    }

    static <T> T call(
            final Callable<T> action,
            final int delayMillis,
            final Runnable before,
            final Runnable after)
            throws Exception {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Must not block the JavaFX Application Thread");
        }
        final Entry entry;
        synchronized (actions) {
            entry = new Entry(++nextId);
            actions.put(entry.id, entry);
        }
        final var result = new CompletableFuture<T>();
        final var yielded = new CompletableFuture<Void>();
        final Runnable invoke =
                () -> {
                    if (!start(entry)) {
                        final var failure = complete(entry, null, after);
                        if (failure == null) result.cancel(false);
                        else result.completeExceptionally(failure);
                        return;
                    }
                    Platform.runLater(
                            () -> {
                                if (!result.isDone() && Platform.isNestedLoopRunning()) {
                                    synchronized (entry) {
                                        if (!entry.terminal()) {
                                            entry.completion = "pending";
                                            entry.trackModalStages();
                                        }
                                    }
                                    yielded.complete(null);
                                }
                            });
                    T value = null;
                    Throwable failure = null;
                    try {
                        value = ScopedValue.where(CURRENT, entry.scope).call(action::call);
                    } catch (final Throwable actionFailure) {
                        failure = actionFailure;
                    }
                    failure = complete(entry, failure, after);
                    if (failure == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(failure);
                    }
                };
        try {
            Platform.runLater(
                    () -> {
                        if (!prepare(entry)) {
                            result.cancel(false);
                            return;
                        }
                        try {
                            ScopedValue.where(CURRENT, entry.scope).run(before);
                            final boolean cancelling;
                            synchronized (entry) {
                                cancelling = entry.cancelRequested;
                            }
                            if (cancelling || delayMillis <= 0) {
                                invoke.run();
                            } else {
                                final var pause = new PauseTransition(Duration.millis(delayMillis));
                                // Dialogs cannot enter nested event loops from animation callbacks.
                                pause.setOnFinished(ignored -> Platform.runLater(invoke));
                                pause.play();
                            }
                        } catch (final Throwable failure) {
                            result.completeExceptionally(complete(entry, failure, after));
                        }
                    });
        } catch (final RuntimeException | Error dispatchFailure) {
            complete(entry, dispatchFailure, after);
            throw dispatchFailure;
        }
        try {
            CompletableFuture.anyOf(result, yielded)
                    .get(5000L + Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
            if (!result.isDone()) {
                throw new Pending(entry.id);
            }
            return result.get();
        } catch (final TimeoutException timeout) {
            final boolean started;
            final boolean cancelled;
            final String completion;
            synchronized (entry) {
                started = entry.started;
                cancelled = !entry.terminal() && !started && !entry.cleanupRequired;
                if (!entry.terminal() && !started) {
                    entry.cancelRequested = true;
                    entry.completion = cancelled ? "cancelled" : "cancelling";
                    if (cancelled) entry.dropModalStages();
                } else if (!entry.terminal()) {
                    entry.completion = "unknown";
                }
                completion = entry.completion;
            }
            if (cancelled) recordTerminal(entry);
            throw new TimedOut(entry.id, started, completion);
        } catch (final ExecutionException failure) {
            final var cause = failure.getCause();
            synchronized (entry) {
                if (entry.sensitive) throw new SensitiveFailure(cause.getClass().getSimpleName());
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    static void sensitive() {
        // Reentrant modal dispatch shares the FX thread, but each action has its own ancestry.
        for (ActionScope scope = CURRENT.isBound() ? CURRENT.get() : null;
                scope != null;
                scope = scope.parent()) {
            final Entry entry;
            synchronized (actions) {
                entry = actions.get(scope.id());
            }
            if (entry != null) {
                synchronized (entry) {
                    entry.sensitive = true;
                    if (!entry.errorMessage.isEmpty()) entry.errorMessage = "[redacted]";
                }
            }
        }
    }

    static Status status(final long id) {
        final Entry entry;
        synchronized (actions) {
            entry = actions.get(id);
        }
        if (entry == null) {
            return null;
        }
        synchronized (entry) {
            return new Status(
                    entry.id,
                    entry.completion,
                    entry.errorType,
                    entry.errorMessage,
                    entry.sensitive);
        }
    }

    static boolean awaitingUnwind(final long actionId) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Must be called on the JavaFX Application Thread");
        }
        final Entry entry;
        synchronized (actions) {
            entry = actions.get(actionId);
        }
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (!"pending".equals(entry.completion)) {
                return false;
            }
            entry.trackModalStages();
            return !entry.modalStages.isEmpty()
                    && entry.modalStages.stream().noneMatch(Window::isShowing);
        }
    }

    private static boolean prepare(final Entry entry) {
        final var parent = CURRENT.isBound() ? CURRENT.get() : null;
        final var parentStatus = parent == null ? null : status(parent.id());
        synchronized (entry) {
            if (entry.cancelRequested || entry.terminal()) return false;
            entry.completion = "preparing";
            entry.cleanupRequired = true;
            entry.sensitive |= parentStatus != null && parentStatus.sensitive();
            entry.scope = new ActionScope(entry.id, parent);
            return true;
        }
    }

    private static boolean start(final Entry entry) {
        synchronized (entry) {
            if (entry.cancelRequested || entry.terminal()) return false;
            entry.started = true;
            entry.completion = "running";
            entry.captureBaseline();
            return true;
        }
    }

    private static Throwable complete(
            final Entry entry, final Throwable actionFailure, final Runnable after) {
        final var cleanupFailure = cleanup(entry, after);
        final var failure = actionFailure == null ? cleanupFailure : actionFailure;
        if (actionFailure != null && cleanupFailure != null && actionFailure != cleanupFailure) {
            actionFailure.addSuppressed(cleanupFailure);
        }
        final boolean captureMessage;
        final ActionScope scope;
        synchronized (entry) {
            captureMessage = failure != null && !entry.terminal() && !entry.sensitive;
            scope = entry.scope;
        }
        // Throwable diagnostics can be application code; never call them under the entry lock.
        final var message =
                !captureMessage
                        ? ""
                        : scope == null
                                ? Entry.safeMessage(failure, failure.getClass().getSimpleName())
                                : ScopedValue.where(CURRENT, scope)
                                        .call(
                                                () ->
                                                        Entry.safeMessage(
                                                                failure,
                                                                failure.getClass()
                                                                        .getSimpleName()));
        final boolean terminal;
        synchronized (entry) {
            terminal = !entry.terminal();
            if (terminal) {
                entry.setCompletion(failure, message);
                entry.dropModalStages();
            }
        }
        if (terminal) {
            recordTerminal(entry);
        }
        return failure;
    }

    private static Throwable cleanup(final Entry entry, final Runnable after) {
        synchronized (entry) {
            if (!entry.cleanupRequired || entry.cleanedUp) {
                return null;
            }
            entry.cleanedUp = true;
        }
        try {
            if (entry.scope == null) after.run();
            else ScopedValue.where(CURRENT, entry.scope).run(after);
            return null;
        } catch (final Throwable failure) {
            return failure;
        }
    }

    private static void recordTerminal(final Entry entry) {
        synchronized (actions) {
            terminalHistory.addLast(entry.id);
            while (terminalHistory.size() > HISTORY_LIMIT) {
                final var expiredId = terminalHistory.removeFirst();
                actions.remove(expiredId);
            }
        }
    }

    record Status(
            long id, String completion, String errorType, String errorMessage, boolean sensitive) {}

    private static final class Entry {
        private final long id;
        private String completion = "queued";
        private boolean started;
        private boolean cancelRequested;
        private final boolean requestSensitive;
        private boolean cleanupRequired;
        private boolean cleanedUp;
        private boolean sensitive;
        private ActionScope scope;
        private String errorType = "";
        private String errorMessage = "";
        private Set<Stage> baselineModalStages = Set.of();
        private Set<Stage> modalStages = Set.of();

        private Entry(final long id) {
            this.id = id;
            requestSensitive = FxActions.requestSensitive();
            sensitive = requestSensitive;
        }

        private boolean terminal() {
            return "completed".equals(completion)
                    || "failed".equals(completion)
                    || "cancelled".equals(completion);
        }

        private void setCompletion(final Throwable failure, final String message) {
            completion = failure != null ? "failed" : cancelRequested ? "cancelled" : "completed";
            if (failure != null) {
                errorType = failure.getClass().getSimpleName();
                errorMessage = sensitive ? "[redacted]" : message;
                if (errorMessage.length() > 512) {
                    errorMessage = errorMessage.substring(0, 512);
                }
            }
        }

        private static String safeMessage(final Throwable failure, final String fallback) {
            try {
                final var message = failure.getMessage();
                return message == null ? fallback : message;
            } catch (final RuntimeException | Error brokenDiagnostic) {
                return fallback;
            }
        }

        private void captureBaseline() {
            baselineModalStages = modalStages();
        }

        private void trackModalStages() {
            for (final var stage : modalStages()) {
                if (!baselineModalStages.contains(stage)) {
                    if (modalStages.isEmpty()) {
                        modalStages = Collections.newSetFromMap(new IdentityHashMap<>());
                    }
                    modalStages.add(stage);
                }
            }
        }

        private void dropModalStages() {
            scope = null;
            baselineModalStages = Set.of();
            modalStages = Set.of();
        }

        private static Set<Stage> modalStages() {
            final Set<Stage> stages = Collections.newSetFromMap(new IdentityHashMap<>());
            for (final var window : Window.getWindows()) {
                if (window instanceof Stage stage && stage.getModality() != Modality.NONE) {
                    stages.add(stage);
                }
            }
            return stages;
        }
    }

    static final class SensitiveFailure extends Exception {
        private static final long serialVersionUID = 1L;

        private SensitiveFailure(final String type) {
            super(type + ": [redacted]");
        }
    }

    static final class Pending extends Exception {
        private static final long serialVersionUID = 1L;
        private final long actionId;

        private Pending(final long actionId) {
            this.actionId = actionId;
        }

        long actionId() {
            return actionId;
        }
    }

    static final class TimedOut extends Exception {
        private static final long serialVersionUID = 1L;
        private final long actionId;
        private final boolean started;
        private final String completion;

        private TimedOut(final long actionId, final boolean started, final String completion) {
            super(
                    "cancelled".equals(completion)
                            ? "Queued action cancelled before execution"
                            : "cancelling".equals(completion)
                                    ? "Action not started; cancellation cleanup remains pending"
                                    : "Action completion is unknown");
            this.actionId = actionId;
            this.started = started;
            this.completion = completion;
        }

        long actionId() {
            return actionId;
        }

        boolean started() {
            return started;
        }

        String completion() {
            return completion;
        }
    }
}
