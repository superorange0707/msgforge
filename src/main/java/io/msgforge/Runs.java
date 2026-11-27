package io.msgforge;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Starts runs and remembers the latest runs and messages for the console. A run sends messages to a plan: so many,
 * or for so long, at a rate that may ramp up, from one or several senders. It can make a share of them invalid on
 * purpose, verify where each message arrives, and watch other destinations (a reject queue, say) for what turns up there.
 * What it measures on the way is in {@link Stats}; it stays the same size however long the run.
 */
@Component
public class Runs {

    private static final int MAX_COUNT = 1_000_000_000, MAX_TARGETS = 10, MAX_PARTS = 20, MAX_WEIGHT = 1000, KEPT_RUNS = 20, KEPT_MESSAGES = 50;
    private static final int MAX_SENDERS = 64, MAX_AUTO_SENDERS = 16, MAX_WATCHES = 10, MAX_RAMP = 86_400, MAX_DURATION = 7 * 86_400;
    // ponytail: each arrival is matched by scanning every message still awaited, so verified runs stop at 20,000.
    // To go higher, pull the token out of the arrival (a position or pattern per template) and look it up in a map.
    private static final int MAX_VERIFIED = 20_000;
    // Correlation ids of arrivals nobody was waiting for yet: a reply can beat the return of the send that tells its id.
    private static final int KEPT_STRAYS = 10_000;
    private static final double MIN_RATE = 0.001;
    // ponytail: a length, not a proof. Eight characters keeps counters and small numbers out; it does not make a token unique in a body.
    private static final int MIN_TOKEN = 8;

    /** One place a run sends to: a saved connection and a destination on it. The destination may hold ${MSGFORGE_NAME}. */
    public record Target(String connection, String destination) {}

    /**
     * One kind of message in a run. weight is its share: parts weighted 7 and 3 give exactly 70% and 30%.
     * body is optional: when set it is used instead of the saved template (unsaved edits from the console).
     * expect is optional: where this part's messages should arrive once the system under test has handled them.
     */
    public record Part(String pack, String template, String body, int weight, Map<String, String> vars, Target expect) {}

    /**
     * How a run goes beyond "count messages at rate". Everything is optional.
     * rampUpSeconds: the rate rises in a straight line from nothing to the run's rate over this long.
     * durationSeconds: the run stops sending after this long; with a count as well, whichever comes first.
     * senders: how many send at once, each over a connection of its own (default 1). 0 lets the run decide: it
     * starts with one and adds another whenever it falls behind its rate.
     * unlimited: a run with neither a count nor a duration goes on until it is cancelled, and has to say so here.
     */
    public record Load(Integer rampUpSeconds, Integer durationSeconds, Integer senders, Boolean unlimited) {}

    /** Another destination to listen on during the run, and how to sort what arrives there. */
    public record Watch(String connection, String destination, List<Observer.Bucket> buckets) {}

