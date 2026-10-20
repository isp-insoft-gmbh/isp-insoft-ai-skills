package fxdriver;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.scene.Scene;
import javafx.util.Subscription;

/** Waits for JavaFX state without polling the application thread continuously. */
final class FxWaits {
    private static final long FALLBACK_MILLIS = 100;

    private FxWaits() {}

    record Result<T>(T value, boolean satisfied, boolean observed, int polls) {}

    static <T> Result<T> until(
            final Callable<T> probe,
            final Predicate<T> satisfied,
            final Supplier<List<Observable>> signals,
            final long timeoutMillis)
            throws Exception {
        requireOffFxThread();
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(satisfied, "satisfied");
        Objects.requireNonNull(signals, "signals");
        final var wait = new State<T>(probe, satisfied, signals, deadline(timeoutMillis));
        wait.requestPoll();
        try {
            return wait.await();
        } finally {
            wait.complete(false);
        }
    }

    static boolean nextLayout(final Scene scene, final long timeoutMillis) throws Exception {
        requireOffFxThread();
        Objects.requireNonNull(scene, "scene");
        final var wait = new LayoutWait(deadline(timeoutMillis));
        wait.install(scene);
        try {
            return wait.await();
        } finally {
            wait.complete(false);
        }
    }

    private static long deadline(final long timeoutMillis) {
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must not be negative");
        }
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private static boolean expired(final long deadline) {
        return System.nanoTime() - deadline >= 0;
    }

