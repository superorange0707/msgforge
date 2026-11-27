package io.msgforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Several environments in one msgforge: each a connection with its own names for its queues, chosen per run. */
class EnvironmentsTest {

    private static final TransportType FAKE = new TransportType() {
        public String id() { return "fake"; }
        public String label() { return "Fake"; }
        public List<Field> fields() {
            return List.of(new Field("host", "Host", "", true, false), new Field("user", "User", "", false, false),
                    new Field("password", "Password", "", false, true));
        }
        public Sender open(Map<String, String> settings) { throw new UnsupportedOperationException(); }
        public String summary(Map<String, String> s) { return s.getOrDefault("host", ""); }
    };

    private static final Map<String, String> ENV = Map.of("MSGFORGE_SIT_HOST", "mq-sit-1(1414)", "MSGFORGE_SIT_USER", "tester",
            "MSGFORGE_SIT_PASSWORD", "dummy-pw", "MSGFORGE_SIT_IN", "SIT.IN", "MSGFORGE_UAT_HOST", "mq-uat-1(1414)");

    private static Connections connections(Path file, String... show) {
        Connections c = new Connections(new ConfigStore(file.toString(), new ObjectMapper()), List.of(FAKE, new DryRunType()), new String[0], show);
        c.env = ENV::get;
        return c;
    }

    @Test
    void aConnectionsOwnVarsNameItsQueuesAndAreKeptWhenAnEditLeavesThemOut(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("msgforge.json");
        Connections store = connections(file, "MSGFORGE_ENV");
        String sit = store.save(new Connections.Connection(null, "SIT", "fake", Map.of("host", "h"), List.of("${Input}", "${Reject}"),
                ordered("Input", "${MSGFORGE_SIT_IN}", "Reject", "ROUTER.REJECT"))).id();
        String uat = store.save(new Connections.Connection(null, "UAT", "fake", Map.of("host", "h"), List.of("${Input}"), Map.of("Input", "UAT.IN"))).id();

        // filled in per connection: its vars first, then the environment under the usual rule
        assertEquals("SIT.IN", store.resolve(sit, "${Input}"));
        assertEquals("UAT.IN", store.resolve(uat, "${Input}"));
        assertEquals("${MSGFORGE_SIT_IN}", store.expand(sit, "${Input}"), "expanding leaves the environment for later");
        assertEquals("X.ROUTER.REJECT", store.expand(sit, "X.${Reject}"));
        assertEquals("${Reject}", store.expand(uat, "${Reject}"), "a name the connection does not have stays as written");
        assertEquals("${Input}", store.expand("gone", "${Input}"));
        String refused = assertThrows(IllegalArgumentException.class, () -> store.resolve(uat, "${Reject}")).getMessage();
        assertTrue(refused.contains("Reject may not be used"), refused);

        // shown: a var written plainly is shown, one taken from a variable that is not listed is shown as that variable
        Connections.View view = store.get(sit);
        assertEquals(Map.of("Input", "${MSGFORGE_SIT_IN}", "Reject", "ROUTER.REJECT"), view.vars());
        assertEquals(Map.of("${Input}", "${MSGFORGE_SIT_IN}", "${Reject}", "ROUTER.REJECT"), view.queues());
        assertEquals(List.of("${Input}", "${Reject}"), view.destinations());
        assertEquals(Map.of("${Input}", "UAT.IN"), store.get(uat).queues());
        assertEquals(Map.of("${Input}", "SIT.IN", "${Reject}", "ROUTER.REJECT"), connections(file, "MSGFORGE_*_IN").get(sit).queues());

        // an edit from the console's form sends no vars: the stored ones stay. An empty map clears them.
        store.save(new Connections.Connection(sit, "SIT", "fake", Map.of("host", "h2"), List.of("${Input}")));
        assertEquals(List.of("Input", "Reject"), List.copyOf(store.get(sit).vars().keySet()), "kept, in their order");
        store.save(new Connections.Connection(uat, "UAT", "fake", Map.of("host", "h"), List.of("${Input}"), Map.of()));
        assertTrue(store.get(uat).vars().isEmpty());
        assertFalse(Files.readString(file).contains("\"vars\":{}"), "a connection without vars is written as before");

        // read back from the file, in order
        assertEquals(List.of("Input", "Reject"), List.copyOf(connections(file).get(sit).vars().keySet()));

        for (Map<String, String> bad : List.of(Map.of("a-b", "x"), Map.of("A", " "), Map.of("", "x"))) {
            assertThrows(IllegalArgumentException.class, () -> store.save(new Connections.Connection(null, "B", "fake", Map.of("host", "h"), List.of(), bad)), bad.toString());
        }
        // a hand-written file with a name that cannot be used: left out, the rest loads
        Files.writeString(dir.resolve("hand.json"), """
                {"connections":[{"id":"a","name":"A","type":"fake","settings":{"host":"h"},"destinations":["${In}"],"vars":{"In":"A.IN","bad name":"x","N":null}}]}""");
        assertEquals(Map.of("In", "A.IN"), connections(dir.resolve("hand.json")).get("a").vars());
    }

