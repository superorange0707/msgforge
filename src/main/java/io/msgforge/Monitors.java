package io.msgforge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.annotation.PreDestroy;

/**
 * A monitor watches one destination outside any run, a reject queue all day say: it counts what arrives and in
 * which buckets until it is reset or stopped. It is the same {@link Observer} a run uses for the destinations it watches.
 *
 * A monitor can be saved. Saved ones live in the "monitors" section of the configuration file and start with the
 * console. One whose broker cannot be reached does not hold the start up: it is listed as down with the reason,
 * and tries again after 5, 10, 20, 40 and then every 60 seconds. The same goes for a monitor whose receiving breaks
 * later; what it had counted stays.
 *
 * A monitor shares its subscription with any run that listens on the same destination ({@link Connections#share}),
 * so each of them sees every arrival and neither takes messages from the other.
 */
@Component
public class Monitors {

    private static final Logger log = LoggerFactory.getLogger(Monitors.class);
    static final int MAX = 10, MAX_NAME = 80;
    /** How long a monitor that is down waits before each new attempt; the last value repeats. */
    static final long[] RETRY_SECONDS = {5, 10, 20, 40, 60};
    static final String REFUSED = "It will not try again until it is started.";

    /** name is optional and is what the console lists the monitor under. save keeps it in the configuration file. */
    public record Request(String name, String connection, String destination, List<Observer.Bucket> buckets, Boolean save) {

        public Request(String connection, String destination, List<Observer.Bucket> buckets) {
            this(null, connection, destination, buckets, null);
        }
    }

    /**
     * A monitor as the configuration file holds it. The destination may contain ${MSGFORGE_NAME} and the connection's
     * own vars. start false keeps it listed, stopped, until somebody starts it: a monitor takes what arrives off the
     * queue, which on a queue that others use too is for whoever is testing there to decide. Null or true starts it
     * with the console, as before.
     */
    public record Saved(String id, String name, String connection, String destination, List<Observer.Bucket> buckets,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Boolean start) {

        public Saved(String id, String name, String connection, String destination, List<Observer.Bucket> buckets) {
            this(id, name, connection, destination, buckets, null);
        }
    }

    /**
     * state is "starting" (not tried yet), "up", "down" or "stopped" (a saved one that was stopped by hand, or any one that
     * stopped itself because the broker refused its credentials: that one has an error). A monitor that is down has the
     * reason in error and, unless the reason is something only an edit can mend, the time of its next attempt in retryAt.
     * destination is as written (watch has it with the connection's vars filled in).
     */
    public record View(String id, String name, String connectionId, Instant startedAt, String state, String error, Instant retryAt,
                       boolean saved, Observer.View watch, String destination) {}

    private static final class Monitor {
        final String id, name, connectionId, destination;
        final List<Observer.Bucket> buckets;
        final Instant startedAt = Instant.now();
        final Observer observer;
        final boolean mendable;   // false: what is wrong is in the saved entry itself, and trying again would not help
        volatile boolean byHand;  // saved with start false: not started with the console
        volatile boolean saved, stopped;
        volatile String state = "starting", error;
        volatile Instant retryAt;
        // guarded by this
        TransportType.Subscription subscription;
        int attempts;
        long breaks;   // counts the times receiving broke, so a connect can tell that it broke while it was still connecting

        Monitor(String id, String name, String connectionId, String destination, List<Observer.Bucket> buckets, String shownConnection, String shownDestination, boolean saved) {
            this.id = id;
            this.name = name;
            this.connectionId = connectionId;
            this.destination = destination;
            this.saved = saved;
            Observer made;
            String wrong = connectionId.isBlank() ? "This saved monitor names no connection" : destination.isBlank() ? "This saved monitor names no destination" : null;
            try {
                made = new Observer(shownConnection, shownDestination, buckets);
            } catch (IllegalArgumentException e) {   // a hand-written entry: it is listed with what is wrong, not dropped
                made = new Observer(shownConnection, shownDestination, List.of());
                wrong = e.getMessage();
                buckets = List.of();
            }
            this.buckets = buckets == null ? List.of() : List.copyOf(buckets);
            this.observer = made;
            this.mendable = wrong == null;
            if (wrong != null) {
                state = "down";
                error = wrong;
            }
        }

        View view() {
            return new View(id, name, connectionId, startedAt, state, error, retryAt, saved, observer.view(true), destination);
        }
    }

