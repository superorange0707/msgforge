package io.msgforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The command line against Dry run scenarios: here in the process, and on a server started for the test. */
class CliTest {

    @TempDir
    static Path dir;

    @BeforeAll
    static void scenarios() throws Exception {
        String loop = """
                "parts":[{"pack":"demo","template":"order.json","weight":1,"expect":{"connection":"dry-run","destination":"IN"}}],
                "targets":[{"connection":"dry-run","destination":"IN"}],"count":5,"rate":0""";
        Files.writeString(dir.resolve("msgforge.json"), """
                {"connections":[{"id":"dry-run","name":"Dry run","type":"dry-run","settings":{},"destinations":["IN"]}],
                 "scenarios":[
                  {"id":"s-pass","name":"Loop",%s},
                  {"id":"s-fail","name":"Lost","timeoutSeconds":1,
                   "parts":[{"pack":"demo","template":"order.json","weight":1,"expect":{"connection":"dry-run","destination":"ELSEWHERE"}}],
                   "targets":[{"connection":"dry-run","destination":"IN"}],"count":3,"rate":0},
                  {"id":"s-plain","name":"Send only","parts":[{"pack":"demo","template":"reading.csv","weight":1}],
                   "targets":[{"connection":"dry-run","destination":"IN"}],"count":2,"rate":0},
                  {"id":"s-nopack","name":"No pack","parts":[{"pack":"gone","template":"a.txt","weight":1}],
                   "targets":[{"connection":"dry-run","destination":"IN"}],"count":1,"rate":0},
                  {"id":"s-load","name":"Load","parts":[{"pack":"demo","template":"order.json","weight":1}],
                   "targets":[{"connection":"dry-run","destination":"IN"}],"count":0,"rate":200,
                   "load":{"durationSeconds":60,"senders":1},"invalid":{"percent":10,"kinds":["truncated","type-changed"]}},
                  {"id":"twin-1","name":"Twin",%s},
                  {"id":"twin-2","name":"Twin",%s}]}""".formatted(loop, loop, loop));
    }

    private record Result(int code, String out) {}

    private static Result cli(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<String> all = new ArrayList<>(List.of(args));
        if (!all.contains("--url")) {
            if (all.stream().noneMatch(a -> a.startsWith("--msgforge.config="))) all.add("--msgforge.config=" + dir.resolve("msgforge.json"));
            all.addAll(List.of("--msgforge.packs=packs",
                    "--msgforge.plugins=" + dir.resolve("no-plugins"), "--spring.artemis.embedded.enabled=false"));
        }
        int code = Cli.main(all.toArray(String[]::new), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return new Result(code, bytes.toString(StandardCharsets.UTF_8));
    }

    private static Element report(Path file) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    @Test
    void aPassingRunExits0AndReportsEachCheck() throws Exception {
        Path file = dir.resolve("reports/pass.xml");
        Result r = cli("run", "Loop", "--report", file.toString(), "--count=4");
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("PASS order.json → Dry run / IN: sent 4, arrived 4, misrouted 0, missing 0"), r.out());
        Element suite = report(file);
        assertEquals("Loop", suite.getAttribute("name"));
        assertEquals("1", suite.getAttribute("tests"));
        assertEquals("0", suite.getAttribute("failures"));
        Element test = (Element) suite.getElementsByTagName("testcase").item(0);
        assertEquals("order.json → Dry run / IN", test.getAttribute("name"));
        assertEquals(0, test.getChildNodes().getLength());
    }

    @Test
    void aFailedCheckExits1AndTheReportSaysWhatWasMissing() throws Exception {
        Path file = dir.resolve("fail.xml");
        Result r = cli("run", "s-fail", "--report=" + file);
        assertEquals(1, r.code(), r.out());
        assertTrue(r.out().contains("FAIL order.json → Dry run / ELSEWHERE: sent 3, arrived 0, misrouted 0, missing 3"), r.out());
        Element suite = report(file);
        assertEquals("1", suite.getAttribute("failures"));
        NodeList failure = suite.getElementsByTagName("failure");
        assertEquals("sent 3, arrived 0, misrouted 0, missing 3", ((Element) failure.item(0)).getAttribute("message"));
    }