    @Test
    void settingsListedInShowAreFilledInForTheCardsAndSecretsNever(@TempDir Path dir) {
        Connections store = connections(dir.resolve("msgforge.json"), "MSGFORGE_*_HOST", "MSGFORGE_SIT_PASSWORD", "MSGFORGE_ENV");
        String sit = store.save(new Connections.Connection(null, "SIT", "fake",
                Map.of("host", "${MSGFORGE_SIT_HOST}", "user", "${MSGFORGE_SIT_USER}", "password", "${MSGFORGE_SIT_PASSWORD}"), List.of())).id();
        String uat = store.save(new Connections.Connection(null, "UAT", "fake", Map.of("host", "${MSGFORGE_UAT_HOST}", "user", "plain"), List.of())).id();
        Connections.View view = store.get(sit);
        assertEquals(Map.of("host", "mq-sit-1(1414)"), view.values(), "the user is not listed, the password is a secret whatever the list says");
        assertEquals("${MSGFORGE_SIT_HOST}", view.settings().get("host"), "settings stay as written, for the form");
        assertEquals("mq-sit-1(1414)", view.summary());
        assertEquals(Map.of("host", "mq-uat-1(1414)"), store.get(uat).values(), "* matches any characters; a plain value needs no filling in");
        assertFalse(store.list().toString().contains("dummy-pw") || store.list().toString().contains("tester"), store.list().toString());
        // a variable that some connection keeps a secret in is not shown in another's setting either
        store.save(new Connections.Connection(null, "Odd", "fake", Map.of("host", "h", "password", "${MSGFORGE_UAT_HOST}"), List.of()));
        assertTrue(store.get(uat).values().isEmpty());
        assertEquals("${MSGFORGE_UAT_HOST}", store.get(uat).summary());
    }

    @Test
    void anErrorNamesTheVariablesEvenThoseTheCardShows(@TempDir Path dir) {
        TransportType quoting = new TransportType() {
            public String id() { return "fake"; }
            public String label() { return "Fake"; }
            public List<Field> fields() { return FAKE.fields(); }
            public Sender open(Map<String, String> s) throws Exception {
                throw new java.io.IOException("no " + s.get("host") + " for " + s.get("user") + " with " + s.get("password"));
            }
        };
        Connections store = new Connections(new ConfigStore(dir.resolve("msgforge.json").toString(), new ObjectMapper()), List.of(quoting), new String[0],
                new String[] {"MSGFORGE_*_HOST", "MSGFORGE_SIT_PASSWORD"});
        store.env = ENV::get;
        String id = store.save(new Connections.Connection(null, "SIT", "fake",
                Map.of("host", "${MSGFORGE_SIT_HOST}", "user", "${MSGFORGE_SIT_USER}", "password", "${MSGFORGE_SIT_PASSWORD}"), List.of())).id();
        String error = store.check(id).error();
        // as in 0.3.0: an error never carries a value from the environment, even one the card shows
        assertEquals("no ${MSGFORGE_SIT_HOST} for ${MSGFORGE_SIT_USER} with ${MSGFORGE_SIT_PASSWORD}", error);
    }

