package io.msgforge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Saved connections (where messages go) and their destinations. Any setting may be written as ${MSGFORGE_NAME}; it is read
 * from the environment when the connection opens, which keeps passwords out of the file.
 *
 * Nothing that leaves this class carries a secret: views leave secret settings out, and every error from a
 * connection type passes through {@link Guard}, which puts back the stored text where a resolved value appears.
 */
@Component
public class Connections {

    private static final Pattern ENV = Pattern.compile("\\$\\{(\\w+)}");

    public record Connection(String id, String name, String type, Map<String, String> settings, List<String> destinations) {}

    /** What the API returns: secret settings are blanked and listed in secretsSet instead. */
    /**
     * title is the name with any ${MSGFORGE_NAME} in it filled in (one file can then call its connection after the
     * environment it runs in), and queues gives, for each destination written with a variable, the name it stands
     * for. Both are for showing; requests use what is written. Settings are never filled in for a view.
     */
    public record View(String id, String name, String type, String typeLabel, String summary,
                       Map<String, String> settings, List<String> secretsSet, List<String> destinations,
                       String title, Map<String, String> queues) {}

    /** How the last check of a saved connection went. */
    public record Status(boolean ok, String error, java.time.Instant at) {}

    private final Map<String, Status> statuses = new java.util.concurrent.ConcurrentHashMap<>();

    /** Opens the saved connection and asks the broker whether it is there. Never throws for a broker's answer: the answer is the result. */
    public Status check(String id) {
        Connection c;
        synchronized (this) {
            c = find(id);
        }
        Status status;
        try (TransportType.Sender sender = open(c)) {
            sender.check();
            status = new Status(true, null, java.time.Instant.now());
        } catch (Exception e) {
            status = new Status(false, Runs.describe(e), java.time.Instant.now());
        }
        statuses.put(id, status);
        return status;
    }

    /** The last check of each connection that has had one since msgforge started. */
    public synchronized Map<String, Status> statuses() {
        Map<String, Status> known = new LinkedHashMap<>();
        for (Connection c : connections) if (statuses.containsKey(c.id())) known.put(c.id(), statuses.get(c.id()));
        return known;
    }

    /** As written when a variable in it may not be read or is not set: a view never fails over a name. */
    private String shown(String written) {
        if (!written.contains("${")) return written;
        try {
            return resolve(written);
        } catch (RuntimeException e) {
            return written;
        }
    }

    private final ConfigStore store;
    private final List<Pattern> envAllow;
    private final Map<String, TransportType> types = new LinkedHashMap<>();
    private final List<Connection> connections = new ArrayList<>();

    public Connections(ConfigStore store, List<TransportType> types) {
        this(store, types, new String[0]);
    }

    @Autowired
    public Connections(ConfigStore store, List<TransportType> types, @Value("${msgforge.env-allow:}") String[] envAllow) {
        this.store = store;
        // MQ_* becomes a pattern in which * is the only special character
        this.envAllow = Arrays.stream(envAllow).map(String::trim).filter(n -> !n.isEmpty())
                .map(n -> Pattern.compile(Arrays.stream(n.split("\\*", -1)).map(Pattern::quote).collect(Collectors.joining(".*")))).toList();
        types.forEach(t -> this.types.put(t.id(), t));
        List<Connection> saved = store.read("connections", new TypeReference<List<Connection>>() {});
        if (saved != null) {
            for (Connection c : saved) {
                // A hand-edited file may leave things out; fill them in so nothing later meets a null.
                String id = c.id() == null || c.id().isBlank() ? UUID.randomUUID().toString().substring(0, 8) : c.id();
                Map<String, String> settings = new LinkedHashMap<>();
                if (c.settings() != null) c.settings().forEach((key, value) -> { if (value != null) settings.put(key, value); });
                TransportType type = this.types.get(c.type());
                connections.add(new Connection(id, c.name() == null || c.name().isBlank() ? id : c.name(), String.valueOf(c.type()),
                        type == null ? settings : type.migrate(settings),
                        c.destinations() == null ? List.of() : c.destinations().stream().filter(Objects::nonNull).toList()));
            }
        } else {
            connections.add(new Connection("dry-run", "Dry run", "dry-run", Map.of(), List.of("DEMO.QUEUE")));
            if (this.types.containsKey("ibm-mq")) {
                connections.add(new Connection("local-mq", "Local IBM MQ", "ibm-mq", Map.of(
                        "hosts", "localhost(1414)", "queueManager", "QM1", "channel", "DEV.APP.SVRCONN", "user", "app"),
                        List.of("DEV.QUEUE.1", "DEV.QUEUE.2", "DEV.QUEUE.3")));
            }
        }
    }

