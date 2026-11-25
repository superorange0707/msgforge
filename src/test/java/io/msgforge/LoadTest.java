package io.msgforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jms.core.JmsTemplate;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Load plans, invalid messages, watched destinations, monitors, reports and metrics. Dry run stands in for the broker. */
class LoadTest {

    private final TemplateEngine engine = new TemplateEngine();
    private final Packs packs = new Packs(new String[] {"packs"});
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private static final String ORDER = "{\"id\":\"{{ref=uuid}}\",\"items\":{{int 1 9}},\"note\":\"{{alnum 6}}\"}";
    private static final Runs.Target IN = new Runs.Target("dry-run", "ORDERS.IN"), OUT = new Runs.Target("dry-run", "ORDERS.OUT"),
            REJECTED = new Runs.Target("dry-run", "ORDERS.REJECTED");

    /** Every sender opened, and what each sent: a run with three senders opens three. */
    private static final class Sink implements TransportType {
        final List<List<String>> senders = new CopyOnWriteArrayList<>();
        final List<String> destinations = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        final AtomicInteger closed = new AtomicInteger();
        volatile Consumer<String> onSend = body -> { };

        public String id() { return "sink"; }
        public String label() { return "Sink"; }
        public List<Field> fields() { return List.of(); }

        public Sender open(Map<String, String> settings) {
            List<String> mine = new CopyOnWriteArrayList<>();
            senders.add(mine);
            return new Sender() {
                public void send(String destination, String body, Map<String, String> h) {
                    onSend.accept(body);
                    mine.add(body);
                    destinations.add(destination);
                    headers.add(h);
                }

                public void close() {
                    closed.incrementAndGet();
                }
            };
        }

        List<String> bodies() {
            return senders.stream().flatMap(List::stream).sorted().toList();
        }
    }

    private static Runs.View finished(Runs runs, String id) throws InterruptedException {
        for (int i = 0; i < 1500; i++) {
            Runs.View run = runs.list().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
            if (run.status() != Runs.Status.RUNNING && run.status() != Runs.Status.VERIFYING) return run;
            Thread.sleep(20);
        }
        throw new AssertionError("run " + id + " did not finish");
    }

    /** A send-only request to the sink. */
    private static Runs.Request to(String connection, String body, int count, double rate, Runs.Load load, Faults.Spec invalid, Long seed) {
        return new Runs.Request(null, null, null, null, count, rate, null, "demo", "order.json", body, null, connection, "Q", null, null, null,
                load, invalid, seed, null, null);
    }

    private static String only(Connections store) {
        return store.list().stream().filter(c -> !c.type().equals("dry-run")).findFirst().orElseThrow().id();
    }