    /**
     * What to send, where and how fast.
     * parts and targets describe the general case. For one template to one place, the shorthand fields
     * (pack, template, body, vars, connection, destination, expect) do the same job.
     * mode "spread" (default) hands each message to the next target in turn; "all" sends every message to every target.
     * headers are rendered per message with the same placeholders, and share the body's {{name=...}} bindings.
     * When a part has an expectation, each message is recognised on arrival by a token: match rendered like a header,
     * or without match the first {{uuid}} or {{id}} value in the body. An arrival is that message when its body or
     * one of its header values contains the token, or when its correlation id is the id the transport gave the message.
     * timeoutSeconds (default 10) is how long to wait for arrivals after the last send.
     * count 0 means no count: the run then needs a duration, or load.unlimited.
     * invalid makes a share of the messages invalid ({@link Faults}); seed makes the bodies and faults repeatable.
     * raw sends the body and headers exactly as given: placeholders are not filled in.
     */
    public record Request(String label, List<Part> parts, List<Target> targets, String mode, int count, double rate,
                          Map<String, String> headers, String pack, String template, String body, Map<String, String> vars,
                          String connection, String destination, Target expect, Integer timeoutSeconds, String match,
                          Load load, Faults.Spec invalid, Long seed, List<Watch> watches, Boolean raw) {

        /** A request as it was before load plans. */
        public Request(String label, List<Part> parts, List<Target> targets, String mode, int count, double rate,
                       Map<String, String> headers, String pack, String template, String body, Map<String, String> vars,
                       String connection, String destination, Target expect, Integer timeoutSeconds, String match) {
            this(label, parts, targets, mode, count, rate, headers, pack, template, body, vars, connection, destination, expect, timeoutSeconds, match,
                    null, null, null, null, null);
        }

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
     *
     * A run that sends invalid messages has a second check per part for those (invalid is true). They are expected
     * where the run says invalid messages go, or nowhere; arrived then counts the ones rejected as expected, and
     * accepted the ones that turned up where the valid messages go. On a valid check, rejected counts the valid
     * messages that turned up where the invalid ones go. Neither is counted under misrouted as well.
     *
     * unrecognised is for invalid messages expected nowhere that went out with nothing left to know them by (emptied,
     * replaced, cut before their token): the number of them that cannot be said to have stayed away, because that many
     * arrivals nobody could identify turned up where this run's valid messages go. They are not counted as arrived.
     */
    public record Check(String template, String expected, int sent, int arrived, int misrouted, int missing, Long medianMs, Long maxMs,
                        boolean invalid, int rejected, int accepted, int unrecognised) {

        public Check(String template, String expected, int sent, int arrived, int misrouted, int missing, Long medianMs, Long maxMs,
                     boolean invalid, int rejected, int accepted) {
            this(template, expected, sent, arrived, misrouted, missing, medianMs, maxMs, invalid, rejected, accepted, 0);
        }

        /** A check of valid messages in a run without invalid ones. */
        public Check(String template, String expected, int sent, int arrived, int misrouted, int missing, Long medianMs, Long maxMs) {
            this(template, expected, sent, arrived, misrouted, missing, medianMs, maxMs, false, 0, 0);
        }
    }

    /**
     * sent counts messages that went out, not deliveries: in "all" mode one message reaches every target. failed
     * counts the ones a send was refused for, invalid the ones (among sent) that were made invalid on purpose.
     * count is 0 for a run without one. elapsedMs is how long it has been sending, without the wait for arrivals.
     * targetRate is what the plan asks for at this moment (null when the run sends as fast as it can), actualRate
     * what went out over the last two seconds, and once the run has ended, on average.
     * verdict is null unless the run ended DONE. PASS then means every message arrived where expected and no send
     * failed; a run with nothing to verify has a verdict only when sends failed (FAIL).
     * A failed or cancelled run has no verdict and no checks.
     */
    public record View(String id, String label, List<String> templates, List<TargetView> targets, String mode,
                       long count, long sent, Status status, String error, Instant startedAt, Instant finishedAt,
                       List<Check> checks, String verdict,
                       long failed, long invalid, Double targetRate, double actualRate, long elapsedMs, Stats.Latency latency,
                       List<Stats.ErrorCount> errors, Double rate, Integer rampUpSeconds, Integer durationSeconds, int senders, long seed,
                       Map<String, Long> faults, List<Observer.Summary> watched) {}

    /** A run with everything a report needs: the series over time and each watched destination's tables. */
    public record Detail(View run, List<Stats.Point> series, List<Observer.View> watches) {}

    /** fault is the kind of fault the message was given, or null for a valid one. */
    public record Sent(String runId, long n, int target, String connection, String destination, String template,
                       Map<String, String> headers, String body, Instant at, String fault) {}

    /** The rate a run asks for at each moment: `rate` a second, reached in a straight line over rampSeconds. */
    record Plan(double rate, double rampSeconds) {
        double rateAt(double t) {
            return rate <= 0 ? 0 : t < rampSeconds ? rate * t / rampSeconds : rate;
        }

        /** How many messages are due by t seconds in. */
        double dueBy(double t) {
            if (rampSeconds <= 0) return rate * t;
            return t < rampSeconds ? rate * t * t / (2 * rampSeconds) : rate * rampSeconds / 2 + rate * (t - rampSeconds);
        }
    }

    /** What the process has done since it started, for the metrics endpoint. Runs fall off the list; these do not. */
    public static final class Totals {
        static final int MAX_DESTINATIONS = 200;
        final LongAdder sent = new LongAdder(), failed = new LongAdder(), invalid = new LongAdder();
        final Map<String, LongAdder> runs = new ConcurrentHashMap<>();       // by outcome
        final Map<TargetView, LongAdder> arrivals = new ConcurrentHashMap<>();   // by watched destination, as shown (never the resolved name)

        LongAdder arrivals(TargetView where) {
            LongAdder known = arrivals.get(where);
            if (known != null) return known;
            // the names come from requests: past the cap they are counted together
            return arrivals.computeIfAbsent(arrivals.size() >= MAX_DESTINATIONS ? new TargetView("", "(other)", "(other)") : where, w -> new LongAdder());
        }
    }

    /**
     * A part with its template text and pack fields loaded. watch is the index of its expected destination in the
     * run's watches, or -1. tracked says its messages need a token: they are awaited, valid or invalid.
     */
    private record Loaded(String template, String text, Map<String, String> fields, Map<String, String> vars, int weight, int watch,
                          boolean tracked, boolean raw) {}

    private record Rendered(String body, Map<String, String> headers, String token) {}

    /** A message on its way out: made under the run's gate, sent outside it. */
    private record Outgoing(long n, int part, String body, Map<String, String> headers, String fault, Awaited awaited) {}

    /** A sent message that should arrive somewhere, or, being invalid, should not. */
    private static final class Awaited {
        final String token;
        final int part, expected;   // expected: the watch it should arrive on, -1 for nowhere
        final long n;
        final boolean invalid;
        final boolean lost;         // it went out without its token: where it arrives, nothing says it is this one
        final long sentAt = System.nanoTime();
        int watch = -1;   // where it arrived
        long latency;

        Awaited(String token, int part, long n, boolean invalid, int expected, boolean lost) {
            this.token = token;
            this.part = part;
            this.n = n;
            this.invalid = invalid;
            this.expected = expected;
            this.lost = lost;
        }
    }

    /** An arrival that named a message nobody had sent yet, as far as the run knew: where it arrived, and what the observer kept of it. */
    private static final class Stray {
        final int watch;
        final Connections.Delivery delivery;

        Stray(int watch, Connections.Delivery delivery) {
            this.watch = watch;
            this.delivery = delivery;
        }
    }

    private final class Run {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final Instant startedAt = Instant.now();
        final String label;
        final List<Loaded> parts;
        final List<TargetView> targets;
        final List<String> destinations;        // the targets' destinations with ${NAME} filled in
        final boolean all;
        final int count;
        final Plan plan;
        final long durationNanos;
        final Integer rampUpSeconds, durationSeconds;
        final int wantedSenders;                // 0: as many as it takes
        final Map<String, String> headers;
        final List<TargetView> watches;
        final List<String> watchDestinations;   // resolved, like destinations
        final List<Observer> observers;         // one per watch
        final List<String> watchKeys = new ArrayList<>();   // connection and resolved destination, one per watch
        final java.util.Set<String> expecting = new java.util.HashSet<>();   // the keys of the watches this run expects arrivals on
        final boolean onlyWatched;              // it listens somewhere it expects nothing in particular: it waits its whole timeout there
        final int invalidWatch;                 // where invalid messages should arrive, -1 for nowhere
        final boolean verifying;
        final double faultPercent;
        final List<String> faultKinds;          // null: no faults
        final long seed;
        final String match;
        final int timeoutSeconds;
        final Stats stats = new Stats();
        final Map<String, AtomicLong> faults = Collections.synchronizedMap(new LinkedHashMap<>());   // by kind, in the order asked for
        // Guarded by awaited: arrivals come in on the transports' threads. pending is keyed by token, byId by the transport's message id.
        final List<Awaited> awaited = new ArrayList<>();
        final Map<String, Awaited> pending = new LinkedHashMap<>();
        final Map<String, Awaited> byId = new HashMap<>();
        final Map<String, Stray> strays = new LinkedHashMap<>() {   // by correlation id
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Stray> eldest) {
                return size() > KEPT_STRAYS;
            }
        };
        // Arrivals per watch that are no message of this run, as far as anything on them says. Counted only in a run that
        // may need them: one whose invalid messages are expected nowhere. Guarded by awaited.
        final int[] unknown;
        final boolean blind;
        int outstanding, nowhere;               // awaited messages not yet arrived that are expected somewhere, and the number expected nowhere
        // Guarded by gate: the making of messages. One sender at a time takes the next message, in order, when it is due.
        final Object gate = new Object();
        final AtomicLong seq = new AtomicLong();   // {{seq}} counts within the run
        final SplittableRandom random;
        final int[] credit;
        final int totalWeight;
        long issued;
        double owed, pacedAt;
        volatile boolean lagging;               // a message was due a good while before a sender was free to take it
        final List<Thread> threads = new CopyOnWriteArrayList<>();
        final AtomicInteger sending = new AtomicInteger();
        final AtomicLong lastKept = new AtomicLong();
        volatile long began, ended;             // nanoTime when sending started and stopped; 0 until then
        volatile int senders;
        volatile String broken;   // a receiver failed: the run cannot tell what arrived any more
        volatile String failure;  // the run cannot go on: a message could not be made, or sends only fail
        volatile Thread thread;
        volatile Status status = Status.RUNNING;
        volatile String error;
        volatile Instant finishedAt;
        volatile boolean cancelled;

        Run(String label, List<Loaded> parts, List<TargetView> targets, List<String> destinations, boolean all, int count, Plan plan, Load load,
            Map<String, String> headers, List<TargetView> watches, List<String> watchDestinations, List<Observer> observers, int invalidWatch,
            boolean verifying, double faultPercent, List<String> faultKinds, long seed, String match, int timeoutSeconds) {
            this.label = label;
            this.parts = parts;
            this.targets = targets;
            this.destinations = destinations;
            this.all = all;
            this.count = count;
            this.plan = plan;
            this.rampUpSeconds = load.rampUpSeconds();
            this.durationSeconds = load.durationSeconds();
            this.durationNanos = load.durationSeconds() == null ? 0 : load.durationSeconds() * 1_000_000_000L;
            this.wantedSenders = load.senders() == null ? 1 : load.senders();
            this.headers = headers;
            this.watches = watches;
            this.watchDestinations = watchDestinations;
            this.observers = observers;
            this.invalidWatch = invalidWatch;
            this.verifying = verifying;
            this.faultPercent = faultPercent;
            this.faultKinds = faultKinds;
            this.blind = verifying && faultKinds != null && invalidWatch < 0;
            this.unknown = new int[watches.size()];
            this.seed = seed;
            this.random = new SplittableRandom(seed);
            this.match = match;
            this.timeoutSeconds = timeoutSeconds;
            this.credit = new int[parts.size()];
            this.totalWeight = parts.stream().mapToInt(Loaded::weight).sum();
            this.owed = plan.rampSeconds() > 0 ? 0 : 1;   // the first message goes at once, unless the rate starts from nothing
            if (faultKinds != null) faultKinds.forEach(kind -> faults.put(kind, new AtomicLong()));
            for (int i = 0; i < watches.size(); i++) watchKeys.add(watches.get(i).connectionId() + "\n" + watchDestinations.get(i));
            for (Loaded part : parts) if (part.watch() >= 0) expecting.add(watchKeys.get(part.watch()));
            if (invalidWatch >= 0) expecting.add(watchKeys.get(invalidWatch));
            onlyWatched = watchKeys.stream().anyMatch(key -> !expecting.contains(key));
        }

        long elapsed() {
            long from = began, to = ended;
            return from == 0 ? 0 : (to != 0 ? to : System.nanoTime()) - from;
        }

        /**
         * Waits, holding the gate, until the plan allows the next message. False when the run is over instead.
         * The plan says how many messages are due by now; what is due and not yet sent is owed. A run that cannot
         * keep up is owed more and more, and at most one second's worth is kept: it does not answer a stall with a
         * burst, it shows a rate below its target.
         */
        private boolean turn() {
            while (true) {
                if (stopped() || failure != null) return false;
                long now = System.nanoTime();
                if (durationNanos > 0 && now - began >= durationNanos) return false;
                if (plan.rate() <= 0) return true;
                double t = (now - began) / 1e9;
                owed = Math.min(owed + plan.dueBy(t) - plan.dueBy(pacedAt), Math.max(1, plan.rate()));
                pacedAt = t;
                if (owed >= 1) {
                    if (owed > Math.max(2, plan.rate() / 4)) lagging = true;
                    owed -= 1;
                    return true;
                }
                double rate = plan.rateAt(t);
                long wait = rate > 0 ? (long) ((1 - owed) / rate * 1e9) : 20_000_000L;
                if (t < plan.rampSeconds()) wait = Math.min(wait, 20_000_000L);   // the rate is rising: a wait worked out from it now is too long
                if (durationNanos > 0) wait = Math.min(wait, durationNanos - (now - began));
                // cancel() unparks every sender, so a slow run stops now, not at its next message
                LockSupport.parkNanos(Math.max(wait, 1_000));
            }
        }

        /**
         * The next message, made when it is due, or null when the run has no more to send. Called with the gate held:
         * messages are made one at a time and in order, whichever sender takes them, so the same seed gives message n
         * the same body and the same fault whatever the number of senders.
         */
        // ponytail: making messages is serial. Rendering takes microseconds, so that caps a run at some hundred
        // thousand messages a second, far above what one broker connection per sender carries.
        Outgoing next() {
            if (count > 0 && issued >= count) return null;
            if (!turn()) return null;
            long n = ++issued;
            // Smooth weighted round-robin: exact shares even over a short run, and no long streaks of one template.
            int pick = 0;
            for (int i = 0; i < credit.length; i++) {
                credit[i] += parts.get(i).weight();
                if (credit[i] > credit[pick]) pick = i;
            }
            credit[pick] -= totalWeight;
            Loaded part = parts.get(pick);
            Rendered message = render(part, headers, match, seq, random);
            String body = message.body(), fault = null;
            if (faultKinds != null && random.nextDouble() * 100 < faultPercent) {
                fault = faultKinds.get(random.nextInt(faultKinds.size()));
                String faulted = Faults.apply(fault, body, random, message.token());
                if (faulted == null) {   // checked before the run, so only a message unlike the first: cut it off instead
                    fault = body.length() < 2 ? "empty" : "truncated";
                    faulted = Faults.apply(fault, body, random, null);
                }
                body = faulted;
                faults.computeIfAbsent(fault, k -> new AtomicLong()).incrementAndGet();
            }
            Awaited a = null;
            if (message.token() != null && (fault == null ? part.watch() >= 0 : verifying)) {
                // Awaited before it is sent: the arrival can beat the return of send().
                boolean lost = fault != null && !body.contains(message.token())
                        && message.headers().values().stream().noneMatch(v -> v != null && v.contains(message.token()));
                a = new Awaited(message.token(), pick, n, fault != null, fault == null ? part.watch() : invalidWatch, lost);
                synchronized (awaited) {
                    // Two awaited messages with one token could not be told apart when one of them arrives.
                    if (pending.putIfAbsent(a.token, a) != null) {
                        throw new IllegalStateException("Message " + n + " is identified by the same text as one still awaited (" + a.token
                                + "). Each message needs its own: use {{uuid}} in the template, or a match expression that differs per message.");
                    }
                    awaited.add(a);
                    if (a.expected >= 0) outstanding++;
                    else nowhere++;
                }
            }
            return new Outgoing(n, pick, body, message.headers(), fault, a);
        }

        /** The transport gave the message this id: a reply that names it as its correlation id is this message. */
        void sentAs(Awaited a, String messageId) {
            synchronized (awaited) {
                byId.put(messageId, a);
                Stray early = strays.remove(messageId);
                if (early != null && a.watch < 0 && pending.get(a.token) == a) {
                    if (blind && unknown[early.watch] > 0) unknown[early.watch]--;   // it was somebody's after all
                    settle(a, early.watch, System.nanoTime());
                    if (early.delivery != null) early.delivery.claim(id, a.n, outcome(a, early.watch));   // the lists of arrivals learn which message it was
                }
            }
        }

        /** The send failed: the message is not out there, and nobody waits for it. */
        void unawait(Awaited a) {
            if (a == null) return;
            synchronized (awaited) {
                if (pending.remove(a.token, a)) {
                    awaited.remove(a);
                    if (a.expected >= 0) outstanding--;
                    else nowhere--;
                    if (outstanding == 0) awaited.notifyAll();
                }
            }
        }

        private void settle(Awaited found, int watch, long now) {
            found.watch = watch;
            found.latency = now - found.sentAt;
            pending.remove(found.token);
            if (found.expected >= 0 && --outstanding == 0) awaited.notifyAll();
        }

        /**
         * A message turned up on a watched destination. The first arrival of a message decides; later copies are ignored.
         * Of the awaited tokens it contains, in its body or in a header, the longest is the message: with tokens 1 and
         * 10 awaited, a body with 10 in it contains both, and is message 10. An arrival with none of them is the message
         * whose transport id it carries as its correlation id: an invalid message, emptied or cut off, has lost its
         * token, and the usual convention for a reply still names it.
         */
        void arrived(int watch, TransportType.Arrival arrival) {
            long now = System.nanoTime();
            Long message = null;
            String correlation = arrival.correlationId();
            Connections.Delivery delivery = Connections.delivering();
            synchronized (awaited) {
                Awaited found = null;
                for (Awaited a : pending.values()) {
                    if ((found == null || a.token.length() > found.token.length()) && carries(arrival, a.token)) found = a;
                }
                boolean copy = false;
                if (found == null && correlation != null && verifying) {
                    found = byId.get(correlation);
                    if (found != null && pending.get(found.token) != found) {   // it has arrived already
                        found = null;
                        copy = true;
                    } else if (found == null) strays.put(correlation, new Stray(watch, delivery));
                }
                if (found == null && blind && !copy) {
                    // ponytail: a look through every message sent, per arrival that is not recognised at once, and only in
                    // a run that sends invalid messages it expects nowhere. A second copy of a message that has arrived
                    // must not pass for an invalid one that got through.
                    for (Awaited a : awaited) {
                        if (a.watch >= 0 && carries(arrival, a.token)) {
                            copy = true;
                            break;
                        }
                    }
                    if (!copy) unknown[watch]++;
                }
                if (found != null) {
                    settle(found, watch, now);
                    message = found.n;
                    // every list this arrival is on, this run's and a monitor's on the same destination, learns whose it was
                    if (delivery != null) delivery.claim(id, found.n, outcome(found, watch));
                }
            }
            observers.get(watch).arrived(arrival, message);
            totals.arrivals(watches.get(watch)).increment();
        }

        private static String outcome(Awaited a, int watch) {
            return (a.invalid ? "invalid-" : "valid-") + (watch == a.expected ? "expected" : "unexpected");
        }

        private static boolean carries(TransportType.Arrival arrival, String token) {
            if (arrival.body().contains(token)) return true;
            if (arrival.correlationId() != null && arrival.correlationId().contains(token)) return true;
            for (String value : arrival.headers().values()) if (value != null && value.contains(token)) return true;
            return false;
        }

        boolean stopped() {
            return cancelled || broken != null;
        }

        /** The first reason wins; every sender stops at its next message. */
        void fail(String why) {
            synchronized (threads) {   // not the gate: a sender may be holding that while it waits for its turn
                if (failure == null) failure = why;
            }
            wake(this);
        }

        List<Check> checks() {
            List<Check> out = new ArrayList<>();
            synchronized (awaited) {
                // What arrived unrecognised where valid messages are expected: each may be an invalid message that was let
                // through with nothing left to know it by. One arrival answers for one message, whichever part sent it.
                int unclaimed = 0;
                boolean[] counted = new boolean[watches.size()];
                for (Loaded part : parts) {
                    if (part.watch() >= 0 && !counted[part.watch()]) {
                        counted[part.watch()] = true;
                        unclaimed += unknown[part.watch()];
                    }
                }
                for (int p = 0; p < parts.size(); p++) {
                    for (boolean invalid : new boolean[] {false, true}) {
                        int own = parts.get(p).watch(), expected = invalid ? invalidWatch : own;
                        int sent = 0, arrived = 0, misrouted = 0, missing = 0, rejected = 0, accepted = 0, lost = 0;
                        List<Long> latencies = new ArrayList<>();
                        for (Awaited a : awaited) {
                            if (a.part != p || a.invalid != invalid) continue;
                            sent++;
                            if (a.watch < 0) {
                                if (expected < 0) {   // expected nowhere, and it stayed away, or could not have been seen arriving
                                    arrived++;
                                    if (a.lost) lost++;
                                } else missing++;
                            } else if (a.watch == expected) {
                                arrived++;
                                latencies.add(a.latency / 1_000_000);
                            } else if (invalid && a.watch == own) accepted++;
                            else if (!invalid && a.watch == invalidWatch) rejected++;
                            else misrouted++;
                        }
                        // No check for what was not sent or is not expected anywhere: "0 sent, 0 arrived" judges nothing. (A run whose
                        // messages all came out invalid has no valid ones to check, and a part without an expectation none at all.)
                        if (sent == 0 || !invalid && own < 0) continue;
                        // "It did not arrive" is only known of a message that could have been recognised. Of the ones that
                        // could not, as many as there were arrivals nobody can place are not credited: the run cannot tell
                        // them apart, and a result that cannot be told from a failure is not a pass.
                        int unrecognised = Math.min(lost, unclaimed);
                        unclaimed -= unrecognised;
                        arrived -= unrecognised;
                        Collections.sort(latencies);
                        int n = latencies.size();
                        String where = expected < 0 ? "nowhere" : watches.get(expected).connection() + " / " + watches.get(expected).destination();
                        out.add(new Check(parts.get(p).template(), where, sent, arrived, misrouted, missing,
                                n == 0 ? null : latencies.get(n / 2), n == 0 ? null : latencies.get(n - 1), invalid, rejected, accepted, unrecognised));
                    }
                }
            }
            return out;
        }

        View view() {
            return view(this.status, this.finishedAt);   // status read first: once it says the run has ended, the checks no longer change
        }

        /** The view as it is, or as it will be once the run is marked ended with this status at this time. */
        View view(Status status, Instant finishedAt) {
            // A run that failed or was cancelled did not finish the experiment: it has a status, and no result to judge.
            List<Check> checks = status == Status.FAILED || status == Status.CANCELLED ? List.of() : checks();
            long sent = stats.sent(), failed = stats.failed(), elapsed = elapsed(), now = System.nanoTime();
            String verdict = status != Status.DONE || !verifying && failed == 0 ? null
                    : failed == 0 && checks.stream().allMatch(c -> c.arrived() == c.sent()) ? "PASS" : "FAIL";
            boolean live = status == Status.RUNNING && began != 0 && ended == 0;
            Double target = plan.rate() <= 0 ? null : Stats.round(live ? plan.rateAt(elapsed / 1e9) : plan.rate());
            double actual = live ? stats.rate(now, elapsed) : elapsed == 0 ? 0 : Stats.round(sent / Math.max(0.001, elapsed / 1e9));
            Map<String, Long> byKind = new LinkedHashMap<>();
            synchronized (faults) {
                faults.forEach((kind, n) -> byKind.put(kind, n.get()));
            }
            return new View(id, label, parts.stream().map(Loaded::template).toList(), targets, all ? "all" : "spread",
                    count, sent, status, error, startedAt, finishedAt, checks, verdict,
                    failed, stats.invalid(), target, actual, elapsed / 1_000_000, stats.latency(), stats.errors(),
                    plan.rate() <= 0 ? null : plan.rate(), rampUpSeconds, durationSeconds, senders, seed, byKind,
                    observers.stream().map(Observer::summary).toList());
        }

        Detail detail(boolean withSamples) {
            return new Detail(view(), stats.series(elapsed(), plan), observers.stream().map(o -> o.view(withSamples)).toList());
        }

        Detail ending(Status status, Instant finishedAt) {
            return new Detail(view(status, finishedAt), stats.series(elapsed(), plan), observers.stream().map(o -> o.view(false)).toList());
        }
    }