    public List<TransportType> types() {
        return List.copyOf(types.values());
    }

    public synchronized List<View> list() {
        return connections.stream().map(this::view).toList();
    }

    public synchronized View get(String id) {
        return view(find(id));
    }

    /** Creates (blank id) or replaces a connection. */
    public synchronized View save(Connection incoming) {
        Connection c = clean(incoming);
        int at = c.id() == null ? -1 : indexOf(c.id());
        if (at < 0) {
            c = new Connection(UUID.randomUUID().toString().substring(0, 8), c.name(), c.type(), c.settings(), c.destinations());
            connections.add(c);
        } else {
            connections.set(at, c);
        }
        persist();
        return view(c);
    }

    public synchronized void delete(String id) {
        statuses.remove(id);
        connections.remove(find(id));
        persist();
    }

    /** Opens the connection as typed in the form (saved or not) and reports whether the broker accepts it. */
    public void test(Connection incoming) throws Exception {
        Connection c;
        synchronized (this) {
            c = clean(incoming);
        }
        try (TransportType.Sender sender = open(c)) {
            sender.check();
        }
    }

    /**
     * Opens a saved connection as it is now. The caller owns the sender and closes it. Nothing is shared or cached,
     * so editing or deleting the connection afterwards does not reach a sender that is already open.
     */
    public TransportType.Sender open(String id) {
        Connection c;
        synchronized (this) {
            c = find(id);
        }
        return open(c);
    }

    public synchronized boolean canReceive(String id) {
        TransportType type = types.get(find(id).type());
        return type != null && type.canReceive();
    }

    /**
     * Starts receiving from a destination on a saved connection. The caller closes the subscription.
     * onError gets a receiver's failure as text that is safe to show.
     */
    public TransportType.Subscription receive(String id, String destination, Consumer<TransportType.Arrival> onArrival, Consumer<String> onError) {
        Connection c;
        synchronized (this) {
            c = find(id);
        }
        TransportType type = type(c);
        Map<String, String> settings = resolve(c.settings());
        Guard guard = guard(c, type, settings);
        // A thread the type starts for receiving inherits the context loader the guard sets.
        // an error from the receiver's own thread gets the same treatment as one thrown from a call
        TransportType.Subscription subscription = guard.call(() -> type.receive(settings, destination, onArrival, e -> onError.accept(guard.describe(e))));
        return new TransportType.Subscription() {
            @Override
            public void check() {
                guard.call(() -> {
                    subscription.check();
                    return null;
                });
            }

            @Override
            public void close() {
                guard.call(() -> {
                    subscription.close();
                    return null;
                });
            }
        };
    }

