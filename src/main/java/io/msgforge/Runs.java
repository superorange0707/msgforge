package io.msgforge;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Starts runs (send N messages at a rate) and remembers the latest runs and messages for the console.
 * A run can also verify: it listens where each part's messages should arrive and reports what did.
 */
@Component
public class Runs {

    private static final int MAX_COUNT = 1_000_000, MAX_TARGETS = 10, MAX_PARTS = 20, MAX_WEIGHT = 1000, KEPT_RUNS = 20, KEPT_MESSAGES = 50;
    // ponytail: each arrival is matched by scanning every message still awaited, so verified runs stop at 20,000.
    // To go higher, pull the token out of the arrival (a position or pattern per template) and look it up in a map.
    private static final int MAX_VERIFIED = 20_000;
    private static final double MIN_RATE = 0.001;
    // ponytail: a length, not a proof. Eight characters keeps counters and small numbers out; it does not make a token unique in a body.
    private static final int MIN_TOKEN = 8;

    /** One place a run sends to: a saved connection and a destination on it. */
    public record Target(String connection, String destination) {}

    /**
     * One kind of message in a run. weight is its share: parts weighted 7 and 3 give exactly 70% and 30%.
     * body is optional: when set it is used instead of the saved template (unsaved edits from the console).
     * expect is optional: where this part's messages should arrive once the system under test has handled them.
     */
    public record Part(String pack, String template, String body, int weight, Map<String, String> vars, Target expect) {}

    /**
     * What to send, where and how fast.
     * parts and targets describe the general case. For one template to one place, the shorthand fields
     * (pack, template, body, vars, connection, destination, expect) do the same job.
     * mode "spread" (default) hands each message to the next target in turn; "all" sends every message to every target.
     * headers are rendered per message with the same placeholders, and share the body's {{name=...}} bindings.
     * When a part has an expectation, each message is recognised on arrival by a token the arriving body must contain:
     * match rendered like a header, or without match the first {{uuid}} or {{id}} value in the body.
     * timeoutSeconds (default 10) is how long to wait for arrivals after the last send.
     */
    public record Request(String label, List<Part> parts, List<Target> targets, String mode, int count, double rate,
                          Map<String, String> headers, String pack, String template, String body, Map<String, String> vars,
                          String connection, String destination, Target expect, Integer timeoutSeconds, String match) {

        public static Request single(String pack, String template, String body, String connection, String destination,
                                     int count, double rate, Map<String, String> vars) {
            return new Request(null, null, null, null, count, rate, null, pack, template, body, vars, connection, destination, null, null, null);
        }
    }

    public enum Status { RUNNING, VERIFYING, DONE, FAILED, CANCELLED }

    public record TargetView(String connectionId, String connection, String destination) {}

    /**
     * What became of one part's messages. arrived is on the expected destination, misrouted on another watched one,
     * missing nowhere (yet). The latencies are from send to arrival where expected, null when nothing arrived there.
     */
    public record Check(String template, String expected, int sent, int arrived, int misrouted, int missing, Long medianMs, Long maxMs) {}

    /**
     * sent counts messages, not deliveries: in "all" mode one message reaches every target.
     * verdict is null without expectations and unless the run ended DONE; then PASS means every message arrived
     * where expected. A failed or cancelled run has no verdict and no checks.
     */
    public record View(String id, String label, List<String> templates, List<TargetView> targets, String mode,
                       int count, int sent, Status status, String error, Instant startedAt, Instant finishedAt,
                       List<Check> checks, String verdict) {}

    public record Sent(String runId, int n, int target, String connection, String destination, String template,
                       Map<String, String> headers, String body, Instant at) {}

    /** A part with its template text and pack fields loaded. watch is the index of its expected destination in the run's watches, or -1. */
    private record Loaded(String template, String text, Map<String, String> fields, Map<String, String> vars, int weight, int watch) {}

    private record Rendered(String body, Map<String, String> headers, String token) {}

    /** A sent message that should arrive somewhere. */
    private static final class Awaited {
        final String token;
        final int part;
        final long sentAt = System.nanoTime();
        int watch = -1;   // where it arrived
        long latency;

        Awaited(String token, int part) {
            this.token = token;
            this.part = part;
        }
    }