    @Test
    void aRunWithNothingToVerifyExits0WithOneTestcase() throws Exception {
        Path file = dir.resolve("plain.xml");
        Result r = cli("run", "Send only", "--report", file.toString());
        assertEquals(0, r.code(), r.out());
        Element suite = report(file);
        assertEquals("1", suite.getAttribute("tests"));
        assertEquals("Send only", ((Element) suite.getElementsByTagName("testcase").item(0)).getAttribute("name"));
    }

    @Test
    void anythingElseExits2AndIsAnErrorInTheReport() throws Exception {
        Path file = dir.resolve("missing.xml");
        Result unknown = cli("run", "Nope", "--report", file.toString());
        assertEquals(2, unknown.code());
        assertTrue(unknown.out().contains("No scenario named Nope"), unknown.out());
        assertEquals("1", report(file).getAttribute("errors"));

        Result noPack = cli("run", "No pack");
        assertEquals(2, noPack.code());
        assertTrue(noPack.out().contains("gone"), noPack.out());

        Result twins = cli("run", "Twin");
        assertEquals(2, twins.code());
        assertTrue(twins.out().contains("twin-1, twin-2"), twins.out());

        assertEquals(2, cli("run", "Loop", "--count", "0").code());
        Result fast = cli("run", "Loop", "--rate", "fast");
        assertEquals(2, fast.code());
        assertTrue(fast.out().contains("--rate needs a number, not fast"), fast.out());
        assertEquals(2, cli("run").code());

        Files.writeString(dir.resolve("bad.json"), "{not json");
        Result badConfig = cli("list", "--msgforge.config=" + dir.resolve("bad.json"));
        assertEquals(2, badConfig.code());
        assertTrue(badConfig.out().contains("is not valid JSON"), badConfig.out());
        assertFalse(badConfig.out().contains("Error creating bean"), badConfig.out());
    }

