package io.msgforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jms.core.JmsTemplate;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.databind.ObjectMapper;

class MsgforgeTest {

    private final TemplateEngine engine = new TemplateEngine();
    private final Packs packs = new Packs(new String[] {"packs"});

    /** A connection type with one required setting and one secret, standing in for a real broker. Each open asks for a sender. */
    private static TransportType fake(Supplier<TransportType.Sender> senders) {
        return new TransportType() {
            public String id() { return "fake"; }
            public String label() { return "Fake"; }
            public List<Field> fields() {
                return List.of(new Field("host", "Host", "", true, false), new Field("password", "Password", "", false, true));
            }
            public Sender open(Map<String, String> settings) { return senders.get(); }
        };
    }

    private static TransportType fake(TransportType.Sender sender) {
        return fake(() -> sender);
    }

    /** A sender that says when it was closed, and can be told what to do with each message. */
    private static final class Recording implements TransportType.Sender {
        final List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger closed = new AtomicInteger();
        Consumer<String> onSend = body -> { };

        public void send(String destination, String body, Map<String, String> headers) {
            onSend.accept(body);
            bodies.add(body);
        }

        public void close() {
            closed.incrementAndGet();
        }
    }

    @Test
    void bindingRepeatsAValueAndOverridesWin() {
        String out = engine.render("{{a=uuid}}|{{a}}|{{ccy}}|{{pick \"two words\"}}", Map.of("ccy", "{{pick GBP EUR}}"), Map.of("ccy", "JPY"));
        String[] parts = out.split("\\|");
        assertEquals(parts[0], parts[1]);
        assertEquals("JPY", parts[2]);
        assertEquals("two words", parts[3]);
        assertThrows(IllegalArgumentException.class, () -> engine.render("{{nope}}", Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.render("{{a}}", Map.of("a", "{{a}}"), Map.of()));
        // \{{ is a literal {{: text for another template language passes through
        assertEquals("Hello {{ user.name }} and {{uuid}}, C:\\dir", engine.render("Hello \\{{ user.name }} and \\{{uuid}}, C:\\dir", Map.of(), Map.of()));
    }

    @Test
    void aPlaceholderLeftOpenBeforeThousandsOfWordsIsPlainText() {
        String open = "<a>{{note " + "word ".repeat(100_000);   // overflowed the stack: one frame per argument
        assertEquals(open, engine.render(open, Map.of(), Map.of()));
        String choices = String.join(" ", java.util.stream.IntStream.range(0, 500).mapToObj(i -> "c" + i).toList());
        assertTrue(engine.render("{{pick " + choices + "}}", Map.of(), Map.of()).matches("c\\d+"), "500 arguments are still a placeholder");
    }

    @Test
    void badPlaceholderArgumentsAreExplainedAndSizesAreCapped() {
        Function<String, String> error = template -> assertThrows(IllegalArgumentException.class, () -> engine.render(template, Map.of(), Map.of())).getMessage();
        assertTrue(error.apply("{{now VV}}").startsWith("{{now}} cannot use the pattern VV"), error.apply("{{now VV}}"));
        assertTrue(error.apply("{{now b}}").startsWith("{{now}} cannot use the pattern b"), "a letter that is no pattern at all");
        assertEquals("{{digits}} gives at most 10000 characters", error.apply("{{digits 2000000000}}"));
        assertEquals("{{alnum}} gives at most 10000 characters", error.apply("{{alnum 10001}}"));
        assertEquals("{{decimal}} gives at most 18 decimal places", error.apply("{{decimal 1 2 999999999}}"));
        assertEquals("{{iban}} supports [DE, GB, NL]", error.apply("{{iban FR}}"));
        assertEquals("bad arguments for {{int}}", error.apply("{{int a b}}"));
        assertEquals("missing arguments for {{int}}", error.apply("{{int 1}}"));
        assertEquals(10_000, engine.render("{{digits 10000}}", Map.of(), Map.of()).length());
        assertEquals(20, engine.render("{{decimal 1 2 18}}", Map.of(), Map.of()).length());
        assertEquals(4, engine.render("{{now yyyy}}", Map.of(), Map.of()).length());
    }

    @Test
    void ibanCheckDigitsAreValid() {
        for (String country : List.of("GB", "DE", "NL")) {
            String iban = engine.render("{{iban " + country + "}}", Map.of(), Map.of());
            assertEquals(1, TemplateEngine.mod97(iban.substring(4) + iban.substring(0, 4)), iban);
        }
    }

    @Test
    void namesCannotEscapeThePackFolder() {
        assertThrows(IllegalArgumentException.class, () -> packs.read("..", "pack.yml"));
        assertThrows(IllegalArgumentException.class, () -> packs.write("demo", "../../evil.txt", "x"));
        assertThrows(IllegalArgumentException.class, () -> packs.write("demo", ".hidden", "x"));
        assertThrows(IllegalArgumentException.class, () -> packs.delete(".."));
        assertThrows(IllegalArgumentException.class, () -> packs.create("../up", "x", Map.of()));
    }

    @Test
    void everyBundledTemplateRenders() throws Exception {
        for (Packs.Pack pack : packs.list()) {
            assertFalse(pack.templates().isEmpty(), pack.id());
            for (String name : pack.templates()) {
                String out = engine.render(packs.read(pack.id(), name), pack.fields(), Map.of());
                assertFalse(out.contains("{{"), pack.id() + "/" + name);
                if (name.endsWith(".xml")) DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(out)));
            }
        }
    }

    @Test
    void aPackCanBeCreatedEditedAndDeleted(@TempDir Path dir) {
        Packs mine = new Packs(new String[] {dir.toString()});
        // a pack that is refused leaves nothing behind, so the same name works once the mistake is fixed
        assertThrows(IllegalArgumentException.class, () -> mine.create("orders", "Orders", Map.of("bad name", "1")));
        mine.create("orders", "Orders", Map.of("region", "EU"));
        assertEquals("EU", mine.get("orders").fields().get("region"));
        assertThrows(IllegalArgumentException.class, () -> mine.create("orders", "again", Map.of()));
        mine.update("orders", "Orders v2", Map.of("region", "{{pick \"North East\" EU}}"));
        mine.write("orders", "new.json", "{\"r\":\"{{region}}\"}");
        Packs.Pack pack = mine.get("orders");
        assertEquals("Orders v2", pack.name());
        assertEquals("{{pick \"North East\" EU}}", pack.fields().get("region"));
        assertEquals(List.of("new.json"), pack.templates());
        assertThrows(IllegalArgumentException.class, () -> mine.update("orders", "x", Map.of("bad name", "1")));
        // Order.json is order.json where the file system ignores case, which is what "exists" has to mean before a create
        assertTrue(mine.exists("orders", "new.json"));
        assertEquals(Files.exists(dir.resolve("orders/templates/NEW.JSON")), mine.exists("orders", "NEW.JSON"));
        assertFalse(mine.exists("orders", "other.json"));

        mine.deleteTemplate("orders", "new.json");
        mine.delete("orders");
        assertTrue(mine.list().isEmpty());
        assertTrue(Files.isDirectory(dir), "only the pack folder goes, never its parent");
    }

    @Test
    void aPackThatMayNotBeWrittenToSaysSo(@TempDir Path dir) {
        Packs mine = new Packs(new String[] {dir.toString()});
        mine.create("orders", "Orders", Map.of());
        mine.write("orders", "new.json", "{\"r\":\"{{region}}\"}");
        // a pack msgforge may not write to (the bundled ones in a container, a read-only mount) says so, for every kind of change
        java.io.File templates = dir.resolve("orders/templates").toFile(), existing = dir.resolve("orders/templates/new.json").toFile();
        java.io.File yml = dir.resolve("orders/pack.yml").toFile(), folder = dir.resolve("orders").toFile();
        for (java.io.File f : List.of(existing, yml, templates, folder)) assertTrue(f.setWritable(false));
        // root may write whatever the permissions say (a build inside a container runs as root), so there is nothing to see
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(templates.toPath()), "this user is not held back by file permissions");
        try {
            for (org.junit.jupiter.api.function.Executable change : List.<org.junit.jupiter.api.function.Executable>of(
                    () -> mine.write("orders", "new.json", "changed"), () -> mine.write("orders", "another.json", "x"),
                    () -> mine.update("orders", "Renamed", Map.of()), () -> mine.deleteTemplate("orders", "new.json"), () -> mine.delete("orders"))) {
                assertTrue(assertThrows(IllegalArgumentException.class, change).getMessage().startsWith("This pack is read-only"));
            }
        } finally {
            for (java.io.File f : List.of(folder, templates, yml, existing)) f.setWritable(true);
        }
        assertEquals("{\"r\":\"{{region}}\"}", mine.read("orders", "new.json"));
    }

    @Test
    void connectionsPersistAndNeverReturnSecrets(@TempDir Path dir) throws Exception {
        String file = dir.resolve("msgforge.json").toString();
        TransportType type = fake((destination, body, headers) -> { });
        Connections store = new Connections(new ConfigStore(file, new ObjectMapper()), List.of(type));
        store.list().forEach(c -> store.delete(c.id()));

        Connections.View saved = store.save(new Connections.Connection(null, " Broker ", "fake",
                Map.of("host", "h1", "password", "s3cret", "unknown", "dropped"), List.of("Q.1", " Q.1 ", "", "Q.2")));
        assertEquals("Broker", saved.name());
        assertEquals(Map.of("host", "h1"), saved.settings());
        assertEquals(List.of("password"), saved.secretsSet());
        assertEquals(List.of("Q.1", "Q.2"), saved.destinations());

        // a secret left out of an update keeps the stored one, as long as the update does not change where the connection points
        assertEquals(List.of("password"), store.save(new Connections.Connection(saved.id(), "Broker", "fake", Map.of("host", "h1"), List.of("Q.1"))).secretsSet());
        assertThrows(IllegalArgumentException.class, () -> store.save(new Connections.Connection(saved.id(), "Broker", "fake", Map.of("host", "h2"), List.of("Q.1"))));
        assertEquals("h1", store.get(saved.id()).settings().get("host"));
        // a reloaded store still has the secret on disk
        store.save(new Connections.Connection(saved.id(), "Broker", "fake", Map.of("host", "h2", "password", "s3cret"), List.of("Q.1")));
        Connections reloaded = new Connections(new ConfigStore(file, new ObjectMapper()), List.of(type));
        assertEquals("h2", reloaded.get(saved.id()).settings().get("host"));
        assertEquals(List.of("password"), reloaded.get(saved.id()).secretsSet());
        assertTrue(Files.readString(Path.of(file)).contains("s3cret"));
        // sent blank, it is cleared
        store.save(new Connections.Connection(saved.id(), "Broker", "fake", Map.of("host", "h2", "password", ""), List.of("Q.1")));
        assertTrue(store.get(saved.id()).secretsSet().isEmpty());
        assertFalse(Files.readString(Path.of(file)).contains("s3cret"));

        assertThrows(IllegalArgumentException.class, () -> store.save(new Connections.Connection(null, "x", "fake", Map.of(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> store.save(new Connections.Connection(null, "x", "nope", Map.of(), List.of())));
        // ${NAME} is read from the environment only for names that are allowed: MSGFORGE_* and what the allow-list adds
        Connections strict = new Connections(new ConfigStore(file, new ObjectMapper()), List.of(type));
        Connections allowing = new Connections(new ConfigStore(file, new ObjectMapper()), List.of(type), new String[] {" PA*H ", "HO*"});
        assertEquals("a " + System.getenv("PATH") + " b", allowing.resolve(Map.of("k", "a ${PATH} b")).get("k"));
        assertEquals(System.getenv("HOME"), allowing.resolve(Map.of("k", "${HOME}")).get("k"));
        for (String refused : List.of("${PATH}", "${HOME}", "${MSGFORGE}", "${XMSGFORGE_X}")) {
            String why = assertThrows(IllegalArgumentException.class, () -> strict.resolve(Map.of("k", refused))).getMessage();
            assertTrue(why.contains(refused.substring(2, refused.length() - 1) + " may not be used") && why.contains("MSGFORGE_ENV_ALLOW"), why);
            assertFalse(why.contains(System.getenv("PATH")) || why.contains(System.getenv("HOME")), "refused before the value is read");
        }
        assertThrows(IllegalArgumentException.class, () -> allowing.resolve(Map.of("k", "${PATHS}")), "* does not reach past the name it was given for");
        assertEquals("Environment variable MSGFORGE_NOT_SET_ANYWHERE is not set",
                assertThrows(IllegalArgumentException.class, () -> strict.resolve(Map.of("k", "${MSGFORGE_NOT_SET_ANYWHERE}"))).getMessage());

        // What a view shows of a variable. By default the environment's name and nothing else: a connection or a queue
        // named after the variable that holds a password comes back as written.
        Map<String, String> environment = Map.of("MSGFORGE_ENV", "SIT", "MSGFORGE_MQ_PASSWORD", "dummy-mq-pw", "MSGFORGE_REGION", "north", "MSGFORGE_LOGIN", "dummy-login");
        Connections kit = new Connections(new ConfigStore(dir.resolve("kit.json").toString(), new ObjectMapper()), List.of(type));
        kit.env = environment::get;
        Connections.View sit = kit.save(new Connections.Connection("mq", "${MSGFORGE_ENV}", "fake", Map.of("host", "h", "password", "${MSGFORGE_MQ_PASSWORD}"),
                List.of("ROUTER.${MSGFORGE_ENV}.IN", "${MSGFORGE_MQ_PASSWORD}", "Q.${MSGFORGE_REGION}", "${MSGFORGE_ENV}.${MSGFORGE_MQ_PASSWORD}")));
        assertEquals("SIT", sit.title());
        assertEquals(Map.of("ROUTER.${MSGFORGE_ENV}.IN", "ROUTER.SIT.IN"), sit.queues());
        Connections.View leak = kit.save(new Connections.Connection(null, "x ${MSGFORGE_MQ_PASSWORD}", "fake", Map.of("host", "${MSGFORGE_MQ_PASSWORD}"), List.of("${MSGFORGE_MQ_PASSWORD}")));
        assertEquals("x ${MSGFORGE_MQ_PASSWORD}", leak.title());
        assertTrue(leak.queues().isEmpty());
        assertFalse(kit.list().toString().contains("dummy-mq-pw") || kit.list().toString().contains("north"), kit.list().toString());
        // Listed in MSGFORGE_SHOW, a variable is shown, unless some connection keeps a secret in it: a slip in that list
        // does not hand out a password. A setting its type does not declare counts as a secret. A name that says
        // it is a password is never shown, listed or not.
        Connections wide = new Connections(new ConfigStore(dir.resolve("wide.json").toString(), new ObjectMapper()), List.of(type), new String[0],
                new String[] {" MSGFORGE_REGION ", "MSGFORGE_LOGIN", "MSGFORGE_MQ_PASSWORD", "MSGFORGE_ENV"});
        wide.env = environment::get;
        String a = wide.save(new Connections.Connection(null, "${MSGFORGE_REGION} ${MSGFORGE_LOGIN}", "fake", Map.of("host", "${MSGFORGE_REGION}"), List.of("Q.${MSGFORGE_REGION}"))).id();
        assertEquals("north dummy-login", wide.get(a).title(), "nobody uses it as a secret, and whoever started msgforge listed it");
        assertEquals("pw ${MSGFORGE_MQ_PASSWORD}", wide.get(wide.save(new Connections.Connection(null, "pw ${MSGFORGE_MQ_PASSWORD}", "fake", Map.of("host", "h"), List.of())).id()).title());
        wide.save(new Connections.Connection(null, "B", "fake", Map.of("host", "h", "password", "p-${MSGFORGE_LOGIN}"), List.of()));
        assertEquals("${MSGFORGE_REGION} ${MSGFORGE_LOGIN}", wide.get(a).title());
        assertEquals(Map.of("Q.${MSGFORGE_REGION}", "Q.north"), wide.get(a).queues());
        // in a file written by hand: a key the type does not know, and a type that is not installed
        Files.writeString(dir.resolve("hand.json"), """
                {"connections":[{"id":"a","name":"${MSGFORGE_ENV} ${MSGFORGE_REGION}","type":"fake","settings":{"host":"h","tokne":"${MSGFORGE_ENV}"}},
                                {"id":"b","name":"${MSGFORGE_REGION}","type":"gone","settings":{"host":"${MSGFORGE_REGION}"},"destinations":["${MSGFORGE_REGION}"]}]}""");
        Connections hand = new Connections(new ConfigStore(dir.resolve("hand.json").toString(), new ObjectMapper()), List.of(type), new String[0],
                new String[] {"MSGFORGE_REGION", "MSGFORGE_ENV"});
        hand.env = environment::get;
        assertEquals("${MSGFORGE_ENV} ${MSGFORGE_REGION}", hand.get("a").title());
        assertEquals("${MSGFORGE_REGION}", hand.get("b").title());
        assertTrue(hand.get("b").queues().isEmpty());

        // A type that quotes its settings in an error, the ways the real ones did: a piece of a comma-separated value,
        // with the spaces taken out, in upper case, and in the cause. Neither the environment value nor the password gets out.
        String path = System.getenv("PATH"), firstDir = path.split(":")[0];
        TransportType quoting = new TransportType() {
            public String id() { return "fake"; }
            public String label() { return "Fake"; }
            public List<Field> fields() { return type.fields(); }
            public Sender open(Map<String, String> settings) throws Exception {
                String[] hosts = settings.get("host").split(",");
                throw new java.io.IOException("no route to " + hosts[1].replace(" ", "").toUpperCase() + " or " + hosts[1].trim().substring(3),
                        new IllegalStateException("password was " + settings.get("password").split(" ")[1].toUpperCase()));
            }
        };
        Exception refused = assertThrows(IllegalStateException.class, () -> new Connections(new ConfigStore(file, new ObjectMapper()), List.of(quoting), new String[] {"PATH"})
                .test(new Connections.Connection(null, "x", "fake", Map.of("host", "first one, to:${PATH} ,third", "password", "pw-123 Horse"), List.of())));
        String text = refused.getMessage();
        assertFalse(text.toLowerCase().contains(firstDir.toLowerCase()) || text.toLowerCase().contains("horse"), text);
        assertTrue(text.contains("${PATH}") && text.contains("password was ***"), text);
        assertTrue(refused.getCause() == null, "the original exception, with the values in it, is not passed along");
        // a variable that is not allowed is never opened with at all
        assertThrows(IllegalArgumentException.class, () -> new Connections(new ConfigStore(file, new ObjectMapper()), List.of(quoting))
                .test(new Connections.Connection(null, "x", "fake", Map.of("host", "${PATH}"), List.of())));
    }

    @Test
    void aSavedFileThatCannotBeReadIsReportedAndLeftAlone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("msgforge.json");
        for (String broken : List.of("{\"connections\": [\n  {\"password\": \"s3cret\" oops", "[1, 2]")) {
            Files.writeString(file, broken);
            ConfigStore.Invalid e = assertThrows(ConfigStore.Invalid.class, () -> new ConfigStore(file.toString(), new ObjectMapper()));
            assertTrue(e.getMessage().startsWith(file.toString()), e.getMessage());
            assertFalse(e.getMessage().contains("s3cret"), "the parser's quote of the file is not passed on");
            assertEquals(broken, Files.readString(file));
            String shown = new ConfigStore.InvalidAnalyzer().analyze(e).getAction();
            assertTrue(shown.contains("Fix the file or remove it"), shown);
        }
        Files.writeString(file, "");
        ConfigStore empty = new ConfigStore(file.toString(), new ObjectMapper());
        empty.write("connections", List.of());
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void ibmMqHostListsTlsStoresAndOldSettings(@TempDir Path dir) throws Exception {
        assertEquals("mq1(1414),mq2(1415)", IbmMqType.hosts(" mq1 , mq2(1415) "));
        assertThrows(IllegalArgumentException.class, () -> IbmMqType.hosts("mq1:1414"));
        assertThrows(IllegalArgumentException.class, () -> IbmMqType.hosts(" , "));

        IbmMqType type = new IbmMqType();
        assertEquals(Map.of("hosts", "h(1415)", "channel", "C"), type.migrate(Map.of("host", "h", "port", "1415", "channel", "C")));
        assertEquals("QM1 @ a(1414),b(1414) · TLS", type.summary(Map.of("queueManager", "QM1", "hosts", "a(1414),b(1414)", "cipherSuite", "X")));

        Path store = dir.resolve("trust.p12");
        java.security.KeyStore empty = java.security.KeyStore.getInstance("PKCS12");
        empty.load(null, null);
        try (var out = Files.newOutputStream(store)) {
            empty.store(out, "changeit".toCharArray());
        }
        Map<String, String> tls = Map.of("trustStore", store.toString(), "trustStorePassword", "changeit",
                "keyStore", store.toString(), "keyStorePassword", "changeit");
        assertTrue(IbmMqType.sslSocketFactory(tls) != null);
        assertThrows(IllegalArgumentException.class, () -> IbmMqType.sslSocketFactory(Map.of("trustStore", store.toString(), "trustStorePassword", "wrong")));
        assertThrows(IllegalArgumentException.class, () -> IbmMqType.sslSocketFactory(Map.of("trustStore", dir.resolve("missing.p12").toString())));
        // TLS refusals come in Java's and MQ's own words; each gets the setting to change added. Other errors pass through untouched.
        Map<String, String> noTls = Map.of("hosts", "h", "channel", "C"), withSuite = Map.of("hosts", "h", "channel", "C", "cipherSuite", "TLS_AES_256_GCM_SHA384");
        Exception path = new jakarta.jms.JMSException("JMSWMQ0018: Failed to connect to queue manager 'QM1'");
        path.initCause(new javax.net.ssl.SSLHandshakeException("unable to find valid certification path to requested target"));
        assertTrue(IbmMqType.advised(path, withSuite).getMessage().endsWith("set Trust store file to a store that holds it or its issuer."));
        assertTrue(IbmMqType.advised(path, withSuite).getMessage().startsWith("JMSWMQ0018: Failed to connect to queue manager 'QM1' (unable to find"), "the original text stays");
        assertTrue(IbmMqType.advised(new IllegalStateException("reason '2400' ('MQRC_UNSUPPORTED_CIPHER_SUITE')."), withSuite).getMessage().contains("Java name of a suite"));
        Exception cipherSpec = new IllegalStateException("CC=2;RC=2397;AMQ9641: Remote CipherSpec error for channel 'C'");
        assertTrue(IbmMqType.advised(cipherSpec, noTls).getMessage().endsWith("The channel requires TLS: set TLS cipher suite."));
        assertTrue(IbmMqType.advised(cipherSpec, withSuite).getMessage().endsWith("use one its SSLCIPH setting allows or, if the channel does not use TLS, clear TLS cipher suite."));
        // seen against a real queue manager whose channel has no SSLCIPH: no MQ reason at all, only Java's end of the handshake
        Exception hungUp = new jakarta.jms.JMSException("JMSWMQ0018: Failed to connect to queue manager 'QM1'");
        hungUp.initCause(new javax.net.ssl.SSLHandshakeException("SSL peer shut down incorrectly"));
        assertTrue(IbmMqType.advised(hungUp, withSuite).getMessage().endsWith("If the channel does not use TLS, clear TLS cipher suite; otherwise use a suite its SSLCIPH setting allows."));
        Exception negotiation = new IllegalStateException("CC=2;RC=2059;AMQ9503: Channel negotiation failed.");
        assertTrue(IbmMqType.advised(negotiation, withSuite).getMessage().endsWith("set Key store file."));
        assertTrue(IbmMqType.advised(negotiation, noTls) == negotiation, "without TLS a failed negotiation has other causes");
        Exception other = new IllegalStateException("reason '2035' ('MQRC_NOT_AUTHORIZED').");
        assertTrue(IbmMqType.advised(other, withSuite) == other);

        // stores without a cipher suite would silently connect in plain text, so that is refused
        assertThrows(IllegalArgumentException.class, () -> type.open(Map.of("hosts", "h", "channel", "C", "trustStore", store.toString())));
    }

    @Test
    void aRunDeliversRenderedMessagesOverJms(@TempDir Path dir) throws Exception {
        EmbeddedActiveMQ broker = new EmbeddedActiveMQ().setConfiguration(new ConfigurationImpl()
                .setPersistenceEnabled(false).setSecurityEnabled(false).addAcceptorConfiguration("vm", "vm://0"));
        broker.start();
        try (ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("vm://0")) {
            // a new sender per open, as a real type gives: test() closes the one it opened, and each run closes its own
            List<JmsSender> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
            ConfigStore config = new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper());
            Connections store = new Connections(config, List.of(fake(() -> {
                JmsSender sender = new JmsSender(cf);
                opened.add(sender);
                return sender;
            })));
            String id = store.save(new Connections.Connection(null, "Artemis", "fake", Map.of("host", "vm"), List.of("TEST.QUEUE"))).id();
            store.test(new Connections.Connection(id, "Artemis", "fake", Map.of("host", "vm"), List.of()));
            Runs runs = new Runs(packs, engine, store);
            JmsTemplate jms = new JmsTemplate(cf);
            jms.setReceiveTimeout(5000);

            Runs.View run = runs.start(Runs.Request.single("demo", "shipment.xml", null, id, "TEST.QUEUE", 3, 0, Map.of("carrier", "Polar Line")));
            for (int i = 0; i < 3; i++) {
                String body = (String) jms.receiveAndConvert("TEST.QUEUE");
                assertTrue(body != null && body.contains("<Carrier>Polar Line</Carrier>"), "message " + i);
            }
            for (int i = 0; i < 100 && runs.list().get(0).status() == Runs.Status.RUNNING; i++) Thread.sleep(20);
            assertEquals(Runs.Status.DONE, runs.list().get(0).status());
            assertEquals(3, runs.list().get(0).sent());
            assertEquals("Artemis", run.targets().get(0).connection());
            assertEquals(run.id(), runs.messages().get(0).runId());
            assertEquals(2, opened.size(), "one sender for the test, another for the run");
            assertThrows(IllegalArgumentException.class, () -> runs.start(Runs.Request.single("demo", "order.json", "{{nope}}", id, "Q", 1, 0, null)));
            assertThrows(IllegalArgumentException.class, () -> runs.start(Runs.Request.single("demo", "order.json", null, id, " ", 1, 0, null)));

            // headers: rendered per message, sharing the body's bindings; JMSCorrelationID sets the real JMS header
            runs.start(new Runs.Request(null, null, List.of(new Runs.Target(id, "Q.H")), null, 1, 0,
                    Map.of("JMSCorrelationID", "{{ref}}", "region", "EU"), "demo", "order.json", "{{ref=id}}", null, null, null, null, null, null));
            jakarta.jms.Message withHeaders = jms.receive("Q.H");
            assertEquals(((jakarta.jms.TextMessage) withHeaders).getText(), withHeaders.getJMSCorrelationID());
            assertEquals("EU", withHeaders.getStringProperty("region"));

            // two targets: spread alternates between them, all copies each message to both
            List<Runs.Target> two = List.of(new Runs.Target(id, "Q.A"), new Runs.Target(id, "Q.B"));
            runs.start(new Runs.Request(null, null, two, "spread", 4, 0, null, "demo", "order.json", "m{{seq}}", null, null, null, null, null, null));
            assertTrue(jms.receiveAndConvert("Q.A") != null && jms.receiveAndConvert("Q.A") != null);
            assertTrue(jms.receiveAndConvert("Q.B") != null && jms.receiveAndConvert("Q.B") != null);
            runs.start(new Runs.Request(null, null, two, "all", 1, 0, null, "demo", "order.json", "same-{{uuid}}", null, null, null, null, null, null));
            Object a = jms.receiveAndConvert("Q.A"), b = jms.receiveAndConvert("Q.B");
            assertTrue(a != null && a.equals(b), "both targets get the same message");
            for (int i = 0; i < 100 && runs.list().get(0).status() == Runs.Status.RUNNING; i++) Thread.sleep(20);
            assertEquals(1, runs.list().get(0).sent());
            assertEquals(2, runs.list().get(0).targets().size());
            jms.setReceiveTimeout(200);
            assertTrue(jms.receiveAndConvert("Q.A") == null && jms.receiveAndConvert("Q.B") == null, "nothing extra was sent");
            assertThrows(IllegalArgumentException.class, () -> runs.start(new Runs.Request(null, null, two, "nope", 1, 0, null, "demo", "order.json", null, null, null, null, null, null, null)));
        } finally {
            broker.stop();
        }
    }

    public static class ShoutGenerator implements Generator {
        public String name() { return "shout"; }
        public String generate(List<String> args) { return String.join(" ", args).toUpperCase(); }
    }

    @Test
    void generatorsComeFromCodeOrFromAPluginJar(@TempDir Path dir) throws Exception {
        TemplateEngine withBean = new TemplateEngine(List.of(new ShoutGenerator()));
        assertEquals("HI THERE", withBean.render("{{shout hi there}}", Map.of(), Map.of()));
        assertEquals(List.of("shout"), withBean.customGenerators());

        // a jar that only declares the service: discovery is what is under test, the class itself is already on the classpath
        assertTrue(Plugins.load(dir).isEmpty());
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(dir.resolve("shout.jar")))) {
            jar.putNextEntry(new java.util.jar.JarEntry("META-INF/services/io.msgforge.Generator"));
            jar.write(ShoutGenerator.class.getName().getBytes());
            jar.closeEntry();
        }
        List<Object> found = Plugins.load(dir);
        assertEquals(1, found.size());
        assertEquals("shout", ((Generator) found.get(0)).name());

        // A jar that names a class it does not have is skipped, and the jars around it still load.
        // It sorts before shout.jar, so it is met first; a second good jar after it shows loading goes on past it.
        for (String name : List.of("broken.jar", "zz-also-good.jar")) {
            try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(dir.resolve(name)))) {
                jar.putNextEntry(new java.util.jar.JarEntry("META-INF/services/io.msgforge." + (name.startsWith("broken") ? "TransportType" : "Generator")));
                jar.write((name.startsWith("broken") ? "com.example.Missing" : ShoutGenerator.class.getName()).getBytes());
                jar.closeEntry();
            }
        }
        List<Object> withBroken = Plugins.load(dir);
        assertEquals(2, withBroken.size(), "shout.jar and zz-also-good.jar; broken.jar is left out");
        assertTrue(withBroken.stream().allMatch(ShoutGenerator.class::isInstance));
        assertTrue(Plugins.load(dir.resolve("missing")).isEmpty());
    }

    @Test
    void theKafkaPluginLoadsFromItsJarIntoItsOwnClassLoader(@TempDir Path dir) throws Exception {
        Files.copy(Path.of(System.getProperty("msgforge.test.kafkaPlugin")), dir.resolve("kafka.jar"));
        TransportType kafka = Plugins.load(dir).stream().filter(TransportType.class::isInstance).map(TransportType.class::cast)
                .filter(t -> t.id().equals("kafka")).findFirst().orElseThrow();
        assertTrue(kafka.getClass().getClassLoader() != Connections.class.getClassLoader(), "the plugin is not on the application's class path");
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.apache.kafka.clients.producer.KafkaProducer"), "and neither is the Kafka client it brings");

        // Opening builds a real producer, here one that logs in. The login module is named in the configuration and
        // looked up through the thread's context class loader, so this fails unless Connections has put the plugin's loader there.
        Connections store = new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(kafka));
        String id = store.save(new Connections.Connection(null, "Kafka", "kafka",
                Map.of("bootstrap", "127.0.0.1:1", "securityProtocol", "SASL_PLAINTEXT", "user", "u", "password", "p"), List.of("topic"))).id();
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        store.open(id).close();
        assertTrue(Thread.currentThread().getContextClassLoader() == before, "and the thread gets its own loader back");
    }

    @Test
    void httpConnectionPostsToThePathAndReportsFailures() throws Exception {
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + body + " "
                    + exchange.getRequestHeaders().getFirst("Content-Type") + " " + exchange.getRequestHeaders().getFirst("X-Trace")
                    + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] reply = "nope".getBytes();
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().endsWith("/fail") ? 500 : 200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            HttpType type = new HttpType();
            TransportType.Sender sender = type.open(Map.of("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/base/", "authorization", "Bearer t"));
            sender.check();
            sender.send("orders", "{\"a\":1}", Map.of("X-Trace", "7"));
            assertEquals(List.of("POST /base/orders {\"a\":1} application/json 7 Bearer t"), seen);
            Exception failure = assertThrows(java.io.IOException.class, () -> sender.send("/fail", "x", Map.of()));
            assertEquals("HTTP 500: nope", failure.getMessage());
            assertThrows(IllegalArgumentException.class, () -> type.open(Map.of("url", "ftp://x")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aScenarioMixesTemplatesInExactSharesAndIsSaved(@TempDir Path dir) throws Exception {
        ConfigStore config = new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper());
        Connections store = new Connections(config, List.of(fake((destination, body, headers) -> { })));
        String id = store.save(new Connections.Connection(null, "Sink", "fake", Map.of("host", "x"), List.of("Q"))).id();
        Runs runs = new Runs(packs, engine, store);
        Scenarios scenarios = new Scenarios(config, runs);

        Scenarios.Scenario saved = scenarios.save(new Scenarios.Scenario(null, " Mostly orders ", List.of(
                new Runs.Part("demo", "order.json", null, 7, null, null),
                new Runs.Part("demo", "shipment.xml", null, 3, Map.of("carrier", "Polar Line"), null)),
                List.of(new Runs.Target(id, "Q")), null, 10, 0, null, null, null));
        assertEquals("Mostly orders", saved.name());
        assertEquals("spread", saved.mode());
        assertEquals(1, new Scenarios(config, runs).list().size(), "scenarios survive a restart");
        assertTrue(new Connections(config, List.of(fake((destination, body, headers) -> { }))).list().size() >= 1, "and share the file with connections");

        Runs.View run = scenarios.run(saved.id());
        assertEquals("Mostly orders", run.label());
        for (int i = 0; i < 100 && runs.list().get(0).status() == Runs.Status.RUNNING; i++) Thread.sleep(20);
        assertEquals(Runs.Status.DONE, runs.list().get(0).status());
        List<Runs.Sent> sent = runs.messages();
        assertEquals(7, sent.stream().filter(m -> m.template().equals("order.json")).count());
        assertEquals(3, sent.stream().filter(m -> m.template().equals("shipment.xml")).count());
        assertTrue(sent.stream().filter(m -> m.template().equals("shipment.xml")).allMatch(m -> m.body().contains("<Carrier>Polar Line</Carrier>")));

        assertThrows(IllegalArgumentException.class, () -> scenarios.save(new Scenarios.Scenario(null, "x", List.of(), List.of(new Runs.Target(id, "Q")), null, 1, 0, null, null, null)));
        scenarios.delete(saved.id());
        assertTrue(scenarios.list().isEmpty());
    }

    // ---- verifying what arrives. The dry-run loopback stands in for the broker, and a subscriber for the system under test.

    private static final Runs.Target IN = new Runs.Target("dry-run", "IN"), ORDERS = new Runs.Target("dry-run", "OUT.ORDERS"),
            SHIPMENTS = new Runs.Target("dry-run", "OUT.SHIPMENTS");
    private static final List<Runs.Part> SEVEN_ORDERS_THREE_SHIPMENTS = List.of(
            new Runs.Part("demo", "order.json", null, 7, null, ORDERS),
            new Runs.Part("demo", "shipment.xml", null, 3, null, SHIPMENTS));

    /** The system under test: takes each message off IN and forwards it to the queue route picks, or drops it on null. */
    private static TransportType.Subscription router(DryRunType dry, Function<String, String> route) {
        TransportType.Sender out = dry.open(Map.of());
        return dry.subscribe(Map.of(), "IN", body -> {
            try {
                String to = route.apply(body);
                if (to != null) out.send(to, body, Map.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, e -> { });
    }

    private static Runs.View finished(Runs runs, String id) throws InterruptedException {
        for (int i = 0; i < 500; i++) {
            Runs.View run = runs.list().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
            if (run.status() != Runs.Status.RUNNING && run.status() != Runs.Status.VERIFYING) return run;
            Thread.sleep(20);
        }
        throw new AssertionError("run " + id + " did not finish");
    }

    private static Runs.Request mix(int timeoutSeconds) {
        return new Runs.Request("mix", SEVEN_ORDERS_THREE_SHIPMENTS, List.of(IN), null, 10, 0, null, null, null, null, null, null, null, null, timeoutSeconds, null);
    }

    @Test
    void aCorrectRouterPasses(@TempDir Path dir) throws Exception {
        DryRunType dry = new DryRunType();
        ConfigStore config = new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper());
        Runs runs = new Runs(packs, engine, new Connections(config, List.of(dry)));
        Scenarios scenarios = new Scenarios(config, runs);
        Scenarios.Scenario saved = scenarios.save(new Scenarios.Scenario(null, "Routing", SEVEN_ORDERS_THREE_SHIPMENTS, List.of(IN), null, 10, 0, null, 5, null));
        assertEquals(ORDERS, new Scenarios(config, runs).list().get(0).parts().get(0).expect(), "expectations are saved with the scenario");

        try (TransportType.Subscription sut = router(dry, body -> body.contains("orderId") ? "OUT.ORDERS" : "OUT.SHIPMENTS")) {
            Runs.View run = finished(runs, scenarios.run(saved.id()).id());
            assertEquals(Runs.Status.DONE, run.status());
            assertEquals("PASS", run.verdict());
            Runs.Check orders = run.checks().get(0), shipments = run.checks().get(1);
            assertEquals(new Runs.Check("order.json", "Dry run / OUT.ORDERS", 7, 7, 0, 0, orders.medianMs(), orders.maxMs()), orders);
            assertEquals(new Runs.Check("shipment.xml", "Dry run / OUT.SHIPMENTS", 3, 3, 0, 0, shipments.medianMs(), shipments.maxMs()), shipments);
            assertTrue(orders.medianMs() != null && orders.medianMs() <= orders.maxMs());
        }
    }

    @Test
    void aRouterThatMisroutesOrDropsFails(@TempDir Path dir) throws Exception {
        DryRunType dry = new DryRunType();
        Runs runs = new Runs(packs, engine, new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(dry)));

        try (TransportType.Subscription sut = router(dry, body -> "OUT.ORDERS")) {
            Runs.View run = finished(runs, runs.start(mix(60)).id());   // everything arrived somewhere, so it does not wait out the minute
            assertEquals(Runs.Status.DONE, run.status());
            assertEquals("FAIL", run.verdict());
            assertEquals(7, run.checks().get(0).arrived());
            assertEquals(new Runs.Check("shipment.xml", "Dry run / OUT.SHIPMENTS", 3, 0, 3, 0, null, null), run.checks().get(1));
        }

        try (TransportType.Subscription sut = router(dry, body -> body.contains("orderId") ? "OUT.ORDERS" : null)) {
            long started = System.nanoTime();
            Runs.View waiting = runs.start(mix(1));
            assertNull(waiting.verdict(), "no verdict before the run ends");
            Runs.View run = finished(runs, waiting.id());
            assertTrue(System.nanoTime() - started >= 1_000_000_000L, "waited for the timeout");
            assertEquals(Runs.Status.DONE, run.status(), "a failed verdict is a result, not a failed run");
            assertEquals("FAIL", run.verdict());
            assertEquals(7, run.checks().get(0).arrived());
            assertEquals(new Runs.Check("shipment.xml", "Dry run / OUT.SHIPMENTS", 3, 0, 0, 3, null, null), run.checks().get(1));

            // cancelling ends the wait
            String id = runs.start(mix(600)).id();
            for (int i = 0; i < 250 && runs.list().get(0).status() != Runs.Status.VERIFYING; i++) Thread.sleep(20);
            assertEquals(Runs.Status.VERIFYING, runs.list().get(0).status());
            runs.cancel(id);
            Runs.View cancelled = finished(runs, id);
            assertEquals(Runs.Status.CANCELLED, cancelled.status());
            assertNull(cancelled.verdict(), "a run that was stopped has a status, not a verdict");
            assertTrue(cancelled.checks().isEmpty());
        }
    }

    @Test
    void expectationsAreCheckedBeforeARunStarts(@TempDir Path dir) throws Exception {
        ConfigStore config = new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper());
        Connections store = new Connections(config, List.of(new DryRunType(), fake((destination, body, headers) -> { })));
        String sendOnly = store.save(new Connections.Connection(null, "Sink", "fake", Map.of("host", "x"), List.of("Q"))).id();
        Runs runs = new Runs(packs, engine, store);
        Function<String, Runs.Request> loopback = body -> new Runs.Request(null, null, null, null, 5, 0, null,
                "demo", "order.json", body, null, "dry-run", "Q", new Runs.Target("dry-run", "Q"), null, null);

        // nothing identifies the message, so an arrival could not be told from any other
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> runs.start(loopback.apply("plain {{seq}}")));
        assertTrue(e.getMessage().contains("{{uuid}}") && e.getMessage().contains("match"), e.getMessage());
        // an id generated by a header is not in the body, so it does not count either
        assertThrows(IllegalArgumentException.class, () -> runs.start(new Runs.Request(null, null, null, null, 1, 0, Map.of("trace", "{{uuid}}"),
                "demo", "order.json", "plain", null, "dry-run", "Q", new Runs.Target("dry-run", "Q"), null, null)));
        assertThrows(IllegalArgumentException.class, () -> runs.start(new Runs.Request(null, null, null, null, 1, 0, null,
                "demo", "order.json", null, null, "dry-run", "Q", new Runs.Target(sendOnly, "Q"), null, null)), "a type that cannot receive");
        assertThrows(IllegalArgumentException.class, () -> runs.start(new Runs.Request(null, null, null, null, 20_001, 0, null,
                "demo", "order.json", null, null, "dry-run", "Q", new Runs.Target("dry-run", "Q"), null, null)), "the verified-run ceiling");
        assertThrows(IllegalArgumentException.class, () -> runs.start(new Runs.Request(null, null, null, null, 1, 0, null,
                "demo", "order.json", null, null, "dry-run", "Q", new Runs.Target("dry-run", "Q"), 0, null)), "timeout range");
        assertTrue(runs.list().isEmpty());

        // without match, the first {{uuid}} or {{id}} of the body identifies the message
        Runs.View byId = finished(runs, runs.start(loopback.apply("{\"a\":\"{{id}}\"}")).id());
        assertEquals("PASS", byId.verdict());
        assertEquals(5, byId.checks().get(0).arrived());

        // match repeats a value the body bound, so a template with no uuid or id can still be verified
        Runs.View byMatch = finished(runs, runs.start(new Runs.Request(null, null, null, null, 5, 0, null,
                "demo", "order.json", "{\"ref\":\"{{ref=alnum 12}}\"}", null, "dry-run", "Q", new Runs.Target("dry-run", "Q"), null, "{{ref}}")).id());
        assertEquals("PASS", byMatch.verdict());
        assertEquals(new Runs.Check("order.json", "Dry run / Q", 5, 5, 0, 0, byMatch.checks().get(0).medianMs(), byMatch.checks().get(0).maxMs()), byMatch.checks().get(0));
        // a match that is in no arriving body finds nothing
        Runs.View wrong = finished(runs, runs.start(new Runs.Request(null, null, null, null, 2, 0, null,
                "demo", "order.json", null, null, "dry-run", "Q", new Runs.Target("dry-run", "Q"), 1, "{{alnum 20}}")).id());
        assertEquals("FAIL", wrong.verdict());
        assertEquals(2, wrong.checks().get(0).missing());

        // a run with no expectation has no checks and no verdict, and a scenario saved before expectations existed still loads and runs
        Files.writeString(dir.resolve("old.json"), """
                {"scenarios":[{"id":"old","name":"Old","parts":[{"pack":"demo","template":"order.json","weight":1,"vars":{}}],
                  "targets":[{"connection":"dry-run","destination":"Q"}],"mode":"spread","count":2,"rate":0.0,"headers":{}}]}""");
        ConfigStore old = new ConfigStore(dir.resolve("old.json").toString(), new ObjectMapper());
        Runs oldRuns = new Runs(packs, engine, new Connections(old, List.of(new DryRunType())));
        Runs.View plain = finished(oldRuns, new Scenarios(old, oldRuns).run("old").id());
        assertEquals(Runs.Status.DONE, plain.status());
        assertTrue(plain.checks().isEmpty());
        assertNull(plain.verdict());
    }

    @Test
    void arrivalsAreReceivedOverJms(@TempDir Path dir) throws Exception {
        EmbeddedActiveMQ broker = new EmbeddedActiveMQ().setConfiguration(new ConfigurationImpl()
                .setPersistenceEnabled(false).setSecurityEnabled(false).addAcceptorConfiguration("vm", "vm://1"));
        broker.start();
        try (ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("vm://1")) {
            JmsSender sender = new JmsSender(cf);
            JmsTemplate jms = new JmsTemplate(cf);
            jms.setReceiveTimeout(300);
            BlockingQueue<String> got = new LinkedBlockingQueue<>();

            BlockingQueue<Exception> receiveErrors = new LinkedBlockingQueue<>();
            JmsReceiver receiver = new JmsReceiver(cf, "OUT.Q", got::add, receiveErrors::add);
            sender.send("OUT.Q", "as text", Map.of());
            jms.send("OUT.Q", session -> {
                jakarta.jms.BytesMessage bytes = session.createBytesMessage();
                bytes.writeBytes("as bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return bytes;
            });
            receiver.check();   // the broker answers
            assertEquals("as text", got.poll(5, TimeUnit.SECONDS));
            assertEquals("as bytes", got.poll(5, TimeUnit.SECONDS));
            assertNull(jms.receive("OUT.Q"), "receiving takes the messages off the queue");
            receiver.close();
            sender.send("OUT.Q", "after close", Map.of());
            assertEquals("after close", jms.receiveAndConvert("OUT.Q"), "a closed receiver takes nothing");

            // a whole run against the broker: arrivals come in on the JMS thread while the run is still sending
            TransportType overJms = new TransportType() {
                public String id() { return "jms"; }
                public String label() { return "JMS"; }
                public List<Field> fields() { return List.of(); }
                public Sender open(Map<String, String> settings) { return new JmsSender(cf); }
                public boolean canReceive() { return true; }
                public Subscription subscribe(Map<String, String> settings, String destination, Consumer<String> onMessage, Consumer<Exception> onError) throws Exception {
                    return new JmsReceiver(cf, destination, onMessage, onError);
                }
            };
            Connections store = new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(overJms));
            String id = store.save(new Connections.Connection(null, "Artemis", "jms", Map.of(), List.of())).id();
            Runs runs = new Runs(packs, engine, store);
            Runs.View run = finished(runs, runs.start(new Runs.Request(null, null, null, null, 20, 0, null,
                    "demo", "order.json", null, null, id, "LOOP.Q", new Runs.Target(id, "LOOP.Q"), null, null)).id());
            assertEquals("PASS", run.verdict());
            assertEquals(new Runs.Check("order.json", "Artemis / LOOP.Q", 20, 20, 0, 0, run.checks().get(0).medianMs(), run.checks().get(0).maxMs()), run.checks().get(0));
            assertNull(jms.receive("LOOP.Q"), "the run's receiver is closed and left nothing behind");
            sender.close();
            assertTrue(receiveErrors.isEmpty(), "receiving, closing and a finished run report no error");

            // the broker going away under an open receiver is reported, where it used to pass in silence
            JmsReceiver orphan = new JmsReceiver(cf, "OUT.Q", got::add, receiveErrors::add);
            broker.stop();
            assertThrows(jakarta.jms.JMSException.class, orphan::check, "asked directly, a receiver whose broker is gone says so at once");
            assertTrue(receiveErrors.poll(10, TimeUnit.SECONDS) != null, "the connection failure reaches onError");
            orphan.close();
        } finally {
            broker.stop();
        }
    }

    // ---- how a run ends

    private static Runs.Request slow(String connection, String body, int count, double rate) {
        return Runs.Request.single("demo", "order.json", body, connection, "Q", count, rate, null);
    }

    @Test
    void cancellingWakesASlowRunAndARunningOneIsNeverPushedOutOfTheList(@TempDir Path dir) throws Exception {
        Recording sender = new Recording();
        Connections store = new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(fake(sender)));
        String id = store.save(new Connections.Connection(null, "Sink", "fake", Map.of("host", "x"), List.of("Q"))).id();
        Runs runs = new Runs(packs, engine, store);

        // one message every 100 seconds: after the first it sits waiting
        String waiting = runs.start(slow(id, "m{{seq}}", 5, 0.01)).id();
        for (int i = 0; i < 250 && sender.bodies.isEmpty(); i++) Thread.sleep(20);
        assertEquals(List.of("m1"), sender.bodies);

        // 25 short runs come and go; the list keeps the last 20 that ended, and the one still running
        for (int i = 0; i < 25; i++) finished(runs, runs.start(slow(id, "x", 1, 0)).id());
        assertEquals(22, runs.list().size(), "20 ended, the newest, and the slow one");
        assertEquals(waiting, runs.list().get(21).id());

        runs.cancel(waiting);
        Runs.View cancelled = finished(runs, waiting);   // gives up after 10 s; unwoken, the run would wait 100
        assertEquals(Runs.Status.CANCELLED, cancelled.status());
        assertEquals(1, cancelled.sent());
        assertTrue(cancelled.finishedAt() != null);
        assertEquals(26, sender.closed.get(), "every run closed the sender it opened: 25 short ones and the cancelled one");
        assertThrows(java.util.NoSuchElementException.class, () -> runs.cancel("nope"));

        // shutting down cancels what is running and lets it close its sender
        String atShutdown = runs.start(slow(id, "m{{seq}}", 5, 0.01)).id();
        for (int i = 0; i < 250 && runs.list().get(0).sent() == 0; i++) Thread.sleep(20);
        runs.stop();
        assertEquals(Runs.Status.CANCELLED, runs.list().get(0).status());
        assertEquals(atShutdown, runs.list().get(0).id());
        assertEquals(27, sender.closed.get());
    }

    @Test
    void aSendThatFailsMidRunEndsItAndClosesWhatItOpened(@TempDir Path dir) throws Exception {
        Recording sender = new Recording();
        AtomicInteger subscriptionsClosed = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Consumer<Exception>> receiverFailed = new java.util.concurrent.atomic.AtomicReference<>();
        TransportType failing = new TransportType() {
            public String id() { return "fake"; }
            public String label() { return "Fake"; }
            public List<Field> fields() { return List.of(); }
            public Sender open(Map<String, String> settings) { return sender; }
            public boolean canReceive() { return true; }
            public Subscription subscribe(Map<String, String> settings, String destination, Consumer<String> onMessage, Consumer<Exception> onError) {
                receiverFailed.set(onError);
                return subscriptionsClosed::incrementAndGet;
            }
        };
        Connections store = new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(failing));
        String id = store.save(new Connections.Connection(null, "Sink", "fake", Map.of(), List.of("Q"))).id();
        Runs runs = new Runs(packs, engine, store);
        Runs.Request verified = new Runs.Request(null, null, null, null, 5, 0, null, "demo", "order.json", "m{{seq}} {{uuid}}", null, id, "Q", new Runs.Target(id, "OUT"), 60, null);

        // One send that fails is a result, not the end: it is counted with its error, and the run goes on to the last message.
        Runs.Request plain = new Runs.Request(null, null, null, null, 5, 0, null, "demo", "order.json", "m{{seq}} {{uuid}}", null, id, "Q", null, null, null);
        sender.onSend = body -> { if (body.startsWith("m3 ")) throw new IllegalStateException("broker went away"); };
        Runs.View oneLost = finished(runs, runs.start(plain).id());
        assertEquals(Runs.Status.DONE, oneLost.status());
        assertEquals(4, oneLost.sent());
        assertEquals(1, oneLost.failed());
        assertEquals("FAIL", oneLost.verdict(), "nothing to verify, and still not a run to call good");
        assertEquals(List.of("broker went away"), oneLost.errors().stream().map(Stats.ErrorCount::message).toList());
        assertEquals(1, oneLost.errors().get(0).count());
        assertEquals(4, sender.bodies.size());
        assertEquals(1, sender.closed.get());
        sender.bodies.clear();

        // A connection that is plainly gone ends the run: a hundred sends in a row failed, and nobody wants the other 899,900.
        sender.onSend = body -> { if (!body.startsWith("m1 ") && !body.startsWith("m2 ")) throw new IllegalStateException("broker went away"); };
        Runs.View gone = finished(runs, runs.start(new Runs.Request(null, null, null, null, 900_000, 0, null, "demo", "order.json", "m{{seq}} {{uuid}}", null, id, "Q", null, null, null)).id());
        assertEquals(Runs.Status.FAILED, gone.status());
        assertEquals("Stopped after 100 sends in a row failed: broker went away", gone.error());
        assertEquals(2, gone.sent());
        assertEquals(100, gone.failed());
        assertEquals(2, sender.bodies.size(), "nothing is sent after the run gave up");
        assertNull(gone.verdict(), "a run that failed has a status, not a verdict");
        assertEquals(2, sender.closed.get());
        sender.bodies.clear();

        // A verified run in which nothing could be sent has no result either: it fails with the reason, and does not wait for arrivals.
        sender.onSend = body -> { throw new IllegalStateException("broker went away"); };
        Runs.View failed = finished(runs, runs.start(verified).id());
        assertEquals(Runs.Status.FAILED, failed.status());
        assertEquals("broker went away", failed.error());
        assertEquals(0, failed.sent());
        assertNull(failed.verdict());
        assertTrue(failed.checks().isEmpty());
        assertTrue(failed.finishedAt() != null);
        assertEquals(3, sender.closed.get());
        assertEquals(1, subscriptionsClosed.get());

        // an exception that carries no message is named, where the error used to read "null"
        sender.onSend = body -> { throw new java.util.concurrent.CancellationException(); };
        assertEquals("CancellationException", finished(runs, runs.start(verified).id()).error());
        sender.onSend = body -> { };

        // A receiver that breaks after it subscribed fails the run with its error, cleaned like any other connection error.
        // Nothing arrives here either way; without the report this would be 5 missing and a FAIL blamed on the system under test.
        String waiting = runs.start(verified).id();
        for (int i = 0; i < 250 && runs.list().get(0).status() != Runs.Status.VERIFYING; i++) Thread.sleep(20);
        receiverFailed.get().accept(new IllegalStateException("session closed by broker"));
        Runs.View deaf = finished(runs, waiting);
        assertEquals(Runs.Status.FAILED, deaf.status());
        assertEquals("Receiving from Sink / OUT failed: session closed by broker", deaf.error());
        assertNull(deaf.verdict());

        // an Error, as a plugin with a missing class throws, ends the run the same way: it must not stay RUNNING for ever.
        // Here it comes from a generator, on the second message; one from a connection type is already turned into a plain error.
        sender.onSend = body -> { };
        AtomicInteger calls = new AtomicInteger();
        Runs withPlugin = new Runs(packs, new TemplateEngine(List.of(new Generator() {
            public String name() { return "plugin"; }
            public String generate(List<String> args) {
                if (calls.incrementAndGet() > 2) throw new NoClassDefFoundError("org/example/Missing");   // 1 is the check before the run
                return "ok";
            }
        })), store);
        Runs.View broken = finished(withPlugin, withPlugin.start(slow(id, "{{plugin}}", 5, 0)).id());
        assertEquals(Runs.Status.FAILED, broken.status());
        assertEquals("org/example/Missing", broken.error());
        assertEquals(1, broken.sent());
        assertEquals(6, sender.closed.get());
        assertEquals(3, subscriptionsClosed.get(), "every verified run closed its subscription");
    }

    @Test
    void seqCountsFromOneInEveryRun(@TempDir Path dir) throws Exception {
        Recording sender = new Recording();
        Connections store = new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(fake(sender)));
        String id = store.save(new Connections.Connection(null, "Sink", "fake", Map.of("host", "x"), List.of("Q"))).id();
        Runs runs = new Runs(packs, engine, store);

        engine.render("{{seq}}", Map.of(), Map.of());   // a preview uses nothing up
        finished(runs, runs.start(slow(id, "m{{seq}}", 3, 0)).id());
        finished(runs, runs.start(slow(id, "n{{seq}}-{{seq}}", 2, 0)).id());
        assertEquals(List.of("m1", "m2", "m3", "n1-2", "n3-4"), sender.bodies);
        assertEquals("1", engine.render("{{seq}}", Map.of(), Map.of()));
    }

    // ---- telling messages apart when they arrive

    @Test
    void anArrivalIsTheMessageWithTheLongestTokenItContains(@TempDir Path dir) throws Exception {
        DryRunType dry = new DryRunType();
        Runs runs = new Runs(packs, engine, new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(dry)));
        // Tokens message-1 to message-24: message-1 is inside message-10 to message-19, message-2 inside message-20 to message-24.
        Runs.Request numbered = new Runs.Request(null, List.of(
                new Runs.Part("demo", "a", "A message-{{k=seq}}", 1, null, new Runs.Target("dry-run", "OUT.A")),
                new Runs.Part("demo", "b", "B message-{{k=seq}}", 1, null, new Runs.Target("dry-run", "OUT.B"))),
                List.of(IN), null, 24, 0, null, null, null, null, null, null, null, null, 2, "message-{{k}}");

        // The system under test routes correctly but delivers in reverse order: once it has all 24, the last one first.
        List<String> held = new java.util.ArrayList<>();
        TransportType.Sender out = dry.open(Map.of());
        try (TransportType.Subscription sut = dry.subscribe(Map.of(), "IN", body -> {
            held.add(body);
            if (held.size() < 24) return;
            for (int i = held.size() - 1; i >= 0; i--) {
                try {
                    out.send(held.get(i).startsWith("A") ? "OUT.A" : "OUT.B", held.get(i), Map.of());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }, e -> { })) {
            Runs.View run = finished(runs, runs.start(numbered).id());
            assertEquals("PASS", run.verdict(), run.checks().toString());
            for (Runs.Check check : run.checks()) assertEquals(12, check.arrived(), check.toString());
        }

        // And when one kind is lost, the loss is put down to that kind. "A message-1" never arrives; "B message-10" must not be taken for it.
        try (TransportType.Subscription sut = router(dry, body -> body.startsWith("A") ? null : "OUT.B")) {
            Runs.View run = finished(runs, runs.start(numbered).id());
            assertEquals("FAIL", run.verdict());
            assertEquals(new Runs.Check("a", "Dry run / OUT.A", 12, 0, 0, 12, null, null), run.checks().get(0));
            assertEquals(12, run.checks().get(1).arrived());
        }
    }

    @Test
    void aTokenTooShortToBeTrustedIsRefused(@TempDir Path dir) throws Exception {
        DryRunType dry = new DryRunType();
        Runs runs = new Runs(packs, engine, new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(dry)));
        // Bodies with other digits in them. Matched by the counter, the 1 of message 1 is also in every "11:13":
        // with all of A lost, half of B's arrivals were taken for A's and reported as misrouted.
        Function<String, Runs.Request> at1113 = match -> new Runs.Request(null, List.of(
                new Runs.Part("demo", "a", "A {{k=seq}} {{ref=id}} at 11:13", 1, null, new Runs.Target("dry-run", "OUT.A")),
                new Runs.Part("demo", "b", "B {{k=seq}} {{ref=id}} at 11:13", 1, null, new Runs.Target("dry-run", "OUT.B"))),
                List.of(IN), null, 12, 0, null, null, null, null, null, null, null, null, 1, match);

        String why = assertThrows(IllegalArgumentException.class, () -> runs.start(at1113.apply("{{k}}"))).getMessage();
        assertTrue(why.startsWith("a would be recognised by \"1\", which is too short") && why.contains("{{uuid}}"), why);
        assertThrows(IllegalArgumentException.class, () -> runs.start(at1113.apply("msg-{{k}}")), "seven characters");
        assertTrue(runs.list().isEmpty());

        // The same messages matched by a long value are reported as what happened: A lost, B arrived.
        try (TransportType.Subscription sut = router(dry, body -> body.startsWith("A") ? null : "OUT.B")) {
            Runs.View run = finished(runs, runs.start(at1113.apply("{{ref}}")).id());
            assertEquals("FAIL", run.verdict());
            assertEquals(new Runs.Check("a", "Dry run / OUT.A", 6, 0, 0, 6, null, null), run.checks().get(0));
            Runs.Check b = run.checks().get(1);
            assertEquals(new Runs.Check("b", "Dry run / OUT.B", 6, 6, 0, 0, b.medianMs(), b.maxMs()), b);
        }
        // eight characters is enough: a counter behind a prefix passes
        try (TransportType.Subscription sut = router(dry, body -> body.startsWith("A") ? "OUT.A" : "OUT.B")) {
            Runs.Request prefixed = new Runs.Request(null, null, null, null, 3, 0, null, "demo", "order.json", "A number-{{k=seq}}", null,
                    "dry-run", "IN", new Runs.Target("dry-run", "OUT.A"), 1, "number-{{k}}");
            assertEquals("PASS", finished(runs, runs.start(prefixed).id()).verdict());
        }
    }

    @Test
    void aMatchThatCannotTellMessagesApartIsRefused(@TempDir Path dir) throws Exception {
        Recording sender = new Recording();
        Connections store = new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(new DryRunType(), fake(sender)));
        String sink = store.save(new Connections.Connection(null, "Sink", "fake", Map.of("host", "x"), List.of("Q"))).id();
        Function<String, Runs.Request> matching = match -> new Runs.Request(null, null, null, null, 5, 0, null,
                "demo", "order.json", null, null, sink, "Q", new Runs.Target("dry-run", "NOBODY.FORWARDS.HERE"), 1, match);

        // a constant is in every arriving body, so it would "recognise" them all: 3 of 3 with nothing told apart
        Runs runs = new Runs(packs, engine, store);
        String why = assertThrows(IllegalArgumentException.class, () -> runs.start(matching.apply("orderId"))).getMessage();
        assertTrue(why.contains("must differ per message") && why.contains("orderId"), why);
        assertThrows(IllegalArgumentException.class, () -> runs.start(matching.apply("{{pick same same}}")));
        assertTrue(runs.list().isEmpty() && sender.bodies.isEmpty());

        // An expression that differs when it is tried but repeats during the run: the message that would share a token is not sent.
        List<String> tokens = new java.util.ArrayList<>(List.of("trial-0001", "trial-0002", "token-01", "token-02", "token-02", "token-03"));
        Runs repeating = new Runs(packs, new TemplateEngine(List.of(new Generator() {
            public String name() { return "token"; }
            public String generate(List<String> args) { return tokens.remove(0); }
        })), store);
        Runs.View run = finished(repeating, repeating.start(matching.apply("{{token}}")).id());
        assertEquals(Runs.Status.FAILED, run.status());
        assertTrue(run.error().startsWith("Message 3 is identified by the same text as one still awaited (token-02)"), run.error());
        assertEquals(2, run.sent());
        assertEquals(2, sender.bodies.size());
    }

    @Test
    void emptyEntriesAndRatesTooSmallToTimeAreRefused(@TempDir Path dir) throws Exception {
        ConfigStore config = new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper());
        Runs runs = new Runs(packs, engine, new Connections(config, List.of(new DryRunType())));
        Scenarios scenarios = new Scenarios(config, runs);
        List<Runs.Part> parts = List.of(new Runs.Part("demo", "order.json", null, 1, null, null));
        List<Runs.Target> withNull = java.util.Arrays.asList(new Runs.Target("dry-run", "Q"), null);
        List<Runs.Part> partsWithNull = java.util.Arrays.asList(parts.get(0), null);
        Function<Double, Runs.Request> atRate = rate -> Runs.Request.single("demo", "order.json", null, "dry-run", "Q", 2, rate, null);

        assertThrows(IllegalArgumentException.class, () -> scenarios.save(new Scenarios.Scenario(null, "s", parts, withNull, null, 1, 0, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> scenarios.save(new Scenarios.Scenario(null, "s", partsWithNull, List.of(IN), null, 1, 0, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> runs.start(new Runs.Request(null, parts, withNull, null, 1, 0, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> runs.start(new Runs.Request(null, partsWithNull, List.of(IN), null, 1, 0, null, null, null, null, null, null, null, null, null, null)));

        // 1e-10 a second is a pause of three centuries, which overflowed into no pause at all
        String why = assertThrows(IllegalArgumentException.class, () -> runs.start(atRate.apply(1e-10))).getMessage();
        assertEquals("Per second must be 0 (as fast as possible) or at least 0.001", why);
        assertThrows(IllegalArgumentException.class, () -> scenarios.save(new Scenarios.Scenario(null, "s", parts, List.of(IN), null, 1, 0.0009, null, null, null)));
        assertTrue(runs.list().isEmpty());
        // the slowest rate allowed does pause: one message goes out and the run waits
        String slowest = runs.start(atRate.apply(0.001)).id();
        for (int i = 0; i < 250 && runs.list().get(0).sent() == 0; i++) Thread.sleep(20);
        Thread.sleep(200);
        assertEquals(1, runs.list().get(0).sent());
        assertEquals(Runs.Status.RUNNING, runs.list().get(0).status());
        runs.cancel(slowest);
        assertEquals(Runs.Status.CANCELLED, finished(runs, slowest).status());
    }

    @Test
    void beforeMessagesAreCalledMissingTheSourceIsAskedWhetherItIsStillThere(@TempDir Path dir) throws Exception {
        // A receiver that hears nothing, like a client whose broker died a second ago and has not noticed yet.
        AtomicInteger asked = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Exception> answer = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> frozen = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Exception> reportWhenAsked = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch> questionHangs = new java.util.concurrent.atomic.AtomicReference<>();
        TransportType silent = new TransportType() {
            public String id() { return "fake"; }
            public String label() { return "Fake"; }
            public List<Field> fields() { return List.of(new Field("password", "Password", "", false, true)); }
            public Sender open(Map<String, String> settings) { return (destination, body, headers) -> { }; }
            public boolean canReceive() { return true; }
            public Subscription subscribe(Map<String, String> settings, String destination, Consumer<String> onMessage, Consumer<Exception> onError) {
                return new Subscription() {
                    public void check() throws Exception {
                        asked.incrementAndGet();
                        if (reportWhenAsked.get() != null) onError.accept(reportWhenAsked.get());
                        if (questionHangs.get() != null) questionHangs.get().await();
                        if (frozen.get() != null) frozen.get().await();
                        if (answer.get() != null) throw answer.get();
                    }
                    public void close() {
                        try {
                            if (frozen.get() != null) frozen.get().await();
                        } catch (InterruptedException ignored) {
                            // the test is over
                        }
                    }
                };
            }
        };
        Connections store = new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(silent, new DryRunType()));
        String id = store.save(new Connections.Connection(null, "Broker", "fake", Map.of("password", "s3cret"), List.of("Q"))).id();
        Runs runs = new Runs(packs, engine, store);
        Runs.Request oneSecond = new Runs.Request(null, null, null, null, 3, 0, null, "demo", "order.json", null, null, id, "Q", new Runs.Target(id, "OUT"), 1, null);

        // the source answers: the messages are missing, and that is a result
        Runs.View healthy = finished(runs, runs.start(oneSecond).id());
        assertEquals(Runs.Status.DONE, healthy.status());
        assertEquals("FAIL", healthy.verdict());
        assertEquals(3, healthy.checks().get(0).missing());
        assertEquals(1, asked.get());

        // the source does not answer: nobody knows what arrived, and saying "missing" would blame the system under test
        answer.set(new java.io.IOException("no answer from the broker (login s3cret)"));
        Runs.View dead = finished(runs, runs.start(oneSecond).id());
        assertEquals(Runs.Status.FAILED, dead.status());
        assertEquals("Receiving from Broker / OUT failed: no answer from the broker (login ***)", dead.error());
        assertNull(dead.verdict());
        assertEquals(2, asked.get());

        // The source neither answers nor refuses, like a broker that is frozen: the client would wait for minutes.
        // The run waits its limit and ends; closing a receiver in that state is not waited for either.
        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        answer.set(null);
        frozen.set(never);
        runs.brokerAnswerSeconds = 1;
        long began = System.nanoTime();
        Runs.View stuck = finished(runs, runs.start(oneSecond).id());
        assertEquals(Runs.Status.FAILED, stuck.status());
        assertEquals("Receiving from Broker / OUT failed: no answer within 1 seconds", stuck.error());
        assertTrue(System.nanoTime() - began < 8_000_000_000L, "1 s of timeout, 1 s for the question, 1 s for the close");
        frozen.set(null);
        never.countDown();
        runs.brokerAnswerSeconds = 15;
        asked.set(2);

        // The receiver reports its own failure while the question is out and unanswered, as a JMS exception listener or a
        // Kafka poll thread does. The run ends with that report, at once: it says why, the question would only time out.
        java.util.concurrent.CountDownLatch unanswered = new java.util.concurrent.CountDownLatch(1);
        questionHangs.set(unanswered);   // the question only; closing works, as it does once a client knows its connection is gone
        reportWhenAsked.set(new IllegalStateException("the broker closed the connection"));
        began = System.nanoTime();
        Runs.View told = finished(runs, runs.start(oneSecond).id());
        assertEquals(Runs.Status.FAILED, told.status());
        assertEquals("Receiving from Broker / OUT failed: the broker closed the connection", told.error());
        assertTrue(System.nanoTime() - began < 8_000_000_000L, "not the 15 s an unanswered question is given: " + (System.nanoTime() - began) / 1_000_000 + " ms");
        questionHangs.set(null);
        reportWhenAsked.set(null);
        unanswered.countDown();
        asked.set(2);

        // a run in which everything arrived asks nothing: it ends as fast as before
        Runs.Request loop = new Runs.Request(null, null, null, null, 3, 0, null, "demo", "order.json", null, null, "dry-run", "Q", new Runs.Target("dry-run", "Q"), 60, null);
        assertEquals("PASS", finished(runs, runs.start(loop).id()).verdict());
        assertEquals(2, asked.get());
    }

    @Test
    void copyingToTwoPlacesThatAreBothExpectedIsRefused(@TempDir Path dir) throws Exception {
        Runs runs = new Runs(packs, engine, new Connections(new ConfigStore(dir.resolve("c.json").toString(), new ObjectMapper()), List.of(new DryRunType())));
        Runs.Target a = new Runs.Target("dry-run", "A"), b = new Runs.Target("dry-run", "B"), c = new Runs.Target("dry-run", "C");
        List<Runs.Part> twoKinds = List.of(new Runs.Part("demo", "order.json", null, 1, null, a), new Runs.Part("demo", "shipment.xml", null, 1, null, b));
        Function<List<Runs.Target>, Runs.Request> copiedTo = targets ->
                new Runs.Request(null, twoKinds, targets, "all", 4, 0, null, null, null, null, null, null, null, null, 1, null);

        // every message would land on A and on B; expected on one of them, it also "arrives" on the other, and the first to come decides
        String why = assertThrows(IllegalArgumentException.class, () -> runs.start(copiedTo.apply(List.of(a, b)))).getMessage();
        assertTrue(why.contains("Dry run / A and Dry run / B") && why.contains("Use Spread"), why);
        assertTrue(runs.list().isEmpty());

        // one watched target among the copies is fine: order messages arrive on A where they are expected, the others nowhere they are expected
        Runs.View oneWatched = finished(runs, runs.start(copiedTo.apply(List.of(a, c))).id());
        assertEquals(Runs.Status.DONE, oneWatched.status());
        assertEquals(2, oneWatched.checks().get(0).arrived());
        assertEquals(0, oneWatched.checks().get(0).misrouted());
        // and so is Spread onto both, where each message goes to one place only
        Runs.Request spread = new Runs.Request(null, twoKinds, List.of(a, b), "spread", 4, 0, null, null, null, null, null, null, null, null, 1, null);
        assertEquals(Runs.Status.DONE, finished(runs, runs.start(spread).id()).status());
    }

    @Test
    void helpPrintsTheSettingsAndStartsNothing() {
        java.io.ByteArrayOutputStream printed = new java.io.ByteArrayOutputStream();
        java.io.PrintStream out = System.out;
        System.setOut(new java.io.PrintStream(printed));
        try {
            MsgforgeApplication.main(new String[] {"--server.port=1", "--help"});   // returns: nothing was started, least of all on port 1
        } finally {
            System.setOut(out);
        }
        String usage = printed.toString();
        for (String setting : List.of("PORT", "SERVER_ADDRESS", "MSGFORGE_CONFIG", "MSGFORGE_PACKS", "MSGFORGE_PLUGINS", "MSGFORGE_ALLOWED_HOSTS", "MSGFORGE_ENV_ALLOW")) {
            assertTrue(usage.contains(setting), setting);
        }
        assertFalse(usage.contains("Started"), usage);

        // the address printed at start is one a browser can open, also when the server listens on every address
        assertEquals("http://localhost:8585", MsgforgeApplication.address("127.0.0.1", 8585));
        assertEquals("http://localhost:8686", MsgforgeApplication.address("0.0.0.0", 8686));
        assertEquals("http://localhost:8585", MsgforgeApplication.address("::", 8585));
        assertEquals("http://console.example:80", MsgforgeApplication.address("console.example", 80));
        assertEquals("http://[fe80::1]:8585", MsgforgeApplication.address("fe80::1", 8585));

        // bound to an address of its own, the link only works once that name is allowed, and the line says so
        assertEquals("msgforge is ready: open http://localhost:8585", MsgforgeApplication.ready("0.0.0.0", 8585, ""));
        assertEquals("msgforge is ready: open http://[::1]:8585", MsgforgeApplication.ready("::1", 8585, ""));
        assertEquals("msgforge is ready: open http://192.168.1.5:8585 (add 192.168.1.5 to MSGFORGE_ALLOWED_HOSTS first, or the console answers 403)",
                MsgforgeApplication.ready("192.168.1.5", 8585, "other.example"));
        assertEquals("msgforge is ready: open http://192.168.1.5:8585", MsgforgeApplication.ready("192.168.1.5", 8585, "other.example, 192.168.1.5"));
        assertEquals("msgforge is ready: open http://Console.example:80", MsgforgeApplication.ready("Console.example", 80, "*"));
    }
}