    private static final class Run {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final Instant startedAt = Instant.now();
        final String label;
        final List<Loaded> parts;
        final List<TargetView> targets;
        final boolean all;
        final int count;
        final double rate;
        final Map<String, String> headers;
        final List<TargetView> watches;
        final String match;
        final int timeoutSeconds;
        // Both guarded by awaited: arrivals come in on the transports' threads. pending is keyed by token.
        final List<Awaited> awaited = new ArrayList<>();
        final Map<String, Awaited> pending = new LinkedHashMap<>();
        volatile String broken;   // a receiver failed: the run cannot tell what arrived any more
        final AtomicLong seq = new AtomicLong();   // {{seq}} counts within the run
        volatile Thread thread;
        volatile int sent;
        volatile Status status = Status.RUNNING;
        volatile String error;
        volatile Instant finishedAt;
        volatile boolean cancelled;

        Run(String label, List<Loaded> parts, List<TargetView> targets, boolean all, int count, double rate, Map<String, String> headers,
            List<TargetView> watches, String match, int timeoutSeconds) {
            this.label = label;
            this.parts = parts;
            this.targets = targets;
            this.all = all;
            this.count = count;
            this.rate = rate;
            this.headers = headers;
            this.watches = watches;
            this.match = match;
            this.timeoutSeconds = timeoutSeconds;
        }

        /**
         * A message turned up on a watched destination. The first arrival of a message decides; later copies are ignored.
         * Of the awaited tokens the body contains, the longest is the message: with tokens 1 and 10 awaited, a body
         * with 10 in it contains both, and is message 10.
         */
        void arrived(int watch, String body) {
            long now = System.nanoTime();
            synchronized (awaited) {
                Awaited found = null;
                for (Awaited a : pending.values()) {
                    if (body.contains(a.token) && (found == null || a.token.length() > found.token.length())) found = a;
                }
                if (found == null) return;
                found.watch = watch;
                found.latency = now - found.sentAt;
                pending.remove(found.token);
                if (pending.isEmpty()) awaited.notifyAll();
            }
        }

        boolean stopped() {
            return cancelled || broken != null;
        }

        List<Check> checks() {
            List<Check> out = new ArrayList<>();
            synchronized (awaited) {
                for (int p = 0; p < parts.size(); p++) {
                    int expected = parts.get(p).watch();
                    if (expected < 0) continue;
                    int sent = 0, misrouted = 0, missing = 0;
                    List<Long> latencies = new ArrayList<>();
                    for (Awaited a : awaited) {
                        if (a.part != p) continue;
                        sent++;
                        if (a.watch == expected) latencies.add(a.latency / 1_000_000);
                        else if (a.watch >= 0) misrouted++;
                        else missing++;
                    }
                    Collections.sort(latencies);
                    int n = latencies.size();
                    TargetView t = watches.get(expected);
                    out.add(new Check(parts.get(p).template(), t.connection() + " / " + t.destination(), sent, n, misrouted, missing,
                            n == 0 ? null : latencies.get(n / 2), n == 0 ? null : latencies.get(n - 1)));
                }
            }
            return out;
        }

        View view() {
            Status status = this.status;   // read first: once it says the run has ended, the checks no longer change
            // A run that failed or was cancelled did not finish the experiment: it has a status, and no result to judge.
            List<Check> checks = status == Status.FAILED || status == Status.CANCELLED ? List.of() : checks();
            String verdict = status != Status.DONE || watches.isEmpty() ? null
                    : checks.stream().allMatch(c -> c.arrived() == c.sent()) ? "PASS" : "FAIL";
            return new View(id, label, parts.stream().map(Loaded::template).toList(), targets, all ? "all" : "spread",
                    count, sent, status, error, startedAt, finishedAt, checks, verdict);
        }
    }