    @Test
    void aLoginPageInFrontOfTheServerIsNamedAsSuch() throws Exception {
        com.sun.net.httpserver.HttpServer proxy = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            byte[] page = "<html>Sign in</html>".getBytes();
            exchange.sendResponseHeaders(302, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        proxy.start();
        try {
            Result r = cli("list", "--url", "http://127.0.0.1:" + proxy.getAddress().getPort());
            assertEquals(2, r.code());
            assertTrue(r.out().contains("answered 302 with something that is not msgforge's answer"), r.out());
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void settingsMayComeBeforeTheCommand() {
        assertTrue(Cli.handles(new String[] {"--msgforge.config=x.json", "run", "Loop"}));
        assertTrue(Cli.handles(new String[] {"--msgforge.config=x.json", "list"}));
        assertFalse(Cli.handles(new String[] {"--server.port=8686"}));
        Result r = cli("--msgforge.packs=packs", "run", "Loop", "--count", "2");
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("sent 2, arrived 2"), r.out());
    }

    @Test
    void theLoadOptionsReplaceTheScenariosPlanAndSaveWritesEveryReport() throws Exception {
        Path out = dir.resolve("saved");
        Result r = cli("run", "Load", "--duration", "1", "--ramp-up=1", "--senders", "2", "--seed", "9", "--invalid", "40", "--save", out.toString());
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("no number of messages, for 1 s at most, 200.0 a second"), r.out());
        assertTrue(r.out().contains("DONE (nothing to verify)") && r.out().contains("seed 9") && r.out().contains("send p50"), r.out());
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("run ([0-9a-f]{8}),").matcher(r.out());
        assertTrue(id.find(), r.out());
        java.util.regex.Matcher sent = java.util.regex.Pattern.compile("done, sent (\\d+), invalid (\\d+)").matcher(r.out());
        assertTrue(sent.find(), r.out());
        int n = Integer.parseInt(sent.group(1)), invalid = Integer.parseInt(sent.group(2));
        assertTrue(n > 10 && n <= 101, "200 a second reached over the one second it ran: " + n);
        assertTrue(invalid > n / 5 && invalid < n * 3 / 5, "40 percent of " + n + ": " + invalid);
        for (String file : List.of(".html", ".json", ".csv", ".series.csv")) assertTrue(Files.size(out.resolve("msgforge-" + id.group(1) + file)) > 60, file);
        assertTrue(Files.readString(out.resolve("msgforge-" + id.group(1) + ".csv")).contains("\r\nseed,9\r\n"));
        assertTrue(Files.readString(out.resolve("msgforge-" + id.group(1) + ".html")).contains("<svg class=\"chart\""));

        // the loop hands invalid messages back like any others: a system that accepts them fails the run
        Result accepted = cli("run", "Loop", "--invalid", "50", "--seed", "3", "--count", "20");
        assertEquals(1, accepted.code(), accepted.out());
        assertTrue(accepted.out().contains("FAIL order.json (invalid) → nowhere: sent ") && accepted.out().contains("accepted although invalid"), accepted.out());
        // an option that cannot work is the scenario's error, with the reason
        Result csv = cli("run", "Send only", "--invalid", "50");
        assertEquals(2, csv.code(), csv.out());
        assertTrue(csv.out().contains("reading.csv is neither JSON nor XML"), csv.out());
        assertEquals(2, cli("run", "Load", "--senders", "many").code());
        assertTrue(cli("run", "Load", "--senders", "many").out().contains("--senders needs a number, not many"));
    }

    @Test
    void listShowsIdsAndNamesAndChangesNothing() throws Exception {
        String before = Files.readString(dir.resolve("msgforge.json"));
        Result r = cli("list");
        assertEquals(0, r.code());
        assertTrue(r.out().contains("s-pass  Loop\n"), r.out());
        assertEquals(before, Files.readString(dir.resolve("msgforge.json")));
    }

    @Test
    void urlRunsTheScenarioOnARunningServer() throws Exception {
        ConfigurableApplicationContext server = SpringApplication.run(MsgforgeApplication.class, "--server.port=0",
                "--msgforge.config=" + dir.resolve("msgforge.json"), "--msgforge.packs=packs", "--msgforge.plugins=" + dir.resolve("no-plugins"),
                "--spring.artemis.embedded.enabled=false", "--spring.main.banner-mode=off");
        try {
            String url = "http://localhost:" + server.getEnvironment().getProperty("local.server.port");

            Result pass = cli("run", "Loop", "--url", url, "--count", "3");
            assertEquals(0, pass.code(), pass.out());
            assertTrue(pass.out().contains("sent 3, arrived 3"), pass.out());

            Path file = dir.resolve("remote.xml");
            Result fail = cli("run", "Lost", "--url", url, "--report", file.toString());
            assertEquals(1, fail.code(), fail.out());
            assertEquals("1", report(file).getAttribute("failures"));

            Result noPack = cli("run", "No pack", "--url", url);
            assertEquals(2, noPack.code(), noPack.out());
            assertTrue(noPack.out().contains("answered 4"), noPack.out());

            // the load options and the reports travel over the API too
            Path saved = dir.resolve("remote-saved");
            Result load = cli("run", "Load", "--url", url, "--duration", "1", "--senders", "2", "--seed", "4", "--save", saved.toString());
            assertEquals(0, load.code(), load.out());
            assertTrue(load.out().contains("seed 4") && load.out().contains("for 1 s at most"), load.out());
            try (var files = Files.list(saved)) {
                assertEquals(4, files.count());
            }

            assertEquals(2, cli("run", "Nope", "--url", url).code());
            assertTrue(cli("list", "--url", url).out().contains("s-plain  Send only"));
            // the runs went through the server: it lists them
            assertFalse(server.getBean(Runs.class).list().isEmpty());
        } finally {
            server.close();
        }
        Result unreachable = cli("run", "Loop", "--url", "http://localhost:1");
        assertEquals(2, unreachable.code());
        assertTrue(unreachable.out().contains("cannot reach http://localhost:1"), unreachable.out());
    }
}