    /**
     * One subscription per connection and destination, however many listen: receiving takes messages off a queue, so a
     * run and a monitor that each subscribed would split the messages between them, and both would be wrong. Here the
     * first listener opens the subscription, every later one joins it, each gets every arrival, and the subscription
     * closes when the last one has left. So a run that ends does not close a monitor's subscription, and a monitor
     * that is stopped does not cut off a run.
     *
     * When receiving breaks, every listener's onError is told and the subscription takes no new listeners: the next
     * one to ask opens a new one. A listener that joins rides on the connection's settings as they were when the
     * subscription was opened.
     */
    // ponytail: a new listener that arrives while the last one is still closing opens a second subscription beside
    // the one going away, and on a queue that one could take a message in its last moment. Hold newcomers back until
    // the close has returned if that ever shows.
    public TransportType.Subscription share(String id, String destination, Consumer<TransportType.Arrival> onArrival, Consumer<String> onError) {
        String key = id + "\n" + destination;
        Listener mine = new Listener(onArrival, onError);
        while (true) {
            Tap tap;
            synchronized (taps) {
                tap = taps.computeIfAbsent(key, k -> new Tap());
            }
            Tap joined = tap;
            synchronized (tap) {
                if (tap.over) continue;   // it broke or emptied while this listener was on its way: start another
                tap.listeners.add(mine);   // before the subscription opens, so not one arrival goes unheard
                if (!tap.opened) {
                    tap.opened = true;
                    try {
                        tap.subscription = receive(id, destination, arrival -> {
                            DELIVERING.set(new Delivery());
                            try {
                                for (Listener l : joined.listeners) {
                                    try {
                                        l.onArrival().accept(arrival);
                                    } catch (RuntimeException ignored) {
                                        // one listener's trouble with an arrival is not the others'
                                    }
                                }
                            } finally {
                                DELIVERING.remove();
                            }
                        }, error -> {
                            retire(key, joined);
                            for (Listener l : joined.listeners) l.onError().accept(error);
                        });
                    } catch (RuntimeException e) {
                        tap.listeners.remove(mine);
                        retire(key, tap);
                        throw e;
                    }
                }
            }
            return new TransportType.Subscription() {
                private boolean left;

                @Override
                public void check() throws Exception {
                    TransportType.Subscription s = joined.subscription;
                    if (s != null) s.check();
                }

                @Override
                public void close() {
                    TransportType.Subscription last = null;
                    synchronized (joined) {
                        if (left) return;
                        left = true;
                        joined.listeners.remove(mine);
                        if (joined.listeners.isEmpty() && !joined.closed) {
                            joined.closed = true;
                            retire(key, joined);
                            last = joined.subscription;
                        }
                    }
                    if (last != null) last.close();
                }
            };
        }
    }

    /**
     * One arrival on its way through the listeners of a shared subscription. A monitor keeps a line about it, and a run
     * may recognise it as one of its messages, at once or a moment later; whichever comes first, the line learns
     * which run it was. That is how a reject queue's list can say "this one was ours, and expected".
     */
    static final class Delivery {
        private final List<Observer.Entry> kept = new ArrayList<>(2);
        private String run, outcome;
        private long message;

        synchronized void keep(Observer.Entry entry) {
            kept.add(entry);
            if (run != null) entry.matched(run, message, outcome);
        }

        synchronized void claim(String run, long message, String outcome) {
            if (this.run != null) return;   // the first run to recognise it has it
            this.run = run;
            this.message = message;
            this.outcome = outcome;
            for (Observer.Entry entry : kept) entry.matched(run, message, outcome);
        }
    }

    private static final ThreadLocal<Delivery> DELIVERING = new ThreadLocal<>();

    /** The arrival this thread is handing to listeners now, or null outside a shared subscription. */
    static Delivery delivering() {
        return DELIVERING.get();
    }

    /** No new listener joins this subscription; the ones it has keep it until they leave. */
    private void retire(String key, Tap tap) {
        tap.over = true;
        synchronized (taps) {
            taps.remove(key, tap);
        }
    }

    private record Listener(Consumer<TransportType.Arrival> onArrival, Consumer<String> onError) {}

    private static final class Tap {
        final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile TransportType.Subscription subscription;
        volatile boolean over;
        boolean opened, closed;
    }

    private final Map<String, Tap> taps = new java.util.HashMap<>();

    /** {@link #receive} for the bodies alone. */
    public TransportType.Subscription subscribe(String id, String destination, Consumer<String> onMessage, Consumer<String> onError) {
        return receive(id, destination, arrival -> onMessage.accept(arrival.body()), onError);
    }

    private TransportType.Sender open(Connection c) {
        TransportType type = type(c);
        Map<String, String> settings = resolve(c.settings());
        Guard guard = guard(c, type, settings);
        TransportType.Sender sender = guard.call(() -> type.open(settings));
        return new TransportType.Sender() {
            @Override
            public void send(String destination, String body, Map<String, String> headers) {
                guard.call(() -> {
                    sender.send(destination, body, headers);
                    return null;
                });
            }

            @Override
            public String sendForId(String destination, String body, Map<String, String> headers) {
                return guard.call(() -> sender.sendForId(destination, body, headers));
            }

            @Override
            public void check() {
                guard.call(() -> {
                    sender.check();
                    return null;
                });
            }

            @Override
            public void close() {
                guard.call(() -> {
                    sender.close();
                    return null;
                });
            }
        };
    }