    private final Packs packs;
    private final TemplateEngine engine;
    private final Connections connections;
    private final Totals totals = new Totals();
    private final List<Consumer<Detail>> finishedListeners = new CopyOnWriteArrayList<>();
    private final Deque<Run> runs = new ArrayDeque<>();
    // How long a broker has to answer the question "are you there" or a close. Kafka's own check can take 11 of them.
    int brokerAnswerSeconds = 15;
    private final Deque<Sent> messages = new ArrayDeque<>();
    // ponytail: one thread per run, one more per sender, and no cap on concurrent runs; add a bounded pool if this is ever shared.
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

    public Totals totals() {
        return totals;
    }

    /** Called with each run when it has ended, on the run's own thread. A listener that throws is ignored. */
    public void onFinished(Consumer<Detail> listener) {
        finishedListeners.add(listener);
    }

    /**
     * The checks that need no pack and no connection. A run makes them first; a scenario makes them when it is
     * saved, so a number that could never run is refused then, not on the day it is used.
     */
    static void check(int count, double rate, List<Part> parts, List<Target> targets, Map<String, String> headers, Integer timeoutSeconds,
                      Load load, Faults.Spec invalid, List<Watch> watches) {
        int duration = load == null || load.durationSeconds() == null ? 0 : load.durationSeconds();
        int ramp = load == null || load.rampUpSeconds() == null ? 0 : load.rampUpSeconds();
        int senders = load == null || load.senders() == null ? 1 : load.senders();
        boolean unlimited = load != null && Boolean.TRUE.equals(load.unlimited());
        if (count < 0 || count > MAX_COUNT) throw new IllegalArgumentException("Messages must be 1 to " + MAX_COUNT);
        if (duration < 0 || duration > MAX_DURATION) throw new IllegalArgumentException("Duration must be 0 to " + MAX_DURATION + " seconds");
        if (count == 0 && duration == 0 && !unlimited) {
            throw new IllegalArgumentException("Messages must be 1 to " + MAX_COUNT + ". A run without a number of messages needs a duration, or has to be allowed to run until it is stopped (unlimited).");
        }
        if (!(rate >= 0) || Double.isInfinite(rate)) throw new IllegalArgumentException("Per second must be 0 or more");
        // below this the pause between two messages no longer fits the clock arithmetic, and nobody waits 17 minutes for a message
        if (rate > 0 && rate < MIN_RATE) throw new IllegalArgumentException("Per second must be 0 (as fast as possible) or at least " + MIN_RATE);
        if (ramp < 0 || ramp > MAX_RAMP) throw new IllegalArgumentException("Ramp-up must be 0 to " + MAX_RAMP + " seconds");
        if (ramp > 0 && rate == 0) throw new IllegalArgumentException("A ramp-up needs a rate to rise to: set Per second");
        if (senders < 0 || senders > MAX_SENDERS) throw new IllegalArgumentException("Senders must be 1 to " + MAX_SENDERS + ", or 0 to add them as needed");
        if (targets.stream().anyMatch(t -> t == null)) throw new IllegalArgumentException("A target is empty: each needs a connection and a destination");
        if (parts.size() > MAX_PARTS) throw new IllegalArgumentException("At most " + MAX_PARTS + " templates");
        for (Part p : parts) {
            if (p == null || (p.template() == null || p.template().isBlank()) && p.body() == null) throw new IllegalArgumentException("Template is required");
            if (p.weight() < 0 || p.weight() > MAX_WEIGHT) throw new IllegalArgumentException("Weight must be 1 to " + MAX_WEIGHT);
            noNulls("Override", p.vars());
        }
        noNulls("Header", headers);
        if (timeoutSeconds != null && (timeoutSeconds < 1 || timeoutSeconds > 600)) throw new IllegalArgumentException("Timeout must be 1 to 600 seconds");
        if (invalid != null) {
            Faults.kinds(invalid);
            if (invalid.expect() != null) target("Where invalid messages arrive", invalid.expect());
        }
        boolean verified = parts.stream().anyMatch(p -> p.expect() != null) || invalid != null && invalid.expect() != null;
        if (verified && (count == 0 || count > MAX_VERIFIED)) {
            throw new IllegalArgumentException("A run that verifies arrivals can send at most " + MAX_VERIFIED + " messages, so it needs a number of messages");
        }
        if (watches != null) {
            if (watches.size() > MAX_WATCHES) throw new IllegalArgumentException("At most " + MAX_WATCHES + " watched destinations");
            for (Watch w : watches) {
                if (w == null) throw new IllegalArgumentException("A watched destination is empty: it needs a connection and a destination");
                target("A watched destination", new Target(w.connection(), w.destination()));
                Observer.check(w.buckets());
            }
        }
    }