    private final Connections connections;
    private final ConfigStore store;
    private final List<Monitor> monitors = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "msgforge-monitors");
        t.setDaemon(true);
        return t;
    });
    /** A second is a second, except in a test that will not wait a minute for the fifth attempt. */
    volatile long retryUnitMillis = 1000;

    /** Without a configuration file: nothing is loaded and nothing can be saved. */
    public Monitors(Connections connections) {
        this(connections, null);
    }

    @Autowired
    public Monitors(Connections connections, ConfigStore store) {
        this.connections = connections;
        this.store = store;
        List<Saved> saved = store == null ? null : store.read("monitors", new TypeReference<List<Saved>>() {});
        if (saved == null) return;
        for (Saved s : saved) {
            if (s == null) continue;
            if (monitors.size() >= MAX) {
                log.warn("The configuration file has more than {} monitors; the rest are not started", MAX);
                break;
            }
            // A hand-edited file may leave things out; fill them in so nothing later meets a null.
            String id = s.id() == null || s.id().isBlank() || find(s.id(), false) != null ? UUID.randomUUID().toString().substring(0, 8) : s.id();
            String connection = s.connection() == null ? "" : s.connection().trim(), destination = s.destination() == null ? "" : s.destination().trim();
            String name = s.name() == null || s.name().isBlank() ? null : s.name().trim();
            if (name != null && name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
            Monitor m = new Monitor(id, name, connection, destination, s.buckets(), shown(connection), connections.expand(connection, destination), true);
            if (Boolean.FALSE.equals(s.start())) {
                m.byHand = true;
                if (m.mendable) {
                    m.stopped = true;
                    m.state = "stopped";
                }
            }
            monitors.add(m);
        }
    }

    private static String name(String given) {
        if (given == null || given.isBlank()) return null;
        if (given.trim().length() > MAX_NAME) throw new IllegalArgumentException("A monitor's name can be " + MAX_NAME + " characters long");
        return given.trim();
    }

    /** The connection's name for a title, or its id while there is no such connection. */
    private String shown(String connectionId) {
        try {
            return connections.get(connectionId).name();
        } catch (RuntimeException e) {
            return connectionId;
        }
    }

    /**
     * The saved monitors start once the console is up, each on the monitors' own thread: a broker that takes ten
     * seconds to refuse delays nobody. The command line (run, list) starts none: it has no console to show them, and
     * a pipeline should not take messages off a queue somebody is watching.
     */
    @EventListener
    void ready(ApplicationReadyEvent event) {
        if (event.getApplicationContext() instanceof WebApplicationContext) startSaved();
    }

    void startSaved() {
        synchronized (monitors) {
            for (Monitor m : monitors) if (m.mendable && m.state.equals("starting")) scheduler.execute(() -> attempt(m));
        }
    }

    public View start(Request r) {
        if (r.connection() == null || r.connection().isBlank()) throw new IllegalArgumentException("Connection is required");
        if (r.destination() == null || r.destination().isBlank()) throw new IllegalArgumentException("Destination is required");
        Connections.View c = connections.get(r.connection());
        if (!connections.canReceive(c.id())) throw new IllegalArgumentException(c.name() + " is a " + c.typeLabel() + " connection, which cannot receive.");
        String destination = r.destination().trim(), resolved = connections.resolve(c.id(), destination);
        boolean save = Boolean.TRUE.equals(r.save());
        if (save && store == null) throw new IllegalArgumentException("There is no configuration file to save a monitor in");
        Monitor m = new Monitor(UUID.randomUUID().toString().substring(0, 8), name(r.name()), c.id(), destination, Observer.check(r.buckets()), c.name(),
                connections.expand(c.id(), destination), false);
        synchronized (monitors) {
            if (monitors.size() >= MAX) throw new IllegalArgumentException("At most " + MAX + " monitors. Stop one first.");
            for (Monitor other : monitors) {
                if (other.connectionId.equals(c.id()) && (other.destination.equals(destination) || resolved.equals(resolvedOrNull(c.id(), other.destination)))) {
                    throw new IllegalArgumentException("A monitor is already on " + c.name() + " / " + destination);
                }
            }
            monitors.add(m);
        }
        // Started by hand, a monitor that cannot listen is refused with the reason: there is somebody to read it.
        try {
            connect(m);
        } catch (RuntimeException e) {
            synchronized (monitors) {
                monitors.remove(m);
            }
            throw new IllegalArgumentException("Cannot receive from " + c.name() + " / " + destination + ": " + Runs.describe(e));
        }
        if (save) {
            m.saved = true;
            persist();
        }
        return m.view();
    }

    private String resolvedOrNull(String connectionId, String destination) {
        try {
            return connections.resolve(connectionId, destination);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Subscribes, or throws why it could not. */
    private void connect(Monitor m) {
        long before;
        synchronized (m) {
            if (m.stopped || m.subscription != null) return;
            before = m.breaks;
        }
        Connections.View c = connections.get(m.connectionId);
        if (!connections.canReceive(c.id())) throw new IllegalArgumentException(c.name() + " is a " + c.typeLabel() + " connection, which cannot receive.");
        TransportType.Subscription s = connections.share(c.id(), connections.resolve(c.id(), m.destination), a -> m.observer.arrived(a, null), error -> broke(m, "Receiving failed: " + error));
        boolean keep;
        synchronized (m) {
            keep = !m.stopped && m.breaks == before;   // not stopped, and not broken again, while the broker was answering
            if (keep) {
                m.subscription = s;
                m.attempts = 0;
                m.retryAt = null;
                m.error = null;
                m.state = "up";
            }
        }
        if (!keep) close(s);
    }

    /** A try that reports failure by marking the monitor down and booking the next try. */
    private void attempt(Monitor m) {
        try {
            connect(m);
        } catch (RuntimeException e) {
            broke(m, "Cannot receive: " + Runs.describe(e), Refusals.refused(e));
        }
    }

    private void broke(Monitor m, String error) {
        broke(m, error, Refusals.refused(error));
    }

    /**
     * Receiving ended, or never began. What was counted stays, and the next attempt is booked: unless the broker
     * refused the credentials. Trying those again changes nothing and, repeated, locks the account, so the monitor
     * stops, saying so, and stays listed until someone who has mended the connection starts it again.
     */
    private void broke(Monitor m, String error, boolean refused) {
        TransportType.Subscription s;
        synchronized (m) {
            if (m.stopped) return;
            m.breaks++;
            s = m.subscription;
            m.subscription = null;
            m.error = error;
            if (refused) {
                m.stopped = true;
                m.state = "stopped";
                m.retryAt = null;
                m.error = "The credentials were refused. " + error + (error.matches(".*[.!?]$") ? "" : ".") + " " + REFUSED;
            } else {
                m.state = "down";
            }
            long seconds = RETRY_SECONDS[Math.min(m.attempts++, RETRY_SECONDS.length - 1)];
            if (!refused) {
                m.retryAt = Instant.now().plusMillis(seconds * retryUnitMillis);
                if (!scheduler.isShutdown()) scheduler.schedule(() -> attempt(m), seconds * retryUnitMillis, TimeUnit.MILLISECONDS);
            }
        }
        if (s == null) return;
        if (scheduler.isShutdown()) close(s);
        else scheduler.execute(() -> close(s));   // not on the broker's own thread, which is the one telling us
    }

    public List<View> list() {
        synchronized (monitors) {
            return monitors.stream().map(Monitor::view).toList();
        }
    }

    public View reset(String id) {
        Monitor m = find(id, true);
        m.observer.reset();
        return m.view();
    }

    /** Keeps the monitor in the configuration file, so it starts with the console from now on. */
    public View save(String id) {
        if (store == null) throw new IllegalArgumentException("There is no configuration file to save a monitor in");
        Monitor m = find(id, true);
        m.saved = true;
        persist();
        return m.view();
    }

    /**
     * Takes it out of the configuration file. A monitor that is watching goes on watching until it is stopped or
     * msgforge is; one that was stopped has nothing left to be listed for, and goes.
     */
    public View forget(String id) {
        Monitor m = find(id, true);
        m.saved = false;
        View last = m.view();
        if (m.stopped) {
            synchronized (monitors) {
                monitors.remove(m);
            }
        }
        persist();
        return last;
    }

    /**
     * Stops the monitor listening. One that is not saved is gone with that. A saved one stays, listed as stopped with
     * what it counted: it is still in the configuration file, starts again with the next start of msgforge, and can
     * be started again before that with {@link #restart}.
     */
    public void stop(String id) {
        Monitor m = find(id, true);
        if (!m.saved) {
            synchronized (monitors) {
                monitors.remove(m);
            }
        }
        end(m);
    }

    /** Starts a stopped monitor again. One that cannot listen now is listed as down and tries again, like any other. */
    public View restart(String id) {
        Monitor m = find(id, true);
        if (!m.mendable) throw new IllegalArgumentException(m.error);
        synchronized (m) {
            if (!m.stopped) return m.view();
            m.stopped = false;
            m.attempts = 0;
            m.state = "starting";
            m.error = null;
        }
        attempt(m);
        return m.view();
    }

    private void persist() {
        if (store == null) return;
        synchronized (monitors) {
            store.write("monitors", monitors.stream().filter(m -> m.saved).map(m -> new Saved(m.id, m.name, m.connectionId, m.destination, m.buckets, m.byHand ? false : null)).toList());
        }
    }

    private Monitor find(String id, boolean required) {
        synchronized (monitors) {
            Monitor found = monitors.stream().filter(m -> m.id.equals(id)).findFirst().orElse(null);
            if (found == null && required) throw new NoSuchElementException("no monitor " + id);
            return found;
        }
    }

    private static void end(Monitor m) {
        TransportType.Subscription s;
        synchronized (m) {
            m.stopped = true;
            m.breaks++;   // a connect that is on its way finds out it is no longer wanted
            s = m.subscription;
            m.subscription = null;
            m.retryAt = null;
            if (m.mendable) {
                m.state = "stopped";
                m.error = null;
            }
        }
        close(s);
    }

    /** A broker that does not answer the close is not waited for longer than a run would wait. */
    private static void close(TransportType.Subscription s) {
        if (s == null) return;
        try {
            Runs.within(15, s::close, () -> false);
        } catch (Throwable ignored) {
            // nothing left to release
        }
    }

    /** On shutdown the monitors stop listening. The saved ones stay in the file, for the next start. */
    @PreDestroy
    void stopAll() {
        scheduler.shutdownNow();
        List<Monitor> all;
        synchronized (monitors) {
            all = new ArrayList<>(monitors);
            monitors.clear();
        }
        all.forEach(Monitors::end);
    }
}
