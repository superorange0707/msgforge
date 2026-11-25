package io.msgforge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.function.BiFunction;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * A monitor watches one destination outside any run: the user starts it, looks at what arrives and in which buckets,
 * resets it, and stops it. It is the same {@link Observer} a run uses for the destinations it watches.
 *
 * Receiving from a queue takes the messages off it. So a monitor and a run never listen to the same destination
 * at once: each would get some of the messages, and the run's verdict would be wrong. Whichever comes second is refused.
 */
@Component
public class Monitors {

    static final int MAX = 10;

    public record Request(String connection, String destination, List<Observer.Bucket> buckets) {}

    /** error is set when receiving broke: the monitor has stopped listening and keeps what it counted until it is removed. */
    public record View(String id, String connectionId, Instant startedAt, String error, Observer.View watch) {}

    private static final class Monitor {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final Instant startedAt = Instant.now();
        final String connectionId, resolved;
        final Observer observer;
        volatile TransportType.Subscription subscription;
        volatile String error;

        Monitor(String connectionId, String resolved, Observer observer) {
            this.connectionId = connectionId;
            this.resolved = resolved;
            this.observer = observer;
        }

        View view() {
            return new View(id, connectionId, startedAt, error, observer.view(true));
        }
    }

    private final Connections connections;
    private final List<Monitor> monitors = new ArrayList<>();
    /** Asks the runs whether one of them is receiving from a destination; answers its label, or null. Set by {@link Runs}. */
    volatile BiFunction<String, String, String> runReceiving = (connection, destination) -> null;

    public Monitors(Connections connections) {
        this.connections = connections;
    }

    public View start(Request r) {
        if (r.connection() == null || r.connection().isBlank()) throw new IllegalArgumentException("Connection is required");
        if (r.destination() == null || r.destination().isBlank()) throw new IllegalArgumentException("Destination is required");
        Connections.View c = connections.get(r.connection());
        if (!connections.canReceive(c.id())) throw new IllegalArgumentException(c.name() + " is a " + c.typeLabel() + " connection, which cannot receive.");
        String shown = r.destination().trim(), resolved = connections.resolve(shown);
        Monitor m = new Monitor(c.id(), resolved, new Observer(c.name(), shown, r.buckets()));
        synchronized (monitors) {
            if (monitors.size() >= MAX) throw new IllegalArgumentException("At most " + MAX + " monitors. Stop one first.");
            if (listening(c.id(), resolved) != null) throw new IllegalArgumentException("A monitor is already on " + c.name() + " / " + shown);
            String run = runReceiving.apply(c.id(), resolved);
            if (run != null) {
                throw new IllegalArgumentException("The run " + run + " is receiving from " + c.name() + " / " + shown
                        + ". A monitor would take some of its messages: wait for the run, or let the run watch the destination.");
            }
            monitors.add(m);   // listed before it listens, so a run that starts now sees it
        }
        try {
            m.subscription = connections.receive(c.id(), resolved, a -> m.observer.arrived(a, null), error -> {
                m.error = "Receiving failed: " + error;
                close(m);
            });
            if (m.error != null) close(m);   // it broke before there was a subscription to close
        } catch (RuntimeException e) {
            synchronized (monitors) {
                monitors.remove(m);
            }
            throw new IllegalArgumentException("Cannot receive from " + c.name() + " / " + shown + ": " + Runs.describe(e));
        }
        return m.view();
    }

    /** The monitor's own name for what is listening on a destination, or null. Runs ask before they subscribe. */
    String listening(String connectionId, String resolved) {
        synchronized (monitors) {
            return monitors.stream().filter(m -> m.error == null && m.connectionId.equals(connectionId) && m.resolved.equals(resolved))
                    .map(m -> m.id).findFirst().orElse(null);
        }
    }

    public List<View> list() {
        synchronized (monitors) {
            return monitors.stream().map(Monitor::view).toList();
        }
    }

    public View reset(String id) {
        Monitor m = find(id);
        m.observer.reset();
        return m.view();
    }

    public void stop(String id) {
        Monitor m = find(id);
        synchronized (monitors) {
            monitors.remove(m);
        }
        close(m);
    }

    private Monitor find(String id) {
        synchronized (monitors) {
            return monitors.stream().filter(m -> m.id.equals(id)).findFirst().orElseThrow(() -> new NoSuchElementException("no monitor " + id));
        }
    }

    /** A broker that does not answer the close is not waited for longer than a run would wait. */
    private static void close(Monitor m) {
        TransportType.Subscription s = m.subscription;
        m.subscription = null;
        if (s == null) return;
        try {
            Runs.within(15, s::close, () -> false);
        } catch (Throwable ignored) {
            // nothing left to release
        }
    }

    @PreDestroy
    void stopAll() {
        List<Monitor> all;
        synchronized (monitors) {
            all = new ArrayList<>(monitors);
            monitors.clear();
        }
        all.forEach(Monitors::close);
    }
}