    private static void requireOffFxThread() {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Must not wait on the JavaFX Application Thread");
        }
    }

    private static final class State<T> {
        private final Object monitor = new Object();
        private final Callable<T> probe;
        private final Predicate<T> satisfied;
        private final Supplier<List<Observable>> signals;
        private final long deadline;
        private List<Subscription> subscriptions = List.of();
        private boolean completed;
        private boolean scheduled;
        private boolean observed;
        private boolean matches;
        private int polls;
        private T value;
        private Throwable failure;

        private State(
                final Callable<T> probe,
                final Predicate<T> satisfied,
                final Supplier<List<Observable>> signals,
                final long deadline) {
            this.probe = probe;
            this.satisfied = satisfied;
            this.signals = signals;
            this.deadline = deadline;
        }

        private Result<T> await() throws Exception {
            while (true) {
                final boolean timedOut;
                synchronized (monitor) {
                    if (completed) {
                        if (failure != null) {
                            throwFailure(failure);
                        }
                        return new Result<>(value, matches, observed, polls);
                    }
                    final var remaining = deadline - System.nanoTime();
                    timedOut = remaining <= 0;
                    if (!timedOut) {
                        try {
                            monitor.wait(
                                    Math.min(
                                            TimeUnit.NANOSECONDS.toMillis(remaining) + 1,
                                            FALLBACK_MILLIS));
                        } catch (final InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw interrupted;
                        }
                    }
                }
                if (timedOut) {
                    complete(false);
                } else {
                    requestPoll();
                }
            }
        }

        private void requestPoll() {
            final boolean submit;
            synchronized (monitor) {
                submit = !completed && !scheduled;
                if (submit) {
                    scheduled = true;
                }
            }
            if (submit) {
                try {
                    Platform.runLater(this::poll);
                } catch (final IllegalStateException unavailable) {
                    synchronized (monitor) {
                        scheduled = false;
                    }
                }
            }
        }

        private void poll() {
            try {
                if (isCompletedOrExpired()) return;
                synchronized (monitor) {
                    if (completed) return;
                    polls++;
                }
                final T next;
                try {
                    next = probe.call();
                } catch (final Throwable probeFailure) {
                    complete(probeFailure);
                    return;
                }
                if (isCompletedOrExpired()) return;
                final boolean nextMatches;
                try {
                    nextMatches = satisfied.test(next);
                } catch (final Throwable predicateFailure) {
                    complete(predicateFailure);
                    return;
                }
                synchronized (monitor) {
                    if (!completed) {
                        observed = true;
                        value = next;
                        matches = nextMatches;
                    }
                }
                if (nextMatches) {
                    complete(true);
                    return;
                }
                if (!isCompletedOrExpired()) refreshSubscriptions();
            } finally {
                synchronized (monitor) {
                    scheduled = false;
                }
            }
        }

        private boolean isCompletedOrExpired() {
            synchronized (monitor) {
                if (completed) {
                    return true;
                }
            }
            if (expired(deadline)) {
                complete(false);
                return true;
            }
            return false;
        }

        private void refreshSubscriptions() {
            final List<Observable> nextSignals;
            try {
                nextSignals = Objects.requireNonNull(signals.get(), "signals.get()");
            } catch (final Throwable signalFailure) {
                complete(signalFailure);
                return;
            }
            if (isCompletedOrExpired()) return;
            final var nextSubscriptions = new ArrayList<Subscription>(nextSignals.size());
            final var observer = new java.lang.ref.WeakReference<>(this);
            final Runnable wakeup =
                    () -> {
                        final var active = observer.get();
                        if (active != null) active.requestPoll();
                    };
            try {
                for (final var signal : nextSignals) {
                    if (isCompletedOrExpired()) break;
                    nextSubscriptions.add(
                            Objects.requireNonNull(signal, "signal").subscribe(wakeup));
                }
            } catch (final Throwable subscriptionFailure) {
                final var cleanupFailure = unsubscribe(nextSubscriptions);
                if (cleanupFailure != null && cleanupFailure != subscriptionFailure) {
                    subscriptionFailure.addSuppressed(cleanupFailure);
                }
                complete(subscriptionFailure);
                return;
            }
            final List<Subscription> previous;
            synchronized (monitor) {
                if (completed) {
                    previous = nextSubscriptions;
                } else {
                    previous = subscriptions;
                    subscriptions = nextSubscriptions;
                }
            }
            final var cleanupFailure = unsubscribe(previous);
            if (cleanupFailure != null) complete(cleanupFailure);
        }

        private void complete(final boolean completeMatches) {
            final List<Subscription> stale;
            synchronized (monitor) {
                if (completed) {
                    return;
                }
                completed = true;
                matches = completeMatches && matches;
                stale = subscriptions;
                subscriptions = List.of();
                monitor.notifyAll();
            }
            cleanup(stale);
        }

        private void complete(final Throwable nextFailure) {
            final List<Subscription> stale;
            synchronized (monitor) {
                if (completed) {
                    return;
                }
                failure = nextFailure;
                completed = true;
                stale = subscriptions;
                subscriptions = List.of();
                monitor.notifyAll();
            }
            cleanup(stale);
        }

        private void cleanup(final List<Subscription> stale) {
            if (Platform.isFxApplicationThread()) {
                reportCleanupFailure(unsubscribe(stale));
                return;
            }
            try {
                Platform.runLater(() -> reportCleanupFailure(unsubscribe(stale)));
            } catch (final IllegalStateException unavailable) {
                // JavaFX has already stopped; subscriptions cannot receive another callback.
            }
        }

        private static void reportCleanupFailure(final Throwable problem) {
            if (problem == null) return;
            System.err.println(
                    "[fxdriver] Wait subscription cleanup failed: "
                            + problem.getClass().getSimpleName());
        }

        private static Throwable unsubscribe(final List<Subscription> stale) {
            Throwable failure = null;
            for (final var subscription : stale) {
                try {
                    subscription.unsubscribe();
                } catch (final Throwable problem) {
                    if (failure == null) failure = problem;
                    else if (failure != problem) failure.addSuppressed(problem);
                }
            }
            return failure;
        }
    }

    private static final class LayoutWait {
        private final Object monitor = new Object();
        private final long deadline;
        private Scene scene;
        private Runnable listener;
        private boolean completed;
        private boolean laidOut;
        private boolean pulseQueued;

        private LayoutWait(final long deadline) {
            this.deadline = deadline;
        }

        private void install(final Scene nextScene) {
            try {
                Platform.runLater(
                        () -> {
                            if (expired(deadline)) {
                                complete(false);
                                return;
                            }
                            final Runnable nextListener = this::afterPulse;
                            synchronized (monitor) {
                                if (completed) {
                                    return;
                                }
                                scene = nextScene;
                                listener = nextListener;
                            }
                            nextScene.addPostLayoutPulseListener(nextListener);
                            Platform.requestNextPulse();
                        });
            } catch (final IllegalStateException unavailable) {
                complete(false);
            }
        }

        private void afterPulse() {
            final boolean submit;
            synchronized (monitor) {
                submit = !completed && !pulseQueued;
                if (submit) {
                    pulseQueued = true;
                }
            }
            if (submit) {
                Platform.runLater(() -> complete(!expired(deadline)));
            }
        }

        private boolean await() throws InterruptedException {
            while (true) {
                synchronized (monitor) {
                    if (completed) {
                        return laidOut;
                    }
                    final var remaining = deadline - System.nanoTime();
                    if (remaining > 0) {
                        monitor.wait(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
                        continue;
                    }
                }
                complete(false);
            }
        }

        private void complete(final boolean nextLaidOut) {
            final Scene staleScene;
            final Runnable staleListener;
            synchronized (monitor) {
                if (completed) {
                    return;
                }
                completed = true;
                laidOut = nextLaidOut;
                staleScene = scene;
                staleListener = listener;
                scene = null;
                listener = null;
                monitor.notifyAll();
            }
            if (staleScene != null && staleListener != null) {
                if (Platform.isFxApplicationThread()) {
                    staleScene.removePostLayoutPulseListener(staleListener);
                } else {
                    try {
                        Platform.runLater(
                                () -> staleScene.removePostLayoutPulseListener(staleListener));
                    } catch (final IllegalStateException unavailable) {
                        // JavaFX has already stopped.
                    }
                }
            }
        }
    }

    private static void throwFailure(final Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
    }
}