    private TransportType type(Connection c) {
        TransportType type = types.get(c.type());
        if (type == null) {
            throw new IllegalArgumentException(c.name() + " needs the connection type \"" + c.type()
                    + "\", which is not installed. Put its plugin jar in the plugins folder and restart.");
        }
        return type;
    }

    /**
     * Every call into a connection type goes through here, for two reasons.
     * The type's own class loader is the thread's context loader meanwhile: libraries inside a plugin jar
     * (Kafka, for one) look classes up through it and would not find them otherwise.
     * And whatever the call throws is replaced by an error whose text has no value from the environment and no
     * stored secret in it, so no type, plugin or broker can hand one back through an error message.
     * The original exception is dropped, not chained or logged, because its message is the thing being kept in.
     */
    private record Guard(ClassLoader loader, Pattern hidden, Map<String, String> shown) {

        <T> T call(Callable<T> work) {
            Thread thread = Thread.currentThread();
            ClassLoader before = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                return work.call();
            } catch (Throwable e) {
                throw new IllegalStateException(describe(e));
            } finally {
                thread.setContextClassLoader(before);
            }
        }

        /** The error's text with every hidden value replaced, in one pass so a replacement is never itself rewritten. */
        String describe(Throwable e) {
            String text = Runs.describe(e);
            if (hidden == null) return text;
            return hidden.matcher(text).replaceAll(found -> Matcher.quoteReplacement(shown.getOrDefault(found.group().toLowerCase(Locale.ROOT), "***")));
        }
    }

    /**
     * What must not appear in an error, and what to show instead: the stored text (such as ${MSGFORGE_MQ_HOST})
     * for anything read from the environment, *** for a secret that was typed in. The allow-list in resolve()
     * is what decides which variables can be reached at all; this is the second line, for the ones that can.
     */
    // ponytail: catches a value whole, trimmed, in its comma- or space-separated pieces, and in any letter case.
    // A type that encodes a value (base64, URL-escaping) before quoting it is not covered; a type must not quote settings.
    private static Guard guard(Connection c, TransportType type, Map<String, String> resolved) {
        Map<String, String> shown = new LinkedHashMap<>();
        c.settings().forEach((key, stored) -> {
            String value = resolved.get(key);
            if (!value.equals(stored)) {
                hide(shown, value, stored);
                Matcher m = ENV.matcher(stored);
                while (m.find()) hide(shown, System.getenv(m.group(1)), m.group());
            }
        });
        for (TransportType.Field f : type.fields()) {
            if (f.secret()) hide(shown, resolved.get(f.key()), "***");
        }
        // longest first, so a value that contains another is replaced whole
        String any = shown.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).collect(Collectors.joining("|"));
        return new Guard(type.getClass().getClassLoader(),
                any.isEmpty() ? null : Pattern.compile(any, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), shown);
    }

    private static void hide(Map<String, String> shown, String value, String as) {
        if (value == null || value.isBlank()) return;
        shown.putIfAbsent(value.toLowerCase(Locale.ROOT), as);
        shown.putIfAbsent(value.strip().toLowerCase(Locale.ROOT), as);
        // and in pieces: types split host lists on commas and drop spaces before they complain about one piece
        for (String piece : value.split("[,\\s]+")) {
            if (!piece.isEmpty()) shown.putIfAbsent(piece.toLowerCase(Locale.ROOT), as);
        }
    }

    private Connection clean(Connection in) {
        if (in.name() == null || in.name().isBlank()) throw new IllegalArgumentException("Name is required");
        TransportType type = types.get(in.type());
        if (type == null) throw new IllegalArgumentException("Unknown connection type: " + in.type());
        int at = in.id() == null || in.id().isBlank() ? -1 : indexOf(in.id());
        // A stored secret never follows the connection to another type.
        Map<String, String> stored = at < 0 || !connections.get(at).type().equals(in.type()) ? Map.of() : connections.get(at).settings();
        Map<String, String> settings = new LinkedHashMap<>();
        List<TransportType.Field> kept = new ArrayList<>();
        boolean changed = false;
        for (TransportType.Field f : type.fields()) {
            // A secret that is left out keeps its stored value; one sent blank is cleared.
            boolean given = in.settings() != null && in.settings().containsKey(f.key());
            String value = given ? in.settings().get(f.key()) : null;
            value = value == null ? "" : value.trim();
            if (!given && f.secret() && stored.containsKey(f.key())) {
                value = stored.get(f.key());
                kept.add(f);
            }
            if (!f.secret() && !value.equals(stored.getOrDefault(f.key(), ""))) changed = true;
            if (value.isEmpty() && f.required()) throw new IllegalArgumentException(f.label() + " is required");
            if (!value.isEmpty()) settings.put(f.key(), value);
        }
        // Otherwise an edit could point the connection somewhere else and have the stored password sent there.
        if (changed && !kept.isEmpty()) {
            throw new IllegalArgumentException("Re-enter " + kept.get(0).label() + ": the connection's settings changed");
        }
        LinkedHashSet<String> destinations = new LinkedHashSet<>();
        if (in.destinations() != null) in.destinations().stream().filter(d -> d != null && !d.isBlank()).map(String::trim).forEach(destinations::add);
        return new Connection(at < 0 ? null : in.id(), in.name().trim(), in.type(), settings, List.copyOf(destinations));
    }

    /**
     * Replaces ${NAME} with that environment variable, for names that are allowed: those starting with MSGFORGE_
     * and those matching msgforge.env-allow. A connection can send a setting to any address its author likes, so
     * a variable that may be named in one may be read by whoever can reach the console; the rest of the
     * environment is not on offer. A name that is not allowed is refused before its value is looked at.
     * Fails naming the variable, never a value.
     */
    Map<String, String> resolve(Map<String, String> settings) {
        Map<String, String> out = new LinkedHashMap<>();
        settings.forEach((key, value) -> out.put(key, resolve(value)));
        return out;
    }

    /**
     * One text with its ${NAME}s filled in, under the same rule. A destination in a scenario, a watch or a monitor goes
     * through here, so one saved file serves several environments. Unlike a setting, a destination is shown and
     * may be quoted in a broker's error: it is a name, and a variable used for one should hold nothing secret.
     */
    public String resolve(String value) {
        Matcher m = ENV.matcher(value);
        return m.replaceAll(r -> {
            String name = r.group(1);
            if (!name.startsWith("MSGFORGE_") && envAllow.stream().noneMatch(p -> p.matcher(name).matches())) {
                throw new IllegalArgumentException("Environment variable " + name + " may not be used in a connection. "
                        + "Give it a name that starts with MSGFORGE_, or start msgforge with MSGFORGE_ENV_ALLOW=" + name
                        + " (a comma-separated list of names; * stands for any characters).");
            }
            String env = System.getenv(name);
            if (env == null) throw new IllegalArgumentException("Environment variable " + name + " is not set");
            return Matcher.quoteReplacement(env);
        });
    }

    private View view(Connection c) {
        TransportType type = types.get(c.type());
        // Without the type there is no telling which settings are secret, so none are shown.
        Map<String, String> queues = new LinkedHashMap<>();
        for (String d : c.destinations()) if (!shown(d).equals(d)) queues.put(d, shown(d));
        if (type == null) return new View(c.id(), c.name(), c.type(), c.type() + " (not installed)", "", Map.of(), List.of(), c.destinations(), shown(c.name()), queues);
        // Only what the type declares, and of that only what is not secret. A setting the type does not know
        // (a misspelt key in a hand-written file, a secret an older plugin version called something else) stays here.
        Map<String, String> shown = new LinkedHashMap<>();
        List<String> secretsSet = new ArrayList<>();
        for (TransportType.Field f : type.fields()) {
            String value = c.settings().get(f.key());
            if (value == null) continue;
            if (f.secret()) secretsSet.add(f.key());
            else shown.put(f.key(), value);
        }
        return new View(c.id(), c.name(), c.type(), type.label(), type.summary(c.settings()), shown, secretsSet, c.destinations(), shown(c.name()), queues);
    }

    private Connection find(String id) {
        int at = indexOf(id);
        if (at < 0) throw new NoSuchElementException("no connection " + id);
        return connections.get(at);
    }

    private int indexOf(String id) {
        for (int i = 0; i < connections.size(); i++) if (connections.get(i).id().equals(id)) return i;
        return -1;
    }

    private void persist() {
        store.write("connections", connections);
    }
}