    private Connections store(Path dir, TransportType... types) {
        Connections store = new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(types), new String[] {"HOME"});
        for (TransportType t : types) {
            if (!t.id().equals("dry-run")) store.save(new Connections.Connection(null, t.label(), t.id(), Map.of(), List.of("Q")));
        }
        return store;
    }

    // ---- the plan

    @Test
    void aRunRampsUpHoldsItsRateAcrossSendersAndStopsAtItsDuration(@TempDir Path dir) throws Exception {
        Sink sink = new Sink();
        Connections store = store(dir, sink);
        Runs runs = new Runs(packs, engine, store);
        // 200 a second reached over one second, for two seconds: 100 are due in the first second, 200 in the second
        Runs.View started = runs.start(to(only(store), "m{{seq}}", 0, 200, new Runs.Load(1, 2, 3, null), null, null));
        assertEquals(0, started.count());
        Runs.View run = finished(runs, started.id());
        assertEquals(Runs.Status.DONE, run.status());
        assertNull(run.verdict(), "nothing to verify and nothing failed");
        assertTrue(run.sent() >= 150 && run.sent() <= 301, "about 300 are due, fewer on a busy machine, and never more than the plan allows: " + run.sent());
        assertEquals(3, run.senders());
        assertEquals(3, sink.senders.size(), "each sender has a connection of its own");
        assertEquals(3, sink.closed.get());
        assertEquals(run.sent(), sink.bodies().size());
        assertEquals(run.sent(), sink.bodies().stream().distinct().count(), "no message was sent twice");
        assertTrue(run.elapsedMs() >= 1900 && run.elapsedMs() < 3500, "two seconds of sending: " + run.elapsedMs());
        assertEquals(200.0, run.targetRate());
        assertTrue(run.actualRate() > 60 && run.actualRate() < 160, "300 in 2 s on average: " + run.actualRate());
        assertTrue(run.latency() != null && run.latency().p50() <= run.latency().p95() && run.latency().p95() <= run.latency().p99() && run.latency().p99() <= run.latency().max());

        Runs.Detail detail = runs.detail(run.id(), false);
        List<Stats.Point> series = detail.series();
        assertTrue(series.size() == 2 || series.size() == 3, "one point a second: " + series);
        assertEquals(100.0, series.get(0).target(), "the first second rises from 0 to 200, 100 on average");
        assertEquals(200.0, series.get(1).target());
        assertTrue(series.get(0).sent() < series.get(1).sent(), "the ramp shows: " + series);
        assertEquals(run.sent(), series.stream().mapToLong(Stats.Point::sent).sum());

        // with a count as well, whichever comes first ends it
        Runs.View counted = finished(runs, runs.start(to(only(store), "m{{seq}}", 5, 0, new Runs.Load(null, 60, null, null), null, null)).id());
        assertEquals(5, counted.sent());
        assertEquals(5, counted.count());
    }

    @Test
    void aRunWithoutACountNeedsADurationOrHasToSayItIsUnlimited(@TempDir Path dir) throws Exception {
        Sink sink = new Sink();
        Connections store = store(dir, sink, new DryRunType());
        Runs runs = new Runs(packs, engine, store);
        String id = only(store);
        Function<Runs.Request, String> refused = r -> assertThrows(IllegalArgumentException.class, () -> runs.start(r)).getMessage();

        assertTrue(refused.apply(to(id, "m", 0, 100, null, null, null)).contains("needs a duration"));
        assertTrue(refused.apply(to(id, "m", 0, 100, new Runs.Load(null, null, null, false), null, null)).contains("unlimited"));
        assertEquals("A ramp-up needs a rate to rise to: set Per second", refused.apply(to(id, "m", 5, 0, new Runs.Load(10, null, null, null), null, null)));
        assertEquals("Senders must be 1 to 64, or 0 to add them as needed", refused.apply(to(id, "m", 5, 0, new Runs.Load(null, null, 65, null), null, null)));
        assertEquals("Duration must be 0 to 604800 seconds", refused.apply(to(id, "m", 5, 0, new Runs.Load(null, -1, null, null), null, null)));
        // a run that verifies keeps every message until it has arrived, so it has to say how many there will be
        assertTrue(refused.apply(new Runs.Request(null, null, null, null, 0, 10, null, "demo", "order.json", null, null, "dry-run", "Q",
                new Runs.Target("dry-run", "Q"), null, null, new Runs.Load(null, 5, null, null), null, null, null, null)).contains("at most 20000"));
        assertTrue(runs.list().isEmpty());

        // allowed to be unlimited, it goes on until it is cancelled
        String endless = runs.start(to(id, "m{{seq}}", 0, 0, new Runs.Load(null, null, null, true), null, null)).id();
        for (int i = 0; i < 250 && runs.list().get(0).sent() < 1000; i++) Thread.sleep(20);
        assertEquals(Runs.Status.RUNNING, runs.list().get(0).status());
        runs.cancel(endless);
        Runs.View cancelled = finished(runs, endless);
        assertEquals(Runs.Status.CANCELLED, cancelled.status());
        assertTrue(cancelled.sent() >= 1000);
        assertEquals(0, cancelled.count());
    }

    @Test
    void aRunLeftToDecideAddsSendersWhileItFallsBehind(@TempDir Path dir) throws Exception {
        Sink sink = new Sink();
        sink.onSend = body -> {
            try {
                Thread.sleep(20);   // one sender manages 50 a second
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
        Connections store = store(dir, sink);
        Runs runs = new Runs(packs, engine, store);
        Runs.View run = finished(runs, runs.start(to(only(store), "m{{seq}}", 0, 150, new Runs.Load(null, 6, 0, null), null, null)).id());
        assertTrue(run.senders() >= 3, "150 a second at 20 ms a send takes at least three: " + run.senders());
        assertEquals(run.senders(), sink.senders.size());
        List<Stats.Point> series = runs.detail(run.id(), false).series();
        assertTrue(series.get(0).sent() < 80, "one sender to begin with: " + series);
        assertTrue(series.get(series.size() - 2).sent() > 90, "and the rate is reached once there are enough: " + series);
    }

    @Test
    void theSameSeedGivesTheSameMessagesAndFaultsWhateverTheNumberOfSenders(@TempDir Path dir) throws Exception {
        Faults.Spec third = new Faults.Spec(30, null, null);
        List<List<String>> bodies = new ArrayList<>();
        List<Map<String, Long>> faults = new ArrayList<>();
        for (Object[] attempt : new Object[][] {{42L, 1}, {42L, 4}, {43L, 1}}) {
            Sink sink = new Sink();
            Path own = Files.createDirectory(dir.resolve("s" + bodies.size()));
            Connections store = store(own, sink);
            Runs runs = new Runs(packs, engine, store);
            Runs.View run = finished(runs, runs.start(to(only(store), ORDER + " {{seq}} {{customer}}", 200, 0, new Runs.Load(null, null, (Integer) attempt[1], null), third, (Long) attempt[0])).id());
            assertEquals(attempt[0], run.seed());
            assertEquals(200, run.sent());
            assertEquals(run.invalid(), run.faults().values().stream().mapToLong(Long::longValue).sum());
            assertTrue(run.invalid() > 30 && run.invalid() < 95, "about 60 of 200: " + run.invalid());
            bodies.add(withoutIds(sink.bodies()));
            assertEquals(200, sink.bodies().stream().map(LoadTest::idOf).filter(id -> id != null).distinct().count()
                    + sink.bodies().stream().filter(b -> idOf(b) == null).count(), "every id is its own, seed or no seed");
            faults.add(run.faults());
        }
        assertEquals(bodies.get(0), bodies.get(1), "one sender or four: message n has the same body and the same fault, ids apart");
        assertEquals(faults.get(0), faults.get(1));
        assertNotEquals(bodies.get(0), bodies.get(2));
        assertEquals(List.of("empty", "garbage", "truncated", "field-removed", "type-changed"), List.copyOf(faults.get(0).keySet()));
        assertTrue(faults.get(0).values().stream().allMatch(n -> n > 0), "every kind turned up: " + faults.get(0));

        // a run that is given no seed picks one and says which, so it can be repeated
        Sink sink = new Sink();
        Connections store = store(Files.createDirectory(dir.resolve("unseeded")), sink);
        Runs runs = new Runs(packs, engine, store);
        Runs.View first = finished(runs, runs.start(to(only(store), ORDER, 20, 0, null, null, null)).id());
        List<String> original = sink.bodies();
        sink.senders.clear();
        finished(runs, runs.start(to(only(store), ORDER, 20, 0, null, null, first.seed())).id());
        assertEquals(withoutIds(original), withoutIds(sink.bodies()));
        // The ids are not repeated: a message the last run left on a queue must not pass for an arrival of this one.
        assertTrue(java.util.Collections.disjoint(original.stream().map(LoadTest::idOf).toList(), sink.bodies().stream().map(LoadTest::idOf).toList()));
    }

    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private static String idOf(String body) {
        java.util.regex.Matcher m = ID.matcher(body);
        return m.find() ? m.group() : null;
    }

    /** The bodies with every UUID blanked, in an order that does not depend on them. */
    private static List<String> withoutIds(List<String> bodies) {
        // a body cut off inside its id ends in a piece of one
        return bodies.stream().map(b -> ID.matcher(b).replaceAll("<id>").replaceAll("\"id\":\"[0-9a-f-]*$", "\"id\":\"<cut>")).sorted().toList();
    }

    // ---- invalid messages

    @Test
    void eachKindOfFaultDoesWhatItSaysToJsonAndXml() throws Exception {
        String order = "{\n  \"id\": \"a1\",\n  \"items\": 3,\n  \"total\": 10.50,\n  \"lines\": [{\"sku\": \"X\", \"qty\": 2}]\n}";
        String shipment = "<?xml version=\"1.0\"?><Shipment id=\"s1\"><Carrier>Blue Harbor</Carrier><Weight unit=\"kg\">12.5</Weight></Shipment>";
        SplittableRandom random = new SplittableRandom(7);
        assertEquals("", Faults.apply("empty", order, random, null));
        assertFalse(Faults.apply("garbage", order, random, null).contains("{"));
        for (int i = 0; i < 50; i++) {
            String cut = Faults.apply("truncated", order, random, null);
            assertTrue(!cut.isEmpty() && cut.length() < order.length() && order.startsWith(cut));

            JsonNode before = json.readTree(order), removed = json.readTree(Faults.apply("field-removed", order, random, null));
            assertTrue(count(removed) < count(before) && count(removed) >= count(before) - 4, "one field fewer, with whatever was inside it: " + removed);
            String changed = Faults.apply("type-changed", order, random, null);
            JsonNode after = json.readTree(changed);
            assertEquals(count(before), count(after), "the same fields");
            assertNotEquals(types(before), types(after), "and one of another type: " + changed);
            assertTrue(changed.contains("10.50") || !after.path("total").isNumber(), "numbers that were not touched stay as written: " + changed);

            var builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            String without = Faults.apply("field-removed", shipment, random, null);
            builder.parse(new InputSource(new StringReader(without)));   // still XML
            assertTrue(without.startsWith("<?xml") && without.length() < shipment.length() + 40 && !without.equals(shipment), without);
            assertTrue(List.of("id=\"s1\"", "<Carrier>", "<Weight", "unit=\"kg\"").stream().filter(without::contains).count() < 4, without);
            String retyped = Faults.apply("type-changed", shipment, random, null);
            builder.parse(new InputSource(new StringReader(retyped)));
            assertTrue(retyped.contains("<Weight unit=\"kg\">not-a-number</Weight>"), "the one value that reads as a number: " + retyped);
        }
        // XML with nothing that reads as a number: a simple element becomes one with a child
        assertEquals("<a><b><value>text</value></b></a>", Faults.apply("type-changed", "<a><b>text</b></a>", random, null));
        // a DOCTYPE is not parsed, so no entity is ever resolved
        assertNull(Faults.apply("field-removed", "<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/hosts\">]><a><b>&x;</b></a>", random, null));

        // The field a message is recognised by is left alone: accepted all the same, the message has to be recognisable where it arrives.
        String keyed = "{\"orderId\":\"k-1234567\",\"items\":3,\"lines\":[{\"ref\":\"x\"}]}", keyedXml = "<Order id=\"k-1234567\"><Ref>k-1234567</Ref><Qty>2</Qty></Order>";
        for (int i = 0; i < 200; i++) {
            for (String kind : List.of("field-removed", "type-changed")) {
                assertTrue(Faults.apply(kind, keyed, random, "k-1234567").contains("\"orderId\":\"k-1234567\""), kind);
                String xml = Faults.apply(kind, keyedXml, random, "k-1234567");
                assertTrue(xml.contains("id=\"k-1234567\"") && xml.contains("<Ref>k-1234567</Ref>") && !xml.equals(keyedXml), kind + ": " + xml);
            }
        }
        assertFalse(java.util.stream.IntStream.range(0, 50).allMatch(i -> Faults.apply("field-removed", keyed, random, null).contains("orderId")), "without a token any field may go");
        // a parent of the token's field is not removed either, and with no other field left the kind is refused before the run
        assertEquals("{\"a\":{\"id\":\"k-1234567\"}}", Faults.apply("field-removed", "{\"a\":{\"id\":\"k-1234567\"},\"b\":1}", random, "k-1234567"));
        assertNull(Faults.apply("field-removed", "{\"a\":{\"id\":\"k-1234567\"}}", random, "k-1234567"));
        assertNull(Faults.apply("type-changed", "<a><id>k-1234567</id></a>", random, "k-1234567"));
        assertTrue(Faults.refusal("field-removed", "id-only.json", "{\"id\":\"k-1234567\"}", "k-1234567").startsWith(
                "id-only.json has no field besides the one its messages are recognised by (k-1234567), and \"field-removed\" leaves that one alone"));
        assertNull(Faults.refusal("field-removed", "id-only.json", "{\"id\":\"k-1234567\"}", null));

        // text that is neither is refused before a run starts, by name
        assertNull(Faults.apply("field-removed", "site,12.5,ok", random, null));
        assertEquals("reading.csv is neither JSON nor XML, so \"type-changed\" cannot find a field in it. Leave that kind out, or use it with a JSON or XML template.",
                Faults.refusal("type-changed", "reading.csv", "site,12.5,ok", null));
        assertNull(Faults.refusal("truncated", "reading.csv", "site,12.5,ok", null));
        assertTrue(Faults.refusal("field-removed", "broken.json", "{\"a\":", null).contains("does not parse as the JSON it looks like"));
        assertEquals("Unknown kind of invalid message: scrambled. The kinds are empty, garbage, truncated, field-removed, type-changed",
                assertThrows(IllegalArgumentException.class, () -> Faults.kinds(new Faults.Spec(5, List.of("scrambled"), null))).getMessage());
        assertThrows(IllegalArgumentException.class, () -> Faults.kinds(new Faults.Spec(101, null, null)));
    }

    private static int count(JsonNode node) {
        int n = 0;
        for (JsonNode child : node) n += 1 + count(child);
        return n;
    }

    private static String types(JsonNode node) {
        StringBuilder b = new StringBuilder(node.getNodeType().name());
        for (JsonNode child : node) b.append('(').append(types(child)).append(')');
        return b.toString();
    }

    /**
     * The system under test: valid orders go to ORDERS.OUT, anything else to ORDERS.REJECTED with a reason. It keeps the
     * headers on both, as a broker-side router would. route may overrule it, to model one that gets it wrong.
     */
    private static TransportType.Subscription orders(DryRunType dry, ObjectMapper json, Function<Boolean, String> route) {
        TransportType.Sender out = dry.open(Map.of());
        return dry.receive(Map.of(), "ORDERS.IN", arrival -> {
            String reason = null;
            try {
                JsonNode order = json.readTree(arrival.body());
                if (order == null || !order.path("id").isTextual()) reason = "no-id";
                else if (!order.path("items").isNumber()) reason = "bad-items";
                else if (!order.path("note").isTextual()) reason = "bad-note";
            } catch (Exception e) {
                reason = "not-json";
            }
            String to = route.apply(reason == null);
            if (to == null) return;
            try {
                out.send(to, to.endsWith("REJECTED") ? "{\"reason\":\"" + reason + "\",\"type\":\"order\"}" : arrival.body(), arrival.headers());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, e -> { });
    }

    private static Runs.Request ordersRun(int count, Faults.Spec invalid, int timeoutSeconds, List<Runs.Watch> watches) {
        // the token travels in a header too: a message that was emptied or cut off no longer has it in its body
        return new Runs.Request("orders", List.of(new Runs.Part("demo", "order.json", ORDER, 1, null, OUT)), List.of(IN), null, count, 0,
                Map.of("ref", "{{ref}}"), null, null, null, null, null, null, null, timeoutSeconds, "{{ref}}", null, invalid, 11L, watches, null);
    }

    @Test
    void invalidMessagesAreExpectedWhereTheRunSaysAndTheVerdictKnowsTheDifference(@TempDir Path dir) throws Exception {
        DryRunType dry = new DryRunType();
        Runs runs = new Runs(packs, engine, store(dir, dry));
        Faults.Spec quarter = new Faults.Spec(25, null, REJECTED);
        List<Runs.Watch> buckets = List.of(new Runs.Watch("dry-run", "ORDERS.REJECTED", List.of(new Observer.Bucket(null, "/reason", null, null),
                new Observer.Bucket("kind", null, "\"type\":\"(\\w+)\"", null), new Observer.Bucket(null, null, null, "ref"))));

        // it routes as it should: valid ones arrive, invalid ones are rejected, and both are what was expected
        try (TransportType.Subscription sut = orders(dry, json, valid -> valid ? "ORDERS.OUT" : "ORDERS.REJECTED")) {
            Runs.View run = finished(runs, runs.start(ordersRun(200, quarter, 5, buckets)).id());
            assertEquals("PASS", run.verdict(), run.checks().toString());
            assertEquals(2, run.checks().size());
            Runs.Check valid = run.checks().get(0), invalid = run.checks().get(1);
            assertFalse(valid.invalid());
            assertTrue(invalid.invalid());
            assertEquals("Dry run / ORDERS.OUT", valid.expected());
            assertEquals("Dry run / ORDERS.REJECTED", invalid.expected());
            assertEquals(200, valid.sent() + invalid.sent());
            assertEquals(run.invalid(), invalid.sent());
            assertTrue(invalid.sent() > 25 && invalid.sent() < 80, "about 50 of 200: " + invalid.sent());
            assertEquals(valid.sent(), valid.arrived());
            assertEquals(invalid.sent(), invalid.arrived(), "rejected as expected");
            assertEquals(0, valid.rejected() + invalid.accepted() + valid.misrouted() + invalid.misrouted() + valid.missing() + invalid.missing());
            // the Sent list says which fault a message got
            List<Runs.Sent> kept = runs.messages();
            assertTrue(kept.stream().anyMatch(m -> m.fault() != null) && kept.stream().anyMatch(m -> m.fault() == null));
            assertTrue(kept.stream().filter(m -> "empty".equals(m.fault())).allMatch(m -> m.body().isEmpty()));

            // and what arrived on the reject destination is sorted by the values pulled from it
            Runs.Detail detail = runs.detail(run.id(), true);
            Observer.View rejects = detail.watches().stream().filter(w -> w.destination().equals("ORDERS.REJECTED")).findFirst().orElseThrow();
            assertEquals(invalid.sent(), rejects.count());
            assertEquals(List.of("/reason", "kind", "ref"), rejects.tables().stream().map(Observer.Table::name).toList());
            Map<String, Long> reasons = new java.util.TreeMap<>();
            rejects.tables().get(0).values().forEach(c -> reasons.put(c.value(), c.count()));
            assertTrue(java.util.Set.of("not-json", "no-id", "bad-items", "bad-note").containsAll(reasons.keySet()) && reasons.size() >= 3, reasons.toString());
            assertEquals(invalid.sent(), reasons.values().stream().mapToLong(Long::longValue).sum());
            assertEquals(List.of(new Observer.Count("order", invalid.sent())), rejects.tables().get(1).values());
            assertTrue(rejects.recent().size() <= Observer.RECENT);
            Observer.Recent newest = rejects.recent().get(0);
            assertTrue(newest.message() != null && newest.bytes() > 20 && newest.values().size() == 3, newest.toString());
            assertTrue(rejects.samples().size() <= Observer.SAMPLES && rejects.samples().get(0).startsWith("{\"reason\""));
            assertTrue(runs.detail(run.id(), false).watches().get(0).samples().isEmpty(), "a report asks without the bodies");
            // every watched destination is counted, bucketed or not
            assertEquals(valid.sent(), run.watched().stream().filter(w -> w.destination().equals("ORDERS.OUT")).findFirst().orElseThrow().count());
        }

        // it accepts everything: the invalid ones turn up where the valid ones go
        try (TransportType.Subscription sut = orders(dry, json, valid -> "ORDERS.OUT")) {
            Runs.View run = finished(runs, runs.start(ordersRun(100, quarter, 1, null)).id());
            assertEquals("FAIL", run.verdict());
            Runs.Check invalid = run.checks().get(1);
            assertEquals(invalid.sent(), invalid.accepted(), "accepted although invalid");
            assertEquals(0, invalid.arrived() + invalid.misrouted() + invalid.missing());
            assertEquals(run.checks().get(0).sent(), run.checks().get(0).arrived());
        }

        // it rejects everything: the valid ones turn up where the invalid ones go
        try (TransportType.Subscription sut = orders(dry, json, valid -> "ORDERS.REJECTED")) {
            Runs.View run = finished(runs, runs.start(ordersRun(100, quarter, 1, null)).id());
            assertEquals("FAIL", run.verdict());
            Runs.Check valid = run.checks().get(0);
            assertEquals(valid.sent(), valid.rejected(), "valid but rejected");
            assertEquals(0, valid.arrived() + valid.misrouted() + valid.missing());
            assertEquals(run.checks().get(1).sent(), run.checks().get(1).arrived());
        }

        // it loses the invalid ones: missing, where a reject was expected
        try (TransportType.Subscription sut = orders(dry, json, valid -> valid ? "ORDERS.OUT" : null)) {
            Runs.View run = finished(runs, runs.start(ordersRun(100, quarter, 1, null)).id());
            assertEquals("FAIL", run.verdict());
            assertEquals(run.checks().get(1).sent(), run.checks().get(1).missing());

            // With nowhere named for them, that same behaviour is the right one: an invalid message is expected nowhere,
            // and the run waits its whole timeout to see that none turns up after all.
            long began = System.nanoTime();
            Runs.View quiet = finished(runs, runs.start(ordersRun(100, new Faults.Spec(25, null, null), 1, null)).id());
            assertTrue(System.nanoTime() - began >= 1_000_000_000L);
            assertEquals("PASS", quiet.verdict(), quiet.checks().toString());
            assertEquals("nowhere", quiet.checks().get(1).expected());
            assertEquals(quiet.checks().get(1).sent(), quiet.checks().get(1).arrived());
        }
        // and one that lets them through fails that run
        try (TransportType.Subscription sut = orders(dry, json, valid -> "ORDERS.OUT")) {
            Runs.View run = finished(runs, runs.start(ordersRun(100, new Faults.Spec(25, null, null), 1, null)).id());
            assertEquals("FAIL", run.verdict());
            assertEquals(run.checks().get(1).sent(), run.checks().get(1).accepted());
        }

        // a kind this template cannot be given is refused before the run starts
        Runs.Request csv = new Runs.Request(null, null, null, null, 5, 0, null, "demo", "reading.csv", null, null, "dry-run", "ORDERS.IN", null, null, null,
                null, new Faults.Spec(50, List.of("truncated", "field-removed"), null), null, null, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runs.start(csv)).getMessage().startsWith("reading.csv is neither JSON nor XML"));
        // at 0 percent the setting does nothing at all
        Runs.Request none = new Runs.Request(null, null, null, null, 5, 0, null, "demo", "reading.csv", null, null, "dry-run", "ORDERS.IN", null, null, null,
                null, new Faults.Spec(0, List.of("field-removed"), null), null, null, null);
        assertEquals(0, finished(runs, runs.start(none).id()).invalid());
    }

    @Test
    void aReplyIsRecognisedByItsCorrelationIdWhenItsBodySaysNothing(@TempDir Path dir) throws Exception {
        // Over real JMS: the system under test answers every request with an empty message whose correlation id is the
        // request's message id, which is all a reply to a message that could not be read can carry.
        EmbeddedActiveMQ broker = new EmbeddedActiveMQ().setConfiguration(new ConfigurationImpl()
                .setPersistenceEnabled(false).setSecurityEnabled(false).addAcceptorConfiguration("vm", "vm://0"));
        broker.start();
        try (ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("vm://0")) {
            TransportType overJms = new TransportType() {
                public String id() { return "jms"; }
                public String label() { return "JMS"; }
                public List<Field> fields() { return List.of(); }
                public Sender open(Map<String, String> settings) { return new JmsSender(cf); }
                public boolean canReceive() { return true; }
                public Subscription receive(Map<String, String> settings, String destination, Consumer<Arrival> onArrival, Consumer<Exception> onError) throws Exception {
                    return JmsReceiver.arrivals(cf, destination, onArrival, onError);
                }
            };
            Connections store = store(dir, overJms);
            String id = only(store);
            Runs runs = new Runs(packs, engine, store);
            JmsTemplate reply = new JmsTemplate(cf);
            List<String> requestIds = new CopyOnWriteArrayList<>();
            try (JmsReceiver sut = JmsReceiver.arrivals(cf, "REQ.Q", request -> {
                requestIds.add(request.headers().get("JMSMessageID"));
                reply.send(request.body().contains("\"id\"") ? "REPLY.Q" : "REJECT.Q", session -> {
                    jakarta.jms.Message empty = session.createTextMessage(null);
                    empty.setJMSCorrelationID(request.headers().get("JMSMessageID"));
                    empty.setStringProperty("reason", request.body().isEmpty() ? "empty" : "unreadable");
                    return empty;
                });
            }, e -> { })) {
                Runs.Request request = new Runs.Request(null, List.of(new Runs.Part("demo", "order.json", ORDER, 1, null, new Runs.Target(id, "REPLY.Q"))),
                        List.of(new Runs.Target(id, "REQ.Q")), null, 60, 0, null, null, null, null, null, null, null, null, 10, null,
                        new Runs.Load(null, null, 2, null), new Faults.Spec(40, List.of("empty", "garbage"), new Runs.Target(id, "REJECT.Q")), 5L,
                        List.of(new Runs.Watch(id, "REJECT.Q", List.of(new Observer.Bucket(null, null, null, "reason")))), null);
                Runs.View run = finished(runs, runs.start(request).id());
                assertEquals("PASS", run.verdict(), run.checks() + " " + run.error());
                assertEquals(60, run.checks().get(0).sent() + run.checks().get(1).sent());
                assertTrue(run.checks().get(1).sent() > 10, "enough invalid ones to mean something: " + run.checks());
                assertEquals(60, requestIds.stream().filter(m -> m != null && m.startsWith("ID:")).distinct().count());
                Observer.View rejects = runs.detail(run.id(), false).watches().stream().filter(w -> w.destination().equals("REJECT.Q")).findFirst().orElseThrow();
                assertEquals(java.util.Set.of("empty", "unreadable"), rejects.tables().get(0).values().stream().map(Observer.Count::value).collect(java.util.stream.Collectors.toSet()));
                assertTrue(rejects.recent().stream().allMatch(r -> r.message() != null && r.bytes() == 0), "each reject was matched to the message it answers");
            }
        } finally {
            broker.stop();
        }
    }

    @Test
    void theDryRunRouteStandsInForASystemThatRejectsWhatIsNotWellFormed(@TempDir Path dir) throws Exception {
        DryRunType dry = new DryRunType();
        Connections store = new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(dry));
        store.save(new Connections.Connection("dry-run", "Dry run", "dry-run", Map.of("route", "ORDERS.IN > ORDERS.OUT, invalid > ORDERS.REJECTED; A>B"), List.of()));
        assertEquals("ORDERS.IN > ORDERS.OUT, invalid > ORDERS.REJECTED; A>B", store.get("dry-run").summary());
        Runs runs = new Runs(packs, engine, store);

        // cut-off, emptied and garbled messages are rejected with a reason; the run expects exactly that
        List<Runs.Watch> byReason = List.of(new Runs.Watch("dry-run", "ORDERS.REJECTED", List.of(new Observer.Bucket(null, "/reason", null, null), new Observer.Bucket(null, null, null, "reason"))));
        Runs.View run = finished(runs, runs.start(ordersRun(120, new Faults.Spec(30, List.of("empty", "garbage", "truncated"), REJECTED), 5, byReason)).id());
        assertEquals("PASS", run.verdict(), run.checks().toString());
        Observer.View rejects = runs.detail(run.id(), false).watches().stream().filter(w -> w.destination().equals("ORDERS.REJECTED")).findFirst().orElseThrow();
        assertEquals(run.invalid(), rejects.count());
        assertEquals(java.util.Set.of("empty", "not-json", "not-a-format"), rejects.tables().get(0).values().stream().map(Observer.Count::value).collect(java.util.stream.Collectors.toSet()));
        assertEquals(rejects.tables().get(0).values(), rejects.tables().get(1).values(), "the reason is in the body and in a header");
        // the reject names the message it is about, so each one is recognised though the stand-in sent it before the send had returned
        assertTrue(rejects.recent().stream().allMatch(r -> r.message() != null), rejects.recent().toString());
        // a message with a field taken out is still well-formed: the stand-in lets it through, and the run says so
        Runs.View lenient = finished(runs, runs.start(ordersRun(60, new Faults.Spec(30, List.of("field-removed"), REJECTED), 1, null)).id());
        assertEquals("FAIL", lenient.verdict());
        assertEquals(lenient.checks().get(1).sent(), lenient.checks().get(1).accepted());
        // Also when the body's own id is all there is to recognise it by (no header carries the token here): the
        // fault never takes that field, so none of the accepted ones is counted as missing.
        Runs.View byBody = finished(runs, runs.start(new Runs.Request("by body", List.of(new Runs.Part("demo", "order.json", null, 1, null, OUT)), List.of(IN), null, 300, 0,
                null, null, null, null, null, null, null, null, 1, null, null, new Faults.Spec(40, List.of("field-removed", "type-changed"), REJECTED), 21L, null, null)).id());
        Runs.Check accepted = byBody.checks().get(1);
        assertTrue(accepted.sent() > 80, byBody.checks().toString());
        assertEquals(accepted.sent(), accepted.accepted(), byBody.checks().toString());
        assertEquals(0, accepted.missing());
        // a template with nothing but that id cannot be given such a fault, and the run says so before it starts
        Runs.Request idOnly = new Runs.Request(null, null, null, null, 5, 0, null, "demo", "order.json", "{\"id\":\"{{uuid}}\"}", null, "dry-run", "ORDERS.IN", OUT, 1, null,
                null, new Faults.Spec(50, List.of("field-removed"), REJECTED), null, null, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runs.start(idOnly)).getMessage().contains("has no field besides the one its messages are recognised by"));

        assertNull(DryRunType.flaw("<a><b/></a>"));
        assertNull(DryRunType.flaw(" [1, 2] "));
        assertEquals("not-xml", DryRunType.flaw("<a><b></a>"));
        assertEquals("not-xml", DryRunType.flaw("<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/hosts\">]><a>&x;</a>"));
        assertEquals("not-json", DryRunType.flaw("{\"a\":1"));
        assertEquals("not-a-format", DryRunType.flaw("site,12.5,ok"));
        // without "invalid >" what is not well-formed goes nowhere; a destination with no route is the loopback it always was
        TransportType.Sender out = dry.open(Map.of("route", "A > B"));
        List<String> got = new CopyOnWriteArrayList<>();
        try (TransportType.Subscription a = dry.subscribe(Map.of(), "A", got::add, e -> { }); TransportType.Subscription b = dry.subscribe(Map.of(), "B", got::add, e -> { });
             TransportType.Subscription c = dry.subscribe(Map.of(), "C", got::add, e -> { })) {
            out.send("A", "{}", Map.of());
            out.send("A", "nonsense", Map.of());
            out.send("C", "nonsense", Map.of());
            assertEquals(List.of("{}", "nonsense"), got);
        }
        for (String wrong : List.of("A", "A > ", "A > B > C", "A > B, B > C", "A > B, invalid > C, more")) {
            assertEquals("Write a route as FROM > TO, optionally followed by , invalid > REJECTS",
                    assertThrows(IllegalArgumentException.class, () -> dry.open(Map.of("route", wrong))).getMessage(), wrong);
        }
    }

    // ---- watching

    @Test
    void bucketsPullValuesOutOfArrivalsAndNothingFromOutsideGrowsWithoutBound() {
        Observer o = new Observer("Broker", "ORDERS.REJECTED", List.of(new Observer.Bucket(null, "/error/code", null, null),
                new Observer.Bucket("word", null, "reason=(\\w+)", null), new Observer.Bucket(null, null, "\\d{4}", null), new Observer.Bucket(null, null, null, "Origin")));
        o.arrived(new TransportType.Arrival("{\"error\":{\"code\":422,\"text\":\"reason=missing 2026\"}}", Map.of("origin", "edge-1"), null), 7L);
        o.arrived(new TransportType.Arrival("not json, reason=garbled", Map.of(), null), null);
        o.arrived(new TransportType.Arrival("", null, null), null);
        Observer.View view = o.view(true);
        assertEquals(3, view.count());
        assertEquals(List.of("/error/code", "word", "\\d{4}", "Origin"), view.tables().stream().map(Observer.Table::name).toList());
        assertEquals(List.of(new Observer.Count("(none)", 2), new Observer.Count("422", 1)), view.tables().get(0).values());
        assertEquals(List.of("422", "missing", "2026", "edge-1"), view.recent().get(2).values(), "a group's text, the whole match without one, a header whatever its case");
        assertEquals(7L, view.recent().get(2).message());
        assertEquals(List.of("(none)", "garbled", "(none)", "(none)"), view.recent().get(1).values());
        assertEquals(0, view.recent().get(0).bytes());
        assertTrue(view.rate() > 0 && view.lastAt() != null);
        assertEquals(new Observer.Summary("Broker", "ORDERS.REJECTED", 3, view.rate(), view.lastAt()), o.summary());

        // values come from outside: long ones are cut, and past 50 different ones the rest are counted together
        Observer many = new Observer("B", "D", List.of(new Observer.Bucket(null, "/v", null, null)));
        for (int i = 0; i < 500; i++) many.arrived(new TransportType.Arrival("{\"v\":\"" + i % 250 + "x".repeat(200) + "\"}", null, null), null);
        many.arrived(new TransportType.Arrival("{\"v\":\"short\"}", null, null), null);
        Observer.Table table = many.view(true).tables().get(0);
        assertEquals(51, table.values().size());
        assertEquals("(other)", table.values().get(0).value());
        assertTrue(table.values().stream().allMatch(c -> c.value().length() <= 81));
        assertEquals(50, many.view(true).recent().size());
        assertEquals(3, many.view(true).samples().size());
        many.reset();
        assertEquals(0, many.view(true).count());
        assertTrue(many.view(true).tables().get(0).values().isEmpty() && many.view(true).recent().isEmpty() && many.view(true).samples().isEmpty());

        // an expression that would backtrack for hours is cut off, and after three times is not asked again
        Observer slow = new Observer("B", "D", List.of(new Observer.Bucket(null, null, "(a+)+$", null)));
        long began = System.nanoTime();
        for (int i = 0; i < 10; i++) slow.arrived(new TransportType.Arrival("a".repeat(5000) + "!", null, null), null);
        assertTrue(System.nanoTime() - began < 5_000_000_000L, "ten arrivals did not take the thread for good");
        assertEquals(List.of(new Observer.Count("(too slow)", 10)), slow.view(false).tables().get(0).values());

        Function<Observer.Bucket, String> refused = b -> assertThrows(IllegalArgumentException.class, () -> Observer.check(List.of(b))).getMessage();
        assertEquals("A bucket needs exactly one of pointer, regex and header", refused.apply(new Observer.Bucket("x", "/a", "b", null)));
        assertEquals("A bucket needs exactly one of pointer, regex and header", refused.apply(new Observer.Bucket("x", null, " ", null)));
        assertTrue(refused.apply(new Observer.Bucket(null, null, "(unclosed", null)).startsWith("The bucket expression (unclosed is not a regular expression"));
        assertTrue(refused.apply(new Observer.Bucket(null, "reason", null, null)).contains("is not a JSON pointer"));
        assertTrue(refused.apply(new Observer.Bucket(null, null, "a".repeat(201), null)).contains("200 characters"));
        assertThrows(IllegalArgumentException.class, () -> Observer.check(java.util.Collections.nCopies(6, new Observer.Bucket(null, "/a", null, null))));
    }

    @Test
    void aMonitorAndARunOnOneDestinationShareItsSubscription(@TempDir Path dir) throws Exception {
        // A queue: each message goes to one consumer only, in turn. Two consumers would each get half.
        Map<String, List<Consumer<TransportType.Arrival>>> consumers = new ConcurrentHashMap<>();
        AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger(), turn = new AtomicInteger();
        TransportType queue = new TransportType() {
            public String id() { return "queue"; }
            public String label() { return "Queue"; }
            public List<Field> fields() { return List.of(); }
            public Sender open(Map<String, String> settings) {
                return (destination, body, headers) -> {
                    List<Consumer<Arrival>> on = consumers.getOrDefault(destination, List.of());
                    if (!on.isEmpty()) on.get(turn.getAndIncrement() % on.size()).accept(new Arrival(body, headers, null));
                };
            }
            public boolean canReceive() { return true; }
            public Subscription receive(Map<String, String> settings, String destination, Consumer<Arrival> onArrival, Consumer<Exception> onError) {
                opened.incrementAndGet();
                consumers.computeIfAbsent(destination, d -> new CopyOnWriteArrayList<>()).add(onArrival);
                return () -> {
                    closed.incrementAndGet();
                    consumers.get(destination).remove(onArrival);
                };
            }
        };
        Connections store = store(dir, queue);
        String id = only(store);
        Monitors monitors = new Monitors(store);
        Runs runs = new Runs(packs, engine, store);
        Runs.Target rejects = new Runs.Target(id, "REJECTS");
        Function<Integer, Runs.Request> loop = count -> new Runs.Request("loop", null, null, null, count, 0, null, "demo", "order.json", null, null, id, "REJECTS", rejects, 5, null);

        Monitors.View monitor = monitors.start(new Monitors.Request("Rejected", id, " REJECTS ", List.of(new Observer.Bucket(null, "/currency", null, null)), null));
        assertEquals("up", monitor.state());
        assertEquals("Rejected", monitor.name());
        assertEquals("REJECTS", monitor.watch().destination());
        assertEquals(1, opened.get());

        // the run joins the monitor's subscription: the queue has one consumer, and both see all 40 arrivals
        Runs.View run = finished(runs, runs.start(loop.apply(40)).id());
        assertEquals("PASS", run.verdict(), run.checks().toString());
        assertEquals(40, run.checks().get(0).arrived());
        assertEquals(40, monitors.list().get(0).watch().count());
        assertEquals(40, monitors.list().get(0).watch().tables().get(0).values().stream().mapToLong(Observer.Count::count).sum());
        assertEquals(1, opened.get(), "one subscription for the two of them");
        assertEquals(0, closed.get(), "and the run did not close it when it ended: the monitor is still listening");
        assertEquals(1, consumers.get("REJECTS").size());

        // a monitor that is stopped while a run waits does not cut the run off
        String waiting = runs.start(new Runs.Request("waits", null, null, null, 2, 0, null, "demo", "order.json", null, null, id, "ELSEWHERE", rejects, 30, null)).id();
        for (int i = 0; i < 250 && runs.list().get(0).status() != Runs.Status.VERIFYING; i++) Thread.sleep(20);
        monitors.stop(monitor.id());
        assertTrue(monitors.list().isEmpty());
        assertEquals(0, closed.get(), "the run still holds the subscription");
        // a second run that expects arrivals there too is refused: each would see the other's messages arrive
        String why = assertThrows(IllegalArgumentException.class, () -> runs.start(loop.apply(5))).getMessage();
        assertTrue(why.startsWith("The run waits is waiting for arrivals on Queue / REJECTS. Two runs that expect arrivals on one destination"), why);
        // one that only watches it is not: it joins, and so does a new monitor
        Runs.View watching = finished(runs, runs.start(new Runs.Request("watches", null, null, null, 3, 0, null, "demo", "order.json", null, null, id, "REJECTS", null, 1, null,
                null, null, null, List.of(new Runs.Watch(id, "REJECTS", null)), null)).id());
        assertEquals(3, watching.watched().get(0).count());
        Monitors.View again = monitors.start(new Monitors.Request(id, "REJECTS", null));
        assertEquals(1, opened.get());
        runs.cancel(waiting);
        finished(runs, waiting);
        assertEquals(0, closed.get(), "now the monitor holds it");
        monitors.stop(again.id());
        assertEquals(1, closed.get(), "the last listener to leave closes it");
        assertTrue(consumers.get("REJECTS").isEmpty());
        // and the next one to listen opens a new one
        assertEquals("PASS", finished(runs, runs.start(loop.apply(4)).id()).verdict());
        assertEquals(2, opened.get());
        assertEquals(2, closed.get());

        // reset, a second monitor on the same destination, and what cannot work
        DryRunType dry = new DryRunType();
        Connections loopback = store(Files.createDirectory(dir.resolve("dry")), dry, new Sink());
        Monitors more = new Monitors(loopback);
        Monitors.View view = more.start(new Monitors.Request("dry-run", "ORDERS.REJECTED", null));
        dry.open(Map.of()).send("ORDERS.REJECTED", "{}", Map.of());
        assertEquals(1, more.list().get(0).watch().count());
        assertEquals(0, more.reset(view.id()).watch().count());
        assertEquals("A monitor is already on Dry run / ORDERS.REJECTED",
                assertThrows(IllegalArgumentException.class, () -> more.start(new Monitors.Request("dry-run", "ORDERS.REJECTED", null))).getMessage());
        assertEquals("There is no configuration file to save a monitor in", assertThrows(IllegalArgumentException.class, () -> more.save(view.id())).getMessage());
        more.stop(view.id());
        assertThrows(java.util.NoSuchElementException.class, () -> more.stop(view.id()));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> more.start(new Monitors.Request(only(loopback), "Q", null))).getMessage().contains("cannot receive"));
        assertThrows(IllegalArgumentException.class, () -> more.start(new Monitors.Request("dry-run", " ", null)));
        assertThrows(IllegalArgumentException.class, () -> more.start(new Monitors.Request("dry-run", "Q", List.of(new Observer.Bucket(null, null, "(", null)))));
        assertThrows(IllegalArgumentException.class, () -> more.start(new Monitors.Request("x".repeat(81), "dry-run", "Q", null, null)));
        assertThrows(java.util.NoSuchElementException.class, () -> more.start(new Monitors.Request("nope", "Q", null)));
        for (int i = 0; i < Monitors.MAX; i++) more.start(new Monitors.Request("dry-run", "Q" + i, null));
        assertEquals("At most 10 monitors. Stop one first.", assertThrows(IllegalArgumentException.class, () -> more.start(new Monitors.Request("dry-run", "ONE.MORE", null))).getMessage());
        more.stopAll();
        assertTrue(more.list().isEmpty());
    }

    @Test
    void aRunThatOnlyWatchesWaitsItsTimeoutForWhatIsStillOnItsWay(@TempDir Path dir) throws Exception {
        DryRunType dry = new DryRunType();
        Connections store = store(dir, dry);
        Runs runs = new Runs(packs, engine, store);
        Monitors monitors = new Monitors(store);
        // A system that rejects late: half a second after each message it puts a reject on ORDERS.REJECTED.
        java.util.concurrent.ScheduledExecutorService late = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        TransportType.Sender out = dry.open(Map.of());
        try (TransportType.Subscription sut = dry.subscribe(Map.of(), "ORDERS.IN", body -> late.schedule(() -> {
            out.send("ORDERS.REJECTED", "{\"reason\":\"late\"}", Map.of());
            return null;
        }, 500, java.util.concurrent.TimeUnit.MILLISECONDS), e -> { })) {
            Function<Integer, Runs.Request> watchOnly = timeout -> new Runs.Request("watch only", null, null, null, 20, 0, null, "demo", "order.json", null, null, "dry-run", "ORDERS.IN",
                    null, timeout, null, null, null, null, List.of(new Runs.Watch("dry-run", "ORDERS.REJECTED", List.of(new Observer.Bucket(null, "/reason", null, null)))), null);
            long began = System.nanoTime();
            String id = runs.start(watchOnly.apply(2)).id();
            for (int i = 0; i < 100 && runs.list().get(0).status() == Runs.Status.RUNNING; i++) Thread.sleep(10);
            assertEquals(Runs.Status.VERIFYING, runs.list().get(0).status(), "sending is over, and it goes on listening");
            Runs.View run = finished(runs, id);
            assertTrue(System.nanoTime() - began >= 2_000_000_000L, "the whole timeout: nobody can say when the last reject has come");
            assertEquals(Runs.Status.DONE, run.status());
            assertNull(run.verdict(), "it expected nothing, so there is nothing to judge");
            assertEquals(20, run.watched().get(0).count(), "all twenty rejects, though the last was sent before any had arrived");
            assertEquals(List.of(new Observer.Count("late", 20)), runs.detail(id, false).watches().get(0).tables().get(0).values());

            // with a monitor on the same destination the subscription stays open anyway; the run still waits, so its own table is complete
            Monitors.View monitor = monitors.start(new Monitors.Request("dry-run", "ORDERS.REJECTED", null));
            Runs.View shared = finished(runs, runs.start(watchOnly.apply(2)).id());
            assertEquals(20, shared.watched().get(0).count());
            assertEquals(20, monitors.list().get(0).watch().count());
            monitors.stop(monitor.id());
            // cancelling ends the wait at once
            String cancelled = runs.start(watchOnly.apply(60)).id();
            for (int i = 0; i < 100 && runs.list().get(0).status() == Runs.Status.RUNNING; i++) Thread.sleep(10);
            runs.cancel(cancelled);
            assertEquals(Runs.Status.CANCELLED, finished(runs, cancelled).status());
        } finally {
            late.shutdownNow();
        }
    }

    private static Monitors.View monitor(Monitors monitors, String id, String state) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            Monitors.View m = monitors.list().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
            if (m.state().equals(state)) return m;
            Thread.sleep(20);
        }
        throw new AssertionError("monitor " + id + " is " + monitors.list().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow() + ", not " + state);
    }

    @Test
    void savedMonitorsStartWithTheConsoleAndOneThatIsDownTriesAgain(@TempDir Path dir) throws Exception {
        // A broker that can be switched off: while it is down, subscribing fails; and it can break a subscription that is open.
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        AtomicInteger attempts = new AtomicInteger();
        List<Consumer<Exception>> breakers = new CopyOnWriteArrayList<>();
        List<Consumer<TransportType.Arrival>> listeners = new CopyOnWriteArrayList<>();
        TransportType broker = new TransportType() {
            public String id() { return "broker"; }
            public String label() { return "Broker"; }
            public List<Field> fields() { return List.of(new Field("password", "Password", "", false, true)); }
            public Sender open(Map<String, String> settings) { return (destination, body, headers) -> { }; }
            public boolean canReceive() { return true; }
            public Subscription receive(Map<String, String> settings, String destination, Consumer<Arrival> onArrival, Consumer<Exception> onError) {
                attempts.incrementAndGet();
                if (down.get()) throw new IllegalStateException("connection refused (login s3cret)");
                breakers.add(onError);
                listeners.add(onArrival);
                return () -> listeners.remove(onArrival);
            }
        };
        Path file = dir.resolve("msgforge.json");
        Files.writeString(file, """
                {"connections":[{"id":"dry-run","name":"Dry run","type":"dry-run","settings":{},"destinations":[]},
                                {"id":"b","name":"Broker","type":"broker","settings":{"password":"s3cret"},"destinations":[]}],
                 "monitors":[
                  {"id":"rejects","name":"Rejected","connection":"dry-run","destination":"REJECTS.${HOME}","buckets":[{"pointer":"/reason"}]},
                  {"id":"remote","connection":"b","destination":"ORDERS.DEAD"},
                  {"name":"No such connection","connection":"gone","destination":"Q"},
                  {"id":"typo","connection":"dry-run","destination":"Q","buckets":[{"regex":"("}]},
                  {"id":"blank","connection":"dry-run"},
                  {"id":"secret","connection":"dry-run","destination":"${PATH}"},
                  null]}""");
        DryRunType dry = new DryRunType();
        ConfigStore config = new ConfigStore(file.toString(), new ObjectMapper());
        Connections store = new Connections(config, List.of(dry, broker), new String[] {"HOME"});
        Monitors monitors = new Monitors(store, config);
        monitors.retryUnitMillis = 20;   // the waits of 5, 10, 20, 40 and 60 seconds, as so many fiftieths of one

        // Loaded and listed before anything is tried; nothing listens until the console is up.
        assertEquals(List.of("starting", "starting", "starting", "down", "down", "starting"), monitors.list().stream().map(Monitors.View::state).toList());
        assertTrue(monitors.list().stream().allMatch(Monitors.View::saved));
        monitors.startSaved();

        // the one on a broker that answers is up, with its name, its buckets and its destination as written
        Monitors.View rejects = monitor(monitors, "rejects", "up");
        assertEquals("Rejected", rejects.name());
        assertEquals("REJECTS.${HOME}", rejects.watch().destination());
        dry.open(Map.of()).send("REJECTS." + System.getenv("HOME"), "{\"reason\":\"empty\"}", Map.of());
        assertEquals(List.of(new Observer.Count("empty", 1)), monitors.list().get(0).watch().tables().get(0).values());

        // the one whose broker is down is listed as down with the reason (and no secret in it), and tries again
        Monitors.View remote = monitor(monitors, "remote", "down");
        assertEquals("Cannot receive: connection refused (login ***)", remote.error());
        assertTrue(remote.retryAt() != null);
        for (int i = 0; i < 250 && attempts.get() < 3; i++) Thread.sleep(20);
        assertTrue(attempts.get() >= 3, "5 and 10 units after the first: " + attempts.get());
        down.set(false);
        remote = monitor(monitors, "remote", "up");
        assertNull(remote.error());
        assertNull(remote.retryAt());
        listeners.get(0).accept(new TransportType.Arrival("x", null, null));
        assertEquals(1, monitor(monitors, "remote", "up").watch().count());
        // receiving breaks later: down again, what it counted stays, and it comes back by itself
        int before = attempts.get();
        breakers.get(0).accept(new IllegalStateException("the broker closed the connection"));
        assertEquals(1, monitors.list().get(1).watch().count());
        for (int i = 0; i < 250 && attempts.get() == before; i++) Thread.sleep(20);
        remote = monitor(monitors, "remote", "up");
        assertEquals(1, remote.watch().count());
        assertEquals(1, listeners.size(), "the broken subscription was closed, the new one listens");

        // the others say what is wrong with them: a connection that is not there (it may yet be added), and entries only an edit can mend
        Monitors.View gone = monitors.list().get(2);
        assertEquals("No such connection", gone.name());
        assertEquals("down", monitor(monitors, gone.id(), "down").state());
        assertEquals("Cannot receive: no connection gone", monitors.list().get(2).error());
        assertTrue(monitors.list().get(3).error().startsWith("The bucket expression ( is not a regular expression"), monitors.list().get(3).error());
        assertNull(monitors.list().get(3).retryAt(), "trying again would not mend a bucket");
        assertEquals("This saved monitor names no destination", monitors.list().get(4).error());
        assertTrue(monitor(monitors, "secret", "down").error().startsWith("Cannot receive: Environment variable PATH may not be used"));

        // ---- saving from the console: a monitor started by hand is kept in the file, forgotten again, and removed when stopped
        Monitors.View byHand = monitors.start(new Monitors.Request("By hand", "dry-run", "ORDERS.DEAD", List.of(new Observer.Bucket("why", null, null, "reason")), null));
        assertFalse(byHand.saved());
        assertFalse(Files.readString(file).contains("By hand"));
        assertTrue(monitors.save(byHand.id()).saved());
        JsonNode saved = json.readTree(file.toFile()).get("monitors");
        assertEquals(7, saved.size());
        assertEquals("{\"id\":\"" + byHand.id() + "\",\"name\":\"By hand\",\"connection\":\"dry-run\",\"destination\":\"ORDERS.DEAD\","
                + "\"buckets\":[{\"name\":\"why\",\"pointer\":null,\"regex\":null,\"header\":\"reason\"}]}", saved.get(6).toString());
        assertEquals("REJECTS.${HOME}", saved.get(0).get("destination").asText(), "as written, not as resolved");
        assertEquals("s3cret", json.readTree(file.toFile()).get("connections").get(1).get("settings").get("password").asText(), "the rest of the file is as it was");
        assertFalse(monitors.forget(byHand.id()).saved());
        assertEquals(6, json.readTree(file.toFile()).get("monitors").size());
        assertEquals(7, monitors.list().size(), "forgotten, it goes on watching");
        // Stopping a monitor that is not saved removes it. A saved one only stops: it stays in the file and in the list,
        // keeps what it counted, hears nothing more, and can be started again.
        monitors.stop(byHand.id());
        assertEquals(6, monitors.list().size());
        dry.open(Map.of()).send("REJECTS." + System.getenv("HOME"), "{\"reason\":\"empty\"}", Map.of());
        long counted = monitor(monitors, "rejects", "up").watch().count();
        monitors.stop("rejects");
        Monitors.View stopped = monitor(monitors, "rejects", "stopped");
        assertTrue(stopped.saved() && stopped.error() == null && stopped.retryAt() == null);
        assertEquals(6, json.readTree(file.toFile()).get("monitors").size(), "still saved");
        dry.open(Map.of()).send("REJECTS." + System.getenv("HOME"), "{\"reason\":\"unheard\"}", Map.of());
        assertEquals(counted, monitors.list().get(0).watch().count());
        assertEquals("up", monitors.restart("rejects").state());
        dry.open(Map.of()).send("REJECTS." + System.getenv("HOME"), "{\"reason\":\"heard\"}", Map.of());
        assertEquals(counted + 1, monitors.list().get(0).watch().count());
        assertEquals("up", monitors.restart("rejects").state(), "starting one that is up changes nothing");
        // one that is down is stopped the same way, and then no longer tries
        monitors.stop("secret");
        assertEquals("stopped", monitor(monitors, "secret", "stopped").state());
        assertEquals("down", monitors.restart("secret").state());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> monitors.restart("typo")).getMessage().contains("is not a regular expression"));
        // out of the file is the other step: forgetting a stopped one removes it altogether
        monitors.stop("typo");
        assertEquals(6, monitors.list().size());
        assertFalse(monitors.forget("typo").saved());
        assertEquals(List.of("rejects", "remote", gone.id(), "blank", "secret"), monitors.list().stream().map(Monitors.View::id).toList());
        assertEquals(5, json.readTree(file.toFile()).get("monitors").size());
        // saved at once when asked to be
        Monitors.View kept = monitors.start(new Monitors.Request(null, "dry-run", "KEPT", null, true));
        assertTrue(kept.saved() && Files.readString(file).contains("\"KEPT\""));
        monitors.stopAll();

        // the file as it now is starts the same monitors again; one without a monitors section starts none
        ConfigStore again = new ConfigStore(file.toString(), new ObjectMapper());
        Monitors restarted = new Monitors(new Connections(again, List.of(dry, broker), new String[] {"HOME"}), again);
        assertEquals(List.of("rejects", "remote", gone.id(), "blank", "secret", kept.id()), restarted.list().stream().map(Monitors.View::id).toList());
        restarted.stopAll();
        ConfigStore empty = new ConfigStore(dir.resolve("none.json").toString(), new ObjectMapper());
        assertTrue(new Monitors(new Connections(empty, List.of(dry)), empty).list().isEmpty());
    }

    @Test
    void aSharedSubscriptionTellsEveryListenerWhenItBreaksAndTheNextOneOpensAnother(@TempDir Path dir) throws Exception {
        AtomicInteger opened = new AtomicInteger(), closed = new AtomicInteger();
        List<Consumer<Exception>> breakers = new CopyOnWriteArrayList<>();
        List<Consumer<TransportType.Arrival>> receivers = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean refuse = new java.util.concurrent.atomic.AtomicBoolean();
        TransportType type = new TransportType() {
            public String id() { return "t"; }
            public String label() { return "T"; }
            public List<Field> fields() { return List.of(); }
            public Sender open(Map<String, String> settings) { return (destination, body, headers) -> { }; }
            public boolean canReceive() { return true; }
            public Subscription receive(Map<String, String> settings, String destination, Consumer<Arrival> onArrival, Consumer<Exception> onError) {
                if (refuse.get()) throw new IllegalStateException("refused");
                opened.incrementAndGet();
                breakers.add(onError);
                receivers.add(onArrival);
                return closed::incrementAndGet;
            }
        };
        Connections store = store(dir, type);
        String id = only(store);
        List<String> a = new CopyOnWriteArrayList<>(), b = new CopyOnWriteArrayList<>(), errors = new CopyOnWriteArrayList<>();
        TransportType.Subscription first = store.share(id, "Q", arrival -> a.add(arrival.body()), e -> errors.add("a: " + e));
        TransportType.Subscription second = store.share(id, "Q", arrival -> { b.add(arrival.body()); throw new IllegalStateException("b cannot cope"); }, e -> errors.add("b: " + e));
        TransportType.Subscription other = store.share(id, "OTHER", arrival -> { }, e -> { });
        assertEquals(2, opened.get(), "one for Q, one for OTHER");
        receivers.get(0).accept(new TransportType.Arrival("m1", null, null));
        receivers.get(0).accept(new TransportType.Arrival("m2", null, null));
        assertEquals(List.of("m1", "m2"), a, "one listener's trouble with an arrival is not the other's");
        assertEquals(List.of("m1", "m2"), b);

        breakers.get(0).accept(new IllegalStateException("session closed"));
        assertEquals(List.of("a: session closed", "b: session closed"), errors);
        // it takes no new listeners: the next one opens another, while the two on the broken one still hold it
        TransportType.Subscription third = store.share(id, "Q", arrival -> { }, e -> { });
        assertEquals(3, opened.get());
        first.close();
        first.close();
        assertEquals(0, closed.get());
        second.close();
        assertEquals(1, closed.get(), "the broken one is closed when its last listener has left, and once");
        third.close();
        other.close();
        assertEquals(3, closed.get());

        // one that cannot be opened is the caller's error, and leaves nothing behind for the next to join
        refuse.set(true);
        assertEquals("refused", assertThrows(IllegalStateException.class, () -> store.share(id, "Q", arrival -> { }, e -> { })).getMessage());
        refuse.set(false);
        store.share(id, "Q", arrival -> { }, e -> { }).close();
        assertEquals(4, opened.get());
        assertEquals(4, closed.get());
    }

    // ---- raw, and names from the environment

    @Test
    void aRawMessageGoesOutAsTypedAndDestinationsMayNameTheEnvironment(@TempDir Path dir) throws Exception {
        Sink sink = new Sink();
        Connections store = store(dir, sink, new DryRunType());
        Runs runs = new Runs(packs, engine, store);
        String id = only(store);

        // no pack, no template: the text and the headers as they are, placeholders and all
        Runs.Request raw = new Runs.Request(null, null, null, null, 2, 0, Map.of("note", "{{uuid}}"), null, null, "{{uuid}} is not filled in\n", null, id, "Q", null, null, null,
                null, null, null, null, true);
        Runs.View run = finished(runs, runs.start(raw).id());
        assertEquals(Runs.Status.DONE, run.status());
        assertEquals("raw", run.label());
        assertEquals(List.of("{{uuid}} is not filled in\n", "{{uuid}} is not filled in\n"), sink.bodies());
        assertEquals(Map.of("note", "{{uuid}}"), sink.headers.get(0));
        // every copy is the same, so there is nothing to tell arrivals apart by
        Runs.Request verified = new Runs.Request(null, null, null, null, 2, 0, null, null, null, "text", null, "dry-run", "Q", new Runs.Target("dry-run", "Q"), null, null,
                null, null, null, null, true);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runs.start(verified)).getMessage().startsWith("A raw message cannot be verified"));
        // without raw the same request is a body with placeholders, as before
        Runs.Request rendered = new Runs.Request(null, null, null, null, 1, 0, null, null, null, "{{seq}}", null, id, "Q", null, null, null);
        assertEquals("body", finished(runs, runs.start(rendered).id()).label());
        assertEquals("1", sink.senders.get(sink.senders.size() - 1).get(0));
        assertEquals("Pack is required", assertThrows(IllegalArgumentException.class, () -> runs.start(Runs.Request.single(null, "order.json", null, id, "Q", 1, 0, null))).getMessage());

        // ${NAME} in a destination is filled in when the run starts, under the same rule as in a connection's settings
        sink.destinations.clear();
        Runs.Request named = new Runs.Request(null, null, List.of(new Runs.Target(id, "ORDERS.${HOME}")), null, 1, 0, null, "demo", "order.json", null, null, null, null, null, null, null);
        Runs.View there = finished(runs, runs.start(named).id());
        assertEquals(List.of("ORDERS." + System.getenv("HOME")), sink.destinations);
        assertEquals("ORDERS.${HOME}", there.targets().get(0).destination(), "shown as it was written");
        Runs.Request notAllowed = new Runs.Request(null, null, List.of(new Runs.Target(id, "${PATH}")), null, 1, 0, null, "demo", "order.json", null, null, null, null, null, null, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> runs.start(notAllowed)).getMessage().startsWith("Environment variable PATH may not be used"));
        // and in what a run expects and watches
        DryRunType dry = new DryRunType();
        Connections loop = store(Files.createDirectory(dir.resolve("loop")), dry);
        Runs looped = new Runs(packs, engine, loop);
        Runs.Request expecting = new Runs.Request(null, null, null, null, 3, 0, null, "demo", "order.json", null, null, "dry-run", "Q.${HOME}",
                new Runs.Target("dry-run", "Q.${HOME}"), 5, null, null, null, null, List.of(new Runs.Watch("dry-run", "Q.${HOME}", null)), null);
        Runs.View arrived = finished(looped, looped.start(expecting).id());
        assertEquals("PASS", arrived.verdict());
        assertEquals("Dry run / Q.${HOME}", arrived.checks().get(0).expected());
        assertEquals(3, arrived.watched().get(0).count());
    }

    // ---- what a run measures

    @Test
    void statisticsStayTheSameSizeHoweverLongTheRun() {
        Stats stats = new Stats();
        Runs.Plan plan = new Runs.Plan(10, 0);
        // a message a second for 2,000 seconds: the series holds 600 points at most, so it has halved its resolution twice
        for (long second = 0; second < 2000; second++) {
            stats.sent(second * 1_000_000_000L, second * 1_000_000_000L, (second % 100 + 1) * 1_000_000L, second % 10 == 0);
            if (second % 4 == 0) assertNull(stats.failed(second * 1_000_000_000L, second * 1_000_000_000L, "refused " + second % 30));
        }
        List<Stats.Point> series = stats.series(2000L * 1_000_000_000L, plan);
        assertEquals(500, series.size());
        assertEquals(4.0, series.get(0).seconds());
        assertEquals(1996, series.get(499).t());
        assertEquals(2000, series.stream().mapToLong(Stats.Point::sent).sum());
        assertEquals(500, series.stream().mapToLong(Stats.Point::failed).sum());
        assertEquals(200, series.stream().mapToLong(Stats.Point::invalid).sum());
        assertTrue(series.stream().allMatch(p -> p.sent() == 4 && p.target() == 10.0));
        assertEquals(2000, stats.sent());
        assertEquals(200, stats.invalid());

        // send times between 1 and 100 ms, evenly: the percentiles are right to a bucket's width
        Stats.Latency latency = stats.latency();
        assertEquals(50, latency.p50(), 5);
        assertEquals(95, latency.p95(), 8);
        assertEquals(99, latency.p99(), 8);
        assertEquals(100.0, latency.max());

        // 15 different errors are listed with their counts, most frequent first; past 20 the rest are counted together
        assertEquals(15, stats.errors().size());
        for (int i = 0; i < 40; i++) stats.sent(0, 0, 1, false);
        for (int i = 0; i < 30; i++) assertNull(stats.failed(i, i, "new error " + i + " " + "x".repeat(400)), "a send in between, so no streak");
        List<Stats.ErrorCount> errors = stats.errors();
        assertEquals(21, errors.size());
        assertEquals(Stats.OTHER_ERRORS, errors.stream().filter(e -> e.count() == 25).findFirst().orElseThrow().message());
        assertTrue(errors.stream().allMatch(e -> e.message().length() <= Stats.MAX_ERROR_TEXT + 1));

        // sends that only fail end a run: 100 in a row, or for ten seconds
        Stats streak = new Stats();
        for (int i = 0; i < 99; i++) assertNull(streak.failed(i, i, "down"));
        assertEquals("Stopped after 100 sends in a row failed: down", streak.failed(99, 99, "down"));
        Stats slow = new Stats();
        assertNull(slow.failed(0, 0, "down"), "one failure is one failure, however long ago");
        assertNull(slow.failed(9_000_000_000L, 9_000_000_000L, "down"));
        assertEquals("Stopped after every send failed for 12 seconds: down", slow.failed(12_000_000_000L, 12_000_000_000L, "down"));
        Stats recovering = new Stats();
        assertNull(recovering.failed(0, 0, "down"));
        recovering.sent(5_000_000_000L, 5_000_000_000L, 1, false);
        assertNull(recovering.failed(12_000_000_000L, 12_000_000_000L, "down"), "a send in between starts the count again");

        // a plan's rate rises in a straight line and then holds
        Runs.Plan ramp = new Runs.Plan(100, 10);
        assertEquals(50, ramp.rateAt(5));
        assertEquals(100, ramp.rateAt(30));
        assertEquals(125, ramp.dueBy(5));
        assertEquals(500 + 2000, ramp.dueBy(30));
    }

    // ---- reports and metrics

    @Test
    void aRunIsAReportInEveryFormatAndTheReportsFolderKeepsOnlyTheNewest(@TempDir Path dir) throws Exception {
        DryRunType dry = new DryRunType();
        Sink sink = new Sink();
        Connections store = store(dir, dry, sink);
        Monitors monitors = new Monitors(store);
        Runs runs = new Runs(packs, engine, store);
        Path folder = dir.resolve("reports");
        Reports reports = new Reports(runs, json, folder.toString(), 2);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("notes.txt"), "not a report");
        Files.writeString(folder.resolve("msgforge-notes.html"), "named like one, and still not written by msgforge");

        Runs.View run;
        try (TransportType.Subscription sut = orders(dry, json, valid -> valid ? "ORDERS.OUT" : "ORDERS.REJECTED")) {
            Runs.Request request = new Runs.Request("=cmd <b>orders</b> & \"co\"", List.of(new Runs.Part("demo", "order.json", ORDER, 1, null, OUT)), List.of(IN), null, 80, 0,
                    Map.of("ref", "{{ref}}"), null, null, null, null, null, null, null, 5, "{{ref}}", new Runs.Load(null, null, 2, null), new Faults.Spec(25, null, REJECTED), 3L,
                    List.of(new Runs.Watch("dry-run", "ORDERS.REJECTED", List.of(new Observer.Bucket(null, "/reason", null, null)))), null);
            run = finished(runs, runs.start(request).id());
        }
        assertEquals("PASS", run.verdict());
        Runs.Detail detail = runs.detail(run.id(), false);

        JsonNode asJson = json.readTree(reports.render(detail, "json"));
        assertEquals(run.id(), asJson.path("run").path("id").asText());
        assertEquals(80, asJson.path("run").path("sent").asInt());
        assertEquals(3, asJson.path("run").path("seed").asInt());
        assertTrue(asJson.path("series").size() >= 1 && asJson.path("watches").size() == 2);
        assertEquals(0, asJson.path("watches").findValues("samples").stream().mapToInt(JsonNode::size).sum(), "no bodies in a report");

        String csv = reports.render(detail, "csv");
        assertTrue(csv.startsWith("name,value\r\nlabel,\"'=cmd <b>orders</b> & \"\"co\"\"\"\r\n"), "a label a spreadsheet would run is defused, and quoted: " + csv);
        assertTrue(csv.contains("\r\nsent,80\r\n") && csv.contains("\r\nseed,3\r\n") && csv.contains("\r\nverdict,PASS\r\n"), csv);
        assertTrue(csv.contains("check,messages,expected,sent,arrived") && csv.contains("order.json,invalid,Dry run / ORDERS.REJECTED,"), csv);
        assertTrue(csv.contains("bucket /reason,arrivals\r\n") && csv.contains("invalid_kind,messages\r\n"), csv);
        String series = reports.render(detail, "series");
        assertTrue(series.startsWith("t_seconds,seconds,target_rate,sent,failed,invalid,sent_rate\r\n0,"), series);
        assertEquals(detail.series().size() + 1, series.split("\r\n").length);

        String html = reports.render(detail, "html");
        assertTrue(html.startsWith("<!doctype html>") && html.contains("<svg class=\"chart\"") && html.contains("rejected as expected"), "one page with its chart drawn in");
        assertTrue(html.contains("=cmd &lt;b&gt;orders&lt;/b&gt; &amp; &quot;co&quot;") && !html.contains("<b>orders"), "text from a request is text in the page");
        assertFalse(html.contains("<script") || html.contains("http://") || html.contains("https://"), "it needs nothing else to open, and runs nothing");
        assertTrue(html.contains("@media print"));
        assertThrows(IllegalArgumentException.class, () -> reports.render(detail, "pdf"));
        assertEquals("cut &lt; &amp; &#39;", Reports.esc("cut < & '"));
        assertEquals("'-1+1", Reports.cell("-1+1"));
        assertEquals("-1", Reports.cell(-1));
        assertEquals("\"a,b\"", Reports.cell("a,b"));

        // the folder: the run above was saved when it ended, in all four formats, under a name built from its start, id and label
        Reports.Saved saved = reports.saved();
        assertTrue(saved.enabled());
        assertEquals(1, saved.reports().size());
        Reports.Report first = saved.reports().get(0);
        assertEquals(run.id(), first.id());
        assertEquals("cmd-b-orders-b-co", first.label());
        assertEquals(4, first.files().size());
        assertTrue(first.files().get(0).matches("msgforge-\\d{8}-\\d{9}-" + run.id() + "-cmd-b-orders-b-co\\.html"), first.files().toString());
        assertEquals(html.replaceAll("still running|· [\\d.]+ s ·", ""), reports.read(first.files().get(0)).replaceAll("still running|· [\\d.]+ s ·", ""));
        // two more runs: the folder keeps two, the oldest goes, and files msgforge did not write stay
        String second = finished(runs, runs.start(to(only(store), "m", 3, 0, null, null, null)).id()).id();
        String third = finished(runs, runs.start(to(only(store), "m", 3, 0, null, null, null)).id()).id();
        // no waiting: a run is not shown as ended before its reports are in the folder
        assertEquals(List.of(third, second), reports.saved().reports().stream().map(Reports.Report::id).toList());
        try (var files = Files.list(folder)) {
            assertEquals(10, files.count(), "two runs in four formats, and the two files that were there");
        }
        assertEquals("not a report", Files.readString(folder.resolve("notes.txt")));

        // only a name this class writes can be read: nothing else in the folder, nothing outside it
        for (String name : List.of("notes.txt", "msgforge-notes.html", "../c.json", "..%2Fc.json", first.files().get(0), "")) {
            assertThrows(java.util.NoSuchElementException.class, () -> reports.read(name), name);
        }
        // without a folder nothing is saved and nothing can be read
        Reports off = new Reports(runs, json, " ", 50);
        assertEquals(new Reports.Saved(false, 50, List.of()), off.saved());
        assertThrows(java.util.NoSuchElementException.class, () -> off.read(reports.saved().reports().get(0).files().get(0)));

        // ---- metrics: totals since the start, runs by outcome, arrivals by watched destination
        monitors.start(new Monitors.Request("dry-run", "DEAD\"LET\nTER\\Q", null));
        dry.open(Map.of()).send("DEAD\"LET\nTER\\Q", "x", Map.of());
        String metrics = new Metrics(runs, monitors).metrics();
        assertTrue(metrics.contains("# TYPE msgforge_messages_sent_total counter\nmsgforge_messages_sent_total 86\n"), metrics);
        assertTrue(metrics.contains("msgforge_messages_failed_total 0\n") && metrics.contains("msgforge_messages_invalid_total " + run.invalid() + "\n"), metrics);
        assertTrue(metrics.contains("msgforge_runs_total{outcome=\"pass\"} 1\n") && metrics.contains("msgforge_runs_total{outcome=\"done\"} 2\n")
                && metrics.contains("msgforge_runs_total{outcome=\"failed\"} 0\n"), metrics);
        assertTrue(metrics.contains("msgforge_runs_active 0\n") && metrics.contains("msgforge_send_rate 0\n"), metrics);
        assertTrue(metrics.contains("msgforge_arrivals_total{connection=\"Dry run\",destination=\"ORDERS.REJECTED\",source=\"run\"} " + run.invalid() + "\n"), metrics);
        assertTrue(metrics.contains("msgforge_arrivals_total{connection=\"Dry run\",destination=\"DEAD\\\"LET\\nTER\\\\Q\",source=\"monitor\"} 1\n"), "a label from a user is escaped: " + metrics);
        assertFalse(metrics.contains("{\"id\"") || metrics.contains(System.getenv("HOME")), "numbers and names, never content");
        for (String line : metrics.split("\n")) {
            assertTrue(line.startsWith("# ") || line.matches("[a-z_]+(\\{[^\\n]*})? -?[0-9.]+"), "every line is a comment or a sample: " + line);
        }
        assertEquals("a\\\\b\\\"c\\nd", Metrics.label("a\\b\"c\nd\u0007"));
        // two connections with one name are one series, added up: two samples with the same labels would make the whole answer invalid
        store.save(new Connections.Connection(null, "Dry run", "dry-run", Map.of(), List.of()));
        String twin = store.list().stream().filter(c -> c.name().equals("Dry run") && !c.id().equals("dry-run")).findFirst().orElseThrow().id();
        finished(runs, runs.start(new Runs.Request(null, null, null, null, 2, 0, null, "demo", "order.json", null, null, twin, "ORDERS.REJECTED", null, 1, null,
                null, null, null, List.of(new Runs.Watch(twin, "ORDERS.REJECTED", null)), null)).id());
        List<String> samples = java.util.Arrays.stream(new Metrics(runs, monitors).metrics().split("\n")).filter(l -> !l.startsWith("#")).map(l -> l.substring(0, l.lastIndexOf(' '))).toList();
        assertEquals(samples.size(), samples.stream().distinct().count(), "no series twice: " + samples);
        assertTrue(new Metrics(runs, monitors).metrics().contains("destination=\"ORDERS.REJECTED\",source=\"run\"} " + (run.invalid() + 2) + "\n"));
        assertEquals(200, Metrics.label("\\".repeat(500)).length() / 2);
        monitors.stopAll();
    }
}