    private static void target(String what, Target t) {
        if (t.connection() == null || t.connection().isBlank()) throw new IllegalArgumentException(what + " needs a connection");
        if (t.destination() == null || t.destination().isBlank()) throw new IllegalArgumentException(what + " needs a destination");
    }

    /** JSON can say "a": null where a text belongs. */
    static void noNulls(String what, Map<String, String> values) {
        if (values != null) values.forEach((name, value) -> {
            if (value == null) throw new IllegalArgumentException(what + " " + name + " has no value");
        });
    }

    /** The id of the connection a person names as an environment ({@link Connections#idOf}). */
    String environment(String wanted) {
        return connections.idOf(wanted);
    }

    public View start(Request req) {
        List<Part> wanted = req.parts() != null && !req.parts().isEmpty() ? req.parts()
                : List.of(new Part(req.pack(), req.template(), req.body(), 1, null, req.expect()));
        List<Target> asked = req.targets() != null && !req.targets().isEmpty() ? req.targets()
                : List.of(new Target(req.connection(), req.destination()));
        check(req.count(), req.rate(), wanted, asked, req.headers(), req.timeoutSeconds(), req.load(), req.invalid(), req.watches());
        noNulls("Override", req.vars());
        boolean all = "all".equals(req.mode());
        if (req.mode() != null && !all && !"spread".equals(req.mode())) throw new IllegalArgumentException("Mode is spread or all");
        boolean raw = Boolean.TRUE.equals(req.raw());
        Load load = req.load() == null ? new Load(null, null, null, null) : req.load();

        if (asked.size() > MAX_TARGETS) throw new IllegalArgumentException("At most " + MAX_TARGETS + " targets");
        List<TargetView> targets = new ArrayList<>();
        List<String> destinations = new ArrayList<>();
        for (Target t : asked) {
            if (t.connection() == null || t.connection().isBlank()) throw new IllegalArgumentException("Connection is required");
            if (t.destination() == null || t.destination().isBlank()) throw new IllegalArgumentException("Destination is required");
            // a connection's own var is filled in here, so the run, its report and its watches name the queue the environment has
            String written = connections.expand(t.connection(), t.destination().trim());
            targets.add(new TargetView(t.connection(), connections.get(t.connection()).name(), written));
            destinations.add(connections.resolve(written));
        }

        Map<String, String> headers = req.headers() == null ? Map.of() : Map.copyOf(req.headers());
        String match = req.match() == null || req.match().isBlank() ? null : req.match().trim();
        int timeout = req.timeoutSeconds() == null ? 10 : req.timeoutSeconds();
        // Faults at 0 percent are no faults: the run is then exactly the run it would be without the setting.
        List<String> faultKinds = req.invalid() == null || req.invalid().percent() == 0 ? null : Faults.kinds(req.invalid());
        Target invalidGoes = faultKinds == null ? null : req.invalid().expect();
        boolean verifying = wanted.stream().anyMatch(p -> p.expect() != null) || invalidGoes != null;
        if (raw && verifying) {
            throw new IllegalArgumentException("A raw message cannot be verified: every copy is the same text, so an arrival could not be told from the next. "
                    + "Turn Raw off and put {{uuid}} in the message, or send without verifying.");
        }
        List<TargetView> watches = new ArrayList<>();
        List<Loaded> parts = new ArrayList<>();
        AtomicLong probe = new AtomicLong();   // {{seq}} for the trial renders below; the run's own starts at 1
        RandomGenerator trial = new SplittableRandom();   // and the run's own random numbers stay untouched too
        for (Part p : wanted) {
            boolean unsaved = p.pack() == null || p.pack().isBlank();   // a body sent as it is, with no pack to take fields from
            if (unsaved && p.body() == null) throw new IllegalArgumentException("Pack is required");
            Map<String, String> fields = unsaved ? Map.of() : packs.get(p.pack()).fields();
            // A raw text that was given is not the template's: it is listed as raw, whatever template was open when it was typed.
            String name = raw && p.body() != null ? "raw" : p.template() == null || p.template().isBlank() ? (raw ? "raw" : "body") : p.template();
            Map<String, String> vars = new LinkedHashMap<>();
            if (req.vars() != null) vars.putAll(req.vars());
            if (p.vars() != null) vars.putAll(p.vars());
            int watch = p.expect() == null ? -1 : watch(watches, "The expectation", p.expect());
            Loaded loaded = new Loaded(name, p.body() != null ? p.body() : packs.read(p.pack(), p.template()),
                    fields, vars, Math.max(1, p.weight()), watch, watch >= 0 || faultKinds != null && verifying, raw);
            // fail now on a bad placeholder or a message nothing can match, not on message 1 of a run
            Rendered first = render(loaded, headers, match, probe, trial);
            String token = first.token();
            // A match that renders the same for two messages (a constant, say) would "recognise" any arrival as any message.
            if (match != null && loaded.tracked() && token.equals(render(loaded, headers, match, probe, trial).token())) {
                throw new IllegalArgumentException("The match expression gives every message of " + name + " the same text (" + token
                        + "). It must differ per message: repeat a value the body generates, such as {{ref}} with {{ref=uuid}} in the template.");
            }
            // A short token turns up in a message by accident: the 1 of message 1 is in "11:13" and in every amount with a 1 in it.
            if (loaded.tracked() && token.length() < MIN_TOKEN) {
                throw new IllegalArgumentException(name + " would be recognised by \"" + token + "\", which is too short: other text in a message can "
                        + "contain it, and the verdict could be wrong. Match on a value of at least " + MIN_TOKEN + " characters, such as {{uuid}} or {{id}}.");
            }
            // and on a kind of fault this template cannot be given
            if (faultKinds != null) {
                for (String kind : faultKinds) {
                    String why = Faults.refusal(kind, name, first.body(), first.token());
                    if (why != null) throw new IllegalArgumentException(why);
                }
            }
            parts.add(loaded);
        }
        int invalidWatch = invalidGoes == null ? -1 : watch(watches, "Where invalid messages arrive", invalidGoes);
        // Every watched destination has an observer; the ones the request names bring their buckets.
        Map<TargetView, List<Observer.Bucket>> buckets = new LinkedHashMap<>();
        if (req.watches() != null) {
            for (Watch w : req.watches()) {
                int at = watch(watches, "A watched destination", new Target(w.connection(), w.destination()));
                if (buckets.put(watches.get(at), Observer.check(w.buckets())) != null) {
                    throw new IllegalArgumentException(watches.get(at).connection() + " / " + watches.get(at).destination() + " is watched twice: put all its buckets in one entry");
                }
            }
        }
        List<Observer> observers = new ArrayList<>();
        List<String> watchDestinations = new ArrayList<>();
        for (TargetView w : watches) {
            watchDestinations.add(connections.resolve(w.destination()));
            observers.add(new Observer(w.connection(), w.destination(), buckets.get(w)));
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
        // A run without a seed picks one and says which, so any run can be repeated afterwards.
        // ponytail: below 2^48, so the number survives a trip through a browser, where numbers are doubles.
        long seed = req.seed() != null ? req.seed() : ThreadLocalRandom.current().nextLong(1L << 48);
        Plan plan = new Plan(req.rate(), load.rampUpSeconds() == null ? 0 : load.rampUpSeconds());
        Run run = new Run(label, List.copyOf(parts), List.copyOf(targets), List.copyOf(destinations), all, req.count(), plan, load, headers,
                List.copyOf(watches), List.copyOf(watchDestinations), List.copyOf(observers), invalidWatch, verifying,
                faultKinds == null ? 0 : req.invalid().percent(), faultKinds, seed, match, timeout);
        synchronized (runs) {
            // A run shares a destination's subscription with monitors and with runs that only watch it. Two runs that
            // both expect arrivals there are another matter: each would see the other's messages arrive, and a match
            // expression that is not unique across runs (a counter, say) would take one for its own.
            for (Run other : runs) {
                if (other.finishedAt != null) continue;
                for (String where : run.expecting) {
                    if (other.expecting.contains(where)) {
                        TargetView w = run.watches.get(run.watchKeys.indexOf(where));
                        throw new IllegalArgumentException("The run " + other.label + " is waiting for arrivals on " + w.connection() + " / " + w.destination()
                                + ". Two runs that expect arrivals on one destination could take each other's messages for their own: wait for it to end, or expect on another destination.");
                    }
                }
            }
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

    /** The place of a destination among the run's watches, added when it is new. Only a connection that can receive can be watched. */
    private int watch(List<TargetView> watches, String what, Target t) {
        target(what, t);
        Connections.View c = connections.get(t.connection());
        if (!connections.canReceive(c.id())) {
            throw new IllegalArgumentException(c.name() + " is a " + c.typeLabel() + " connection, which cannot receive. Expect the messages on another connection.");
        }
        TargetView where = new TargetView(c.id(), c.name(), connections.expand(c.id(), t.destination().trim()));
        int at = watches.indexOf(where);
        if (at >= 0) return at;
        watches.add(where);
        return watches.size() - 1;
    }

    /** Renders the body first, then the headers and the match expression, through one message so they can share values. */
    private Rendered render(Loaded part, Map<String, String> headers, String match, AtomicLong seq, RandomGenerator random) {
        if (part.raw()) return new Rendered(part.text(), headers, null);   // as typed: no placeholder is looked for, in the body or in a header
        TemplateEngine.Message message = engine.message(part.fields(), part.vars(), seq, random);
        String body = message.render(part.text());
        String token = message.firstId();   // taken now: an id a header generates later is not in the body
        Map<String, String> rendered = new LinkedHashMap<>();
        headers.forEach((name, value) -> rendered.put(name, message.render(value)));
        if (!part.tracked()) return new Rendered(body, rendered, null);
        if (match != null) token = message.render(match);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(part.template() + " has nothing to recognise its messages by when they arrive. "
                    + "Add {{uuid}} to the template or set a match expression.");
        }
        return new Rendered(body, rendered, token);
    }

    interface BrokerCall {
        void run() throws Exception;
    }

    /**
     * Makes a call into a broker's client on a thread of its own and stops waiting after a while. A broker that
     * is frozen, not gone, keeps its connections open and answers nothing: the client then waits for minutes
     * (IBM MQ sat in a check for over a minute, until the queue manager was let run again), and a run has to end.
     */
    // ponytail: the abandoned call keeps its thread until the client gives up. Sending is not covered: a send
    // into a frozen broker still blocks its sender until the client's own timeout.
    static void within(int seconds, BrokerCall call, BooleanSupplier noLongerNeeded) throws Exception {
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

    /** One sender's connections: a sender per target, and one per connection however many targets use it. Senders are not shared between threads. */
    private TransportType.Sender[] crew(Run run, boolean check, List<AutoCloseable> opened) {
        int size = run.targets.size();
        TransportType.Sender[] senders = new TransportType.Sender[size];
        Map<String, TransportType.Sender> mine = new HashMap<>();
        for (int i = 0; i < size; i++) {
            String id = run.targets.get(i).connectionId();
            try {
                senders[i] = mine.get(id);
                if (senders[i] == null) {
                    senders[i] = connections.open(id);
                    mine.put(id, senders[i]);
                    synchronized (opened) {
                        opened.add(senders[i]);
                    }
                    if (check) senders[i].check();
                    connections.noted(id, null);   // the console shows how a connection last did; a run opening it is such a time
                }
            } catch (RuntimeException e) {
                connections.noted(id, describe(e));
                throw size == 1 ? e : new IllegalStateException(run.targets.get(i).connection() + ": " + describe(e));
            } catch (Exception e) {
                connections.noted(id, describe(e));
                throw new IllegalStateException(size == 1 ? describe(e) : run.targets.get(i).connection() + ": " + describe(e));
            }
        }
        return senders;
    }

    /**
     * A run opens its own senders and subscriptions and closes them when it ends, however it ends. So a connection
     * edited or deleted meanwhile does not disturb it: it finishes on the settings it started with.
     *
     * A send that fails is counted, its error kept, and the run goes on: one refused message is a finding. The run
     * ends as failed when the connection is plainly gone: 100 sends in a row failed, or every send failed for ten
     * seconds, or the run is over and not one message went out. ({@link Stats#failed})
     */
    private void execute(Run run) {
        run.thread = Thread.currentThread();
        List<AutoCloseable> opened = new ArrayList<>();
        List<TransportType.Subscription> subscriptions = new ArrayList<>();
        Status end = Status.FAILED;
        boolean waiting = false;   // still awaiting arrivals when the timeout passed
        try {
            // Every connection is opened before the first message, so a broker that is down fails the run up front.
            List<TransportType.Sender[]> crews = new ArrayList<>();
            crews.add(crew(run, true, opened));
            // Listening starts before the first send, so a fast system under test cannot answer unheard.
            for (int i = 0; i < run.watches.size(); i++) {
                int watch = i;
                TargetView w = run.watches.get(i);
                try {
                    // shared with whoever else listens there: a monitor on the same reject queue goes on seeing every arrival
                    subscriptions.add(connections.share(w.connectionId(), run.watchDestinations.get(i), arrival -> run.arrived(watch, arrival), error -> {
                        run.broken = "Receiving from " + w.connection() + " / " + w.destination() + " failed: " + error;
                        wake(run);
                    }));
                } catch (Exception e) {
                    throw new IllegalStateException("Cannot receive from " + w.connection() + " / " + w.destination() + ": " + describe(e));
                }
            }
            // A run told how many senders to use opens them all now. Left to decide (0), it starts with one when it has a
            // rate to fall behind, and with a few when it is to go as fast as it can.
            boolean adding = run.wantedSenders == 0 && run.plan.rate() > 0;
            int senders = run.wantedSenders > 0 ? run.wantedSenders : adding ? 1 : Math.min(4, Runtime.getRuntime().availableProcessors());
            while (crews.size() < senders && !run.stopped()) crews.add(crew(run, false, opened));
            run.began = System.nanoTime();
            for (TransportType.Sender[] crew : crews) startSender(run, crew);
            long lastAdded = System.nanoTime();
            while (run.sending.get() > 0) {
                LockSupport.parkNanos(200_000_000L);
                // falling behind for a second with every sender busy: one more, over a connection of its own
                if (adding && run.lagging && System.nanoTime() - lastAdded > 1_000_000_000L && run.senders < MAX_AUTO_SENDERS && !run.stopped() && run.failure == null) {
                    run.lagging = false;
                    lastAdded = System.nanoTime();
                    try {
                        startSender(run, crew(run, false, opened));
                    } catch (RuntimeException e) {
                        adding = false;   // the broker will not give another connection: carry on with the senders there are
                    }
                }
            }
            run.ended = System.nanoTime();
            if (run.failure != null) throw new IllegalStateException(run.failure);
            // Nothing went out at all: there is no result, only the reason.
            if (!run.stopped() && run.stats.sent() == 0 && run.stats.failed() > 0) throw new IllegalStateException(run.stats.lastError());
            if (!run.watches.isEmpty() && !run.stopped()) {
                run.status = Status.VERIFYING;
                long deadline = System.nanoTime() + run.timeoutSeconds * 1_000_000_000L;
                synchronized (run.awaited) {
                    // The last arrival, cancel() and a failing receiver all notify. Messages expected nowhere are given
                    // the whole timeout to turn up somewhere after all: their absence is the result. So is a destination
                    // that is only watched: nobody can say when the last reject has come, and a receiver closed while
                    // messages are still on their way misses them, or on a queue takes them and drops them.
                    for (long left; (run.outstanding > 0 || run.nowhere > 0 || run.onlyWatched) && !run.stopped() && (left = deadline - System.nanoTime()) > 0;) {
                        run.awaited.wait(left / 1_000_000 + 1);
                    }
                    waiting = run.outstanding > 0;
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
            run.error = describe(e);
        } finally {
            if (run.began != 0 && run.ended == 0) run.ended = System.nanoTime();
            // A sender still inside a send (the run failed or was cancelled around it) is given a moment before its connection is closed under it.
            run.fail("ended");
            for (Thread t : run.threads) {
                try {
                    t.join(2_000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            List<AutoCloseable> mine = new ArrayList<>(subscriptions);
            synchronized (opened) {
                mine.addAll(opened);
            }
            for (AutoCloseable c : mine) {
                try {
                    within(brokerAnswerSeconds, c::close, () -> false);
                } catch (Throwable ignored) {
                    // the run's result stands; the rest must still be closed
                }
            }
            // The reports are written before the run is marked ended: whoever sees it ended can rely on them being there.
            Instant at = Instant.now();
            Detail detail = null;
            try {
                detail = run.ending(end, at);
                for (Consumer<Detail> listener : finishedListeners) {
                    try {
                        listener.accept(detail);
                    } catch (Throwable ignored) {
                        // a report that could not be written does not change how the run ended
                    }
                }
            } catch (Throwable ignored) {
                // nor does one that could not be put together
            }
            String outcome = detail != null && detail.run().verdict() != null ? detail.run().verdict().toLowerCase() : end.name().toLowerCase();
            totals.runs.computeIfAbsent(outcome, k -> new LongAdder()).increment();
            run.finishedAt = at;
            run.status = end;
        }
    }

    private void startSender(Run run, TransportType.Sender[] crew) {
        run.sending.incrementAndGet();
        run.senders++;
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Outgoing m;
                    synchronized (run.gate) {
                        m = run.next();
                    }
                    if (m == null) break;
                    deliver(run, m, crew);
                }
            } catch (Throwable e) {
                // A message that cannot be made (a placeholder that fails, an Error from a plugin) ends the run: the next would fail the same way.
                run.fail(describe(e));
            } finally {
                run.sending.decrementAndGet();
                LockSupport.unpark(run.thread);
            }
        }, "msgforge-send");
        t.setDaemon(true);
        run.threads.add(t);
        t.start();
    }

    private void deliver(Run run, Outgoing m, TransportType.Sender[] senders) {
        int size = senders.length, first = run.all ? 0 : (int) ((m.n() - 1) % size), last = run.all ? size - 1 : first;
        long began = System.nanoTime();
        try {
            for (int i = first; i <= last; i++) {
                if (m.awaited() == null) {
                    senders[i].send(run.destinations.get(i), m.body(), m.headers());
                } else {
                    String id = senders[i].sendForId(run.destinations.get(i), m.body(), m.headers());
                    if (id != null) run.sentAs(m.awaited(), id);
                }
            }
        } catch (Exception e) {   // whatever a connection type throws reaches here as a plain error with nothing secret in it
            long now = System.nanoTime();
            run.unawait(m.awaited());
            totals.failed.increment();
            String stop = run.stats.failed(now, now - run.began, describe(e));
            if (stop != null) run.fail(stop);
            return;
        }
        long now = System.nanoTime();
        run.stats.sent(now, now - run.began, now - began, m.fault() != null);
        totals.sent.increment();
        if (m.fault() != null) totals.invalid.increment();
        // ponytail: the Sent list keeps a run's first messages and then at most 50 a second, so a fast run shows a sample.
        long kept = run.lastKept.get();
        if (m.n() > KEPT_MESSAGES && (now - kept < 20_000_000L || !run.lastKept.compareAndSet(kept, now))) return;
        Instant at = Instant.now();
        synchronized (messages) {
            for (int i = first; i <= last; i++) {
                TargetView t = run.targets.get(i);
                messages.addFirst(new Sent(run.id, m.n(), i, t.connection(), t.destination(), run.parts.get(m.part()).template(), m.headers(), m.body(), at, m.fault()));
            }
            while (messages.size() > KEPT_MESSAGES) messages.removeLast();
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
        cancel(find(id));
    }

    // ponytail: wakes a run that is waiting, between sends or for arrivals. A send that is blocked inside a
    // broker call is left to return or time out on its own; interrupting it would need each client's cooperation.
    private static void cancel(Run run) {
        run.cancelled = true;
        wake(run);
    }

    private static void wake(Run run) {
        LockSupport.unpark(run.thread);
        run.threads.forEach(LockSupport::unpark);
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

    private Run find(String id) {
        synchronized (runs) {
            return runs.stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow(() -> new NoSuchElementException("no run " + id));
        }
    }

    /** One run in full. samples: with the newest bodies that arrived on its watched destinations, which a report leaves out. */
    public Detail detail(String id, boolean samples) {
        return find(id).detail(samples);
    }

    public Sent message(String runId, long n, int target) {
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
