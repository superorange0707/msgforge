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
        assertEquals("arrived 0 of 3, misrouted 0, missing 3", ((Element) failure.item(0)).getAttribute("message"));
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
        assertEquals(2, cli("run", "Loop", "--rate", "fast").code());
        assertEquals(2, cli("run").code());

        Files.writeString(dir.resolve("bad.json"), "{not json");
        Result badConfig = cli("list", "--msgforge.config=" + dir.resolve("bad.json"));
        assertEquals(2, badConfig.code());
        assertTrue(badConfig.out().contains("is not valid JSON"), badConfig.out());
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

            assertEquals(2, cli("run", "Nope", "--url", url).code());
            assertTrue(cli("list", "--url", url).out().contains("s-plain  Send only"));
            // the runs went through the server: it lists them
            assertFalse(server.getBean(Runs.class).list().isEmpty());
        } finally {
            server.close();
        }
        assertEquals(2, cli("run", "Loop", "--url", "http://localhost:1").code());
    }
}