    private final Packs packs;
    private final TemplateEngine engine;
    private final Connections connections;
    private final Deque<Run> runs = new ArrayDeque<>();
    // How long a broker has to answer the question "are you there" or a close. Kafka's own check can take 11 of them.
    int brokerAnswerSeconds = 15;
    private final Deque<Sent> messages = new ArrayDeque<>();
    // ponytail: one thread per run and no cap on concurrent runs; add a bounded pool if this is ever shared.
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "msgforge-run");
        t.setDaemon(true);
        return t;
    });

    public Runs(Packs packs, TemplateEngine engine, Connections connections) {
        this.packs = packs;
        this.engine = engine;
        this.connections = connections;
    }

    /**
     * The checks that need no pack and no connection. A run makes them first; a scenario makes them when it is
     * saved, so a number that could never run is refused then, not on the day it is used.
     */
    static void check(int count, double rate, List<Part> parts, List<Target> targets, Map<String, String> headers, Integer timeoutSeconds) {
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("Messages must be 1 to " + MAX_COUNT);
        if (!(rate >= 0) || Double.isInfinite(rate)) throw new IllegalArgumentException("Per second must be 0 or more");
        // below this the pause between two messages no longer fits the clock arithmetic, and nobody waits 17 minutes for a message
        if (rate > 0 && rate < MIN_RATE) throw new IllegalArgumentException("Per second must be 0 (as fast as possible) or at least " + MIN_RATE);
        if (targets.stream().anyMatch(t -> t == null)) throw new IllegalArgumentException("A target is empty: each needs a connection and a destination");
        if (parts.size() > MAX_PARTS) throw new IllegalArgumentException("At most " + MAX_PARTS + " templates");
        for (Part p : parts) {
            if (p == null || p.template() == null || p.template().isBlank()) throw new IllegalArgumentException("Template is required");
            if (p.weight() < 0 || p.weight() > MAX_WEIGHT) throw new IllegalArgumentException("Weight must be 1 to " + MAX_WEIGHT);
            noNulls("Override", p.vars());
        }
        noNulls("Header", headers);
        if (timeoutSeconds != null && (timeoutSeconds < 1 || timeoutSeconds > 600)) throw new IllegalArgumentException("Timeout must be 1 to 600 seconds");
        if (count > MAX_VERIFIED && parts.stream().anyMatch(p -> p.expect() != null)) {
            throw new IllegalArgumentException("A run that verifies arrivals can send at most " + MAX_VERIFIED + " messages");
        }
    }

    /** JSON can say "a": null where a text belongs. */
    static void noNulls(String what, Map<String, String> values) {
        if (values != null) values.forEach((name, value) -> {
            if (value == null) throw new IllegalArgumentException(what + " " + name + " has no value");
        });
    }

    public View start(Request req) {
        List<Part> wanted = req.parts() != null && !req.parts().isEmpty() ? req.parts()
                : List.of(new Part(req.pack(), req.template(), req.body(), 1, null, req.expect()));
        List<Target> asked = req.targets() != null && !req.targets().isEmpty() ? req.targets()
                : List.of(new Target(req.connection(), req.destination()));
        check(req.count(), req.rate(), wanted, asked, req.headers(), req.timeoutSeconds());
        noNulls("Override", req.vars());
        boolean all = "all".equals(req.mode());
        if (req.mode() != null && !all && !"spread".equals(req.mode())) throw new IllegalArgumentException("Mode is spread or all");

        if (asked.size() > MAX_TARGETS) throw new IllegalArgumentException("At most " + MAX_TARGETS + " targets");
        List<TargetView> targets = new ArrayList<>();
        for (Target t : asked) {
            if (t.connection() == null || t.connection().isBlank()) throw new IllegalArgumentException("Connection is required");
            if (t.destination() == null || t.destination().isBlank()) throw new IllegalArgumentException("Destination is required");
            targets.add(new TargetView(t.connection(), connections.get(t.connection()).name(), t.destination().trim()));
        }

        Map<String, String> headers = req.headers() == null ? Map.of() : Map.copyOf(req.headers());
        String match = req.match() == null || req.match().isBlank() ? null : req.match().trim();
        int timeout = req.timeoutSeconds() == null ? 10 : req.timeoutSeconds();
        List<TargetView> watches = new ArrayList<>();
        List<Loaded> parts = new ArrayList<>();
        AtomicLong probe = new AtomicLong();   // {{seq}} for the trial renders below; the run's own starts at 1
        for (Part p : wanted) {
            Packs.Pack pack = packs.get(p.pack());
            Map<String, String> vars = new LinkedHashMap<>();
            if (req.vars() != null) vars.putAll(req.vars());
            if (p.vars() != null) vars.putAll(p.vars());
            int watch = -1;
            if (p.expect() != null) {
                Target e = p.expect();
                if (e.connection() == null || e.connection().isBlank()) throw new IllegalArgumentException("The expectation needs a connection");
                if (e.destination() == null || e.destination().isBlank()) throw new IllegalArgumentException("The expectation needs a destination");
                Connections.View c = connections.get(e.connection());
                if (!connections.canReceive(c.id())) {
                    throw new IllegalArgumentException(c.name() + " is a " + c.typeLabel() + " connection, which cannot receive. Expect the messages on another connection.");
                }
                TargetView where = new TargetView(c.id(), c.name(), e.destination().trim());
                watch = watches.indexOf(where);
                if (watch < 0) {
                    watches.add(where);
                    watch = watches.size() - 1;
                }
            }
            Loaded loaded = new Loaded(p.template(), p.body() != null ? p.body() : packs.read(p.pack(), p.template()),
                    pack.fields(), vars, Math.max(1, p.weight()), watch);
            // fail now on a bad placeholder or a message nothing can match, not on message 1 of a run
            String token = render(loaded, headers, match, probe).token();
            // A match that renders the same for two messages (a constant, say) would "recognise" any arrival as any message.
            if (match != null && watch >= 0 && token.equals(render(loaded, headers, match, probe).token())) {
                throw new IllegalArgumentException("The match expression gives every message of " + p.template() + " the same text (" + token
                        + "). It must differ per message: repeat a value the body generates, such as {{ref}} with {{ref=uuid}} in the template.");
            }
            // A short token turns up in a message by accident: the 1 of message 1 is in "11:13" and in every amount with a 1 in it.
            if (watch >= 0 && token.length() < MIN_TOKEN) {
                throw new IllegalArgumentException(p.template() + " would be recognised by \"" + token + "\", which is too short: other text in a message can "
                        + "contain it, and the verdict could be wrong. Match on a value of at least " + MIN_TOKEN + " characters, such as {{uuid}} or {{id}}.");
            }
            parts.add(loaded);
        }

        String label = req.label() != null && !req.label().isBlank() ? req.label().trim()
                : parts.size() == 1 ? parts.get(0).template() : parts.size() + " templates";
        // Copy to all puts every message on every target. If two of those are watched, a message expected on one also
        // arrives on the other, and whichever arrival comes first would decide between "arrived" and "misrouted".
        List<TargetView> both = targets.stream().filter(watches::contains).distinct().toList();
        if (all && both.size() > 1) {
            throw new IllegalArgumentException("This run copies every message to " + both.get(0).connection() + " / " + both.get(0).destination() + " and "
                    + both.get(1).connection() + " / " + both.get(1).destination() + ", and expects arrivals on both, so each message would arrive where it is "
                    + "expected and somewhere else, and the verdict could be wrong. Use Spread, or send to one place and expect where the system under test delivers.");
        }
        Run run = new Run(label, List.copyOf(parts), List.copyOf(targets), all, req.count(), req.rate(), headers,
                List.copyOf(watches), match, timeout);
        synchronized (runs) {
            runs.addFirst(run);
            // Only runs that have ended make room. One still going stays listed however old, or it could not be cancelled.
            int ended = 0;
            for (Iterator<Run> it = runs.iterator(); it.hasNext();) {
                if (it.next().finishedAt != null && ++ended > KEPT_RUNS) it.remove();
            }
        }
        executor.execute(() -> execute(run));
        return run.view();
    }

    /** Renders the body first, then the headers and the match expression, through one message so they can share values. */
    private Rendered render(Loaded part, Map<String, String> headers, String match, AtomicLong seq) {
        TemplateEngine.Message message = engine.message(part.fields(), part.vars(), seq);
        String body = message.render(part.text());
        String token = message.firstId();   // taken now: an id a header generates later is not in the body
        Map<String, String> rendered = new LinkedHashMap<>();
        headers.forEach((name, value) -> rendered.put(name, message.render(value)));
        if (part.watch() < 0) return new Rendered(body, rendered, null);
        if (match != null) token = message.render(match);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(part.template() + " has nothing to recognise its messages by when they arrive. "
                    + "Add {{uuid}} to the template or set a match expression.");
        }
        return new Rendered(body, rendered, token);
    }

    private interface BrokerCall {
        void run() throws Exception;
    }

    /**
     * Makes a call into a broker's client on a thread of its own and stops waiting after a while. A broker that
     * is frozen, not gone, keeps its connections open and answers nothing: the client then waits for minutes
     * (IBM MQ sat in a check for over a minute, until the queue manager was let run again), and a run has to end.
     */
    // ponytail: the abandoned call keeps its thread until the client gives up. Sending is not covered: a send
    // into a frozen broker still blocks the run until the client's own timeout.
    private static void within(int seconds, BrokerCall call, BooleanSupplier noLongerNeeded) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            call.run();
            return null;
        });
        Thread thread = new Thread(task, "msgforge-broker-call");
        thread.setDaemon(true);
        thread.start();
        // in short waits, so that an answer from elsewhere (the receiver reporting its own failure, a cancel) ends the wait
        for (long until = System.nanoTime() + seconds * 1_000_000_000L; !noLongerNeeded.getAsBoolean();) {
            try {
                task.get(100, TimeUnit.MILLISECONDS);
                return;
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception known ? known : new IllegalStateException(describe(e.getCause()));
            } catch (TimeoutException e) {
                if (System.nanoTime() > until) throw new IllegalStateException("no answer within " + seconds + " seconds");
            }
        }
    }

    /**
     * A run opens its own senders and subscriptions and closes them when it ends, however it ends. So a connection
     * edited or deleted meanwhile does not disturb it: it finishes on the settings it started with.
     */
    private void execute(Run run) {
        run.thread = Thread.currentThread();
        long interval = run.rate > 0 ? (long) (1e9 / run.rate) : 0; // 0 = as fast as possible
        long next = System.nanoTime();
        int size = run.targets.size();
        // Smooth weighted round-robin: exact shares even over a short run, and no long streaks of one template.
        int[] credit = new int[run.parts.size()];
        int totalWeight = run.parts.stream().mapToInt(Loaded::weight).sum();
        Map<String, TransportType.Sender> opened = new LinkedHashMap<>();   // one per connection, however many targets use it
        List<TransportType.Subscription> subscriptions = new ArrayList<>();
        Status end = Status.FAILED;
        boolean waiting = false;   // still awaiting arrivals when the timeout passed
        try {
            // Every connection is opened before the first message, so a broker that is down fails the run up front.
            TransportType.Sender[] senders = new TransportType.Sender[size];
            for (int i = 0; i < size; i++) {
                String id = run.targets.get(i).connectionId();
                try {
                    senders[i] = opened.get(id);
                    if (senders[i] == null) {
                        senders[i] = connections.open(id);
                        opened.put(id, senders[i]);
                        senders[i].check();
                    }
                } catch (Exception e) {
                    throw size == 1 ? e : new IllegalStateException(run.targets.get(i).connection() + ": " + describe(e));
                }
            }
            // Listening starts before the first send, so a fast system under test cannot answer unheard.
            for (int i = 0; i < run.watches.size(); i++) {
                int watch = i;
                TargetView w = run.watches.get(i);
                try {
                    subscriptions.add(connections.subscribe(w.connectionId(), w.destination(), body -> run.arrived(watch, body), error -> {
                        run.broken = "Receiving from " + w.connection() + " / " + w.destination() + " failed: " + error;
                        wake(run);
                    }));
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot receive from " + w.connection() + " / " + w.destination() + ": " + describe(e));
                }
            }
            for (int n = 1; n <= run.count && !run.stopped(); n++) {
                int pick = 0;
                for (int i = 0; i < credit.length; i++) {
                    credit[i] += run.parts.get(i).weight();
                    if (credit[i] > credit[pick]) pick = i;
                }
                credit[pick] -= totalWeight;
                Loaded part = run.parts.get(pick);
                Rendered message = render(part, run.headers, run.match, run.seq);
                if (message.token() != null) {
                    // Awaited before it is sent: the arrival can beat the return of send().
                    Awaited a = new Awaited(message.token(), pick);
                    synchronized (run.awaited) {
                        // Two awaited messages with one token could not be told apart when one of them arrives.
                        if (run.pending.putIfAbsent(a.token, a) != null) {
                            throw new IllegalStateException("Message " + n + " is identified by the same text as one still awaited (" + a.token
                                    + "). Each message needs its own: use {{uuid}} in the template, or a match expression that differs per message.");
                        }
                        run.awaited.add(a);
                    }
                }

                int first = run.all ? 0 : (n - 1) % size, last = run.all ? size - 1 : first;
                for (int i = first; i <= last; i++) {
                    TargetView t = run.targets.get(i);
                    senders[i].send(t.destination(), message.body(), message.headers());
                    synchronized (messages) {
                        messages.addFirst(new Sent(run.id, n, i, t.connection(), t.destination(), part.template(),
                                message.headers(), message.body(), Instant.now()));
                        while (messages.size() > KEPT_MESSAGES) messages.removeLast();
                    }
                }
                run.sent = n;
                if (interval > 0) {
                    next += interval;
                    // cancel() unparks this thread, so a slow run stops now, not at its next message
                    for (long wait; !run.stopped() && (wait = next - System.nanoTime()) > 0;) LockSupport.parkNanos(wait);
                }
            }
            if (!run.watches.isEmpty() && !run.stopped()) {
                run.status = Status.VERIFYING;
                long deadline = System.nanoTime() + run.timeoutSeconds * 1_000_000_000L;
                synchronized (run.awaited) {
                    // the last arrival, cancel() and a failing receiver all notify
                    for (long left; !run.pending.isEmpty() && !run.stopped() && (left = deadline - System.nanoTime()) > 0;) {
                        run.awaited.wait(left / 1_000_000 + 1);
                    }
                    waiting = !run.pending.isEmpty();
                }
            }
            // Time is up and messages are still awaited. Before they are called missing, each source is asked whether it
            // is still there: a broker that died a moment ago has not been noticed by its client yet, and "the system
            // under test lost them" would be the wrong thing to report.
            // A receiver that has already reported its own failure is not asked, and one that reports it while the
            // question is out is not waited for: its report says why, the unanswered question would only say that.
            for (int i = 0; waiting && !run.stopped() && i < subscriptions.size(); i++) {
                try {
                    within(brokerAnswerSeconds, subscriptions.get(i)::check, run::stopped);
                } catch (Exception e) {
                    TargetView w = run.watches.get(i);
                    throw new IllegalStateException(run.broken != null ? run.broken
                            : "Receiving from " + w.connection() + " / " + w.destination() + " failed: " + describe(e));
                }
            }
            if (run.broken != null) throw new IllegalStateException(run.broken);
            end = run.cancelled ? Status.CANCELLED : Status.DONE;
        } catch (Throwable e) {
            // Throwable: a plugin missing a class throws an Error, and the run must still end.
            // ponytail: the first failed send stops the run (a broker that is down fails every message anyway).
            run.error = describe(e);
        } finally {
            List<AutoCloseable> mine = new ArrayList<>(subscriptions);
            mine.addAll(opened.values());
            for (AutoCloseable c : mine) {
                try {
                    within(brokerAnswerSeconds, c::close, () -> false);
                } catch (Throwable ignored) {
                    // the run's result stands; the rest must still be closed
                }
            }
            run.finishedAt = Instant.now();
            run.status = end;
        }
    }

    /** The top message plus the root cause, which is where brokers put the useful part (e.g. an MQ reason code). */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String top = say(e), cause = say(root);
        return root == e || top.contains(cause) ? top : top + " (" + cause + ")";
    }

    /** An exception with no message (a timeout from Future.get, for one) still has a name worth showing. */
    private static String say(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    public void cancel(String id) {
        synchronized (runs) {
            cancel(runs.stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow(() -> new NoSuchElementException("no run " + id)));
        }
    }

    // ponytail: wakes a run that is waiting, between sends or for arrivals. A send that is blocked inside a
    // broker call is left to return or time out on its own; interrupting it would need each client's cooperation.
    private static void cancel(Run run) {
        run.cancelled = true;
        wake(run);
    }

    private static void wake(Run run) {
        LockSupport.unpark(run.thread);
        synchronized (run.awaited) {
            run.awaited.notifyAll();
        }
    }

    /** On shutdown runs are cancelled and given a moment to close what they opened, not cut off mid-send. */
    @PreDestroy
    void stop() throws InterruptedException {
        synchronized (runs) {
            runs.forEach(Runs::cancel);
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);
    }

    public List<View> list() {
        synchronized (runs) {
            return runs.stream().map(Run::view).toList();
        }
    }

    public Sent message(String runId, int n, int target) {
        synchronized (messages) {
            return messages.stream().filter(m -> m.runId().equals(runId) && m.n() == n && m.target() == target).findFirst()
                    .orElseThrow(() -> new NoSuchElementException("That message is no longer kept: only the latest " + KEPT_MESSAGES + " are"));
        }
    }

    public List<Sent> messages() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }
}