    @Test
    void aRunFillsInTheVarsOfTheConnectionItUsesAndNamesTheQueueItHas(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("msgforge.json"), """
                {"connections":[{"id":"sit","name":"SIT","type":"dry-run","destinations":["${Input}"],"vars":{"Input":"SIT.IN"}},
                                {"id":"uat","name":"UAT","type":"dry-run","destinations":["${Input}"],"vars":{"Input":"UAT.IN"}}]}""");
        Connections store = connections(dir.resolve("msgforge.json"));
        Runs runs = new Runs(new Packs(new String[] {"packs"}), new TemplateEngine(), store);
        Runs.Request req = new Runs.Request(null, null, null, null, 3, 0, null, "demo", "order.json", null, null, "uat", "${Input}",
                new Runs.Target("uat", "${Input}"), 5, null, null, null, null, List.of(new Runs.Watch("uat", "${Input}", null)), null);
        Runs.View run = finished(runs, runs.start(req).id());
        assertEquals("PASS", run.verdict());
        assertEquals("UAT / UAT.IN", run.checks().get(0).expected());
        assertEquals("UAT.IN", run.targets().get(0).destination());
        assertEquals(3, run.watched().get(0).count());
    }

    @Test
    void aScenarioRunsOnAnotherEnvironmentWithItsOwnConnectionReplacedEverywhere(@TempDir Path dir) throws Exception {
        Runs.Target sit = new Runs.Target("sit", "${Input}"), other = new Runs.Target("audit", "AUDIT");
        Scenarios.Scenario s = new Scenarios.Scenario("s", "Check", List.of(new Runs.Part("p", "t", null, 1, Map.of(), new Runs.Target("sit", "${Out}"))),
                List.of(sit, other), "spread", 1, 0, Map.of(), null, null, null, new Faults.Spec(5, null, new Runs.Target("", "${Reject}")), null,
                List.of(new Runs.Watch("sit", "${Reject}", null), new Runs.Watch("audit", "AUDIT", null)));
        Scenarios.Scenario on = Scenarios.on(s, "uat");
        assertEquals(List.of(new Runs.Target("uat", "${Input}"), other), on.targets(), "another connection is another system, and stays");
        assertEquals(new Runs.Target("uat", "${Out}"), on.parts().get(0).expect());
        assertEquals(new Runs.Target("uat", "${Reject}"), on.invalid().expect(), "a blank connection is the scenario's own");
        assertEquals(List.of("uat", "audit"), on.watches().stream().map(Runs.Watch::connection).toList());
        assertEquals(5, on.invalid().percent());

        // by id, name or title, in any letter case; never guessed between two
        Files.writeString(dir.resolve("msgforge.json"), """
                {"connections":[{"id":"c1","name":"SIT","type":"dry-run"},{"id":"c2","name":"Twin","type":"dry-run"},{"id":"c3","name":"twin","type":"dry-run"}]}""");
        Connections store = connections(dir.resolve("msgforge.json"), "MSGFORGE_ENV");
        assertEquals("c1", store.idOf("c1"));
        assertEquals("c1", store.idOf("sit"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> store.idOf("twin")).getMessage().contains("c2, c3"));
        String none = assertThrows(IllegalArgumentException.class, () -> store.idOf("prod")).getMessage();
        assertTrue(none.contains("No environment prod") && none.contains("c1 (SIT)"), none);
    }

    @Test
    void aSavedMonitorMayWaitToBeStartedByHand(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("msgforge.json");
        Files.writeString(file, """
                {"connections":[{"id":"sit","name":"SIT","type":"dry-run","settings":{},"destinations":["${Reject}"],"vars":{"Reject":"SIT.REJECT"}},
                                {"id":"uat","name":"UAT","type":"dry-run","settings":{},"destinations":["${Reject}"],"vars":{"Reject":"UAT.REJECT"}}],
                 "monitors":[{"id":"m-sit","name":"Rejected","connection":"sit","destination":"${Reject}"},
                             {"id":"m-uat","name":"Rejected","connection":"uat","destination":"${Reject}","start":false}]}""");
        ConfigStore config = new ConfigStore(file.toString(), new ObjectMapper());
        DryRunType dry = new DryRunType();
        Connections store = new Connections(config, List.of(dry));
        Monitors monitors = new Monitors(store, config);
        try {
            assertEquals(List.of("starting", "stopped"), monitors.list().stream().map(Monitors.View::state).toList());
            monitors.startSaved();
            for (int i = 0; i < 100 && !monitors.list().get(0).state().equals("up"); i++) Thread.sleep(20);
            assertEquals(List.of("up", "stopped"), monitors.list().stream().map(Monitors.View::state).toList());
            Monitors.View sit = monitors.list().get(0);
            assertEquals("${Reject}", sit.destination());
            assertEquals("SIT.REJECT", sit.watch().destination(), "watched under the name the environment has");
            assertNull(monitors.list().get(1).error());

            dry.open(Map.of()).send("SIT.REJECT", "{}", Map.of());
            assertEquals(1, monitors.list().get(0).watch().count());

            // started by hand, it listens on its own environment's queue; saved again, it still waits next time
            assertEquals("up", monitors.restart("m-uat").state());
            dry.open(Map.of()).send("UAT.REJECT", "{}", Map.of());
            assertEquals(1, monitors.list().get(1).watch().count());
            monitors.save("m-uat");
            String saved = Files.readString(file);
            assertTrue(saved.replace(" ", "").contains("\"start\":false") && saved.indexOf("\"start\"") == saved.lastIndexOf("\"start\""), saved);
            assertEquals("stopped", new Monitors(store, config).list().get(1).state());

            // two monitors on one written name are two queues when the environments differ
            String why = assertThrows(IllegalArgumentException.class, () -> monitors.start(new Monitors.Request("sit", "${Reject}", null))).getMessage();
            assertTrue(why.contains("already"), why);
        } finally {
            monitors.stopAll();
        }
    }

    private static Map<String, String> ordered(String... namesAndValues) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) m.put(namesAndValues[i], namesAndValues[i + 1]);
        return m;
    }

    private static Runs.View finished(Runs runs, String id) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            Runs.View v = runs.list().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
            if (v.status() != Runs.Status.RUNNING && v.status() != Runs.Status.VERIFYING) return v;
            Thread.sleep(20);
        }
        throw new AssertionError("run did not finish");
    }
}
