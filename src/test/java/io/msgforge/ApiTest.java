package io.msgforge;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/** The application as it starts: real beans, the Kafka plugin from its jar, a saved file with awkward entries in it. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiTest {

    private static final String HOST = "localhost:8585";

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void files(DynamicPropertyRegistry registry) throws Exception {
        // A connection whose type is not installed, with a password, and an entry with nearly everything left out.
        Files.writeString(dir.resolve("msgforge.json"), """
                {"connections":[
                  {"id":"dry-run","name":"Dry run","type":"dry-run","settings":{},"destinations":["Q"]},
                  {"id":"gone","name":"Old broker","type":"rabbit","settings":{"host":"h","password":"S3CRET-GONE"},"destinations":["Q"]},
                  {"id":"typo","name":"Hand written","type":"ibm-mq","destinations":["Q"],
                   "settings":{"hosts":"mq1","channel":"C","pasword":"TYP0-SECRET","sslPassword":"0LD-KEY-SECRET","password":"R1GHT-SECRET"}},
                  {"type":"dry-run"}],
                 "scenarios":[{"name":"Bare"}],
                 "monitors":[{"id":"always","name":"Rejected","connection":"dry-run","destination":"ALWAYS.REJECTED","buckets":[{"pointer":"/reason"}]},
                             {"id":"orphan","connection":"gone","destination":"Q"}]}""");
        // Packs with something wrong: a pack.yml that does not parse, a template that is not UTF-8, a file where a folder belongs.
        Files.createDirectories(dir.resolve("packs/broken/templates"));
        Files.writeString(dir.resolve("packs/broken/pack.yml"), "name: [unclosed");
        Files.writeString(dir.resolve("packs/broken/templates/a.txt"), "x");
        Files.createDirectories(dir.resolve("packs/odd/templates"));
        Files.write(dir.resolve("packs/odd/templates/latin1.txt"), new byte[] {'c', 'a', 'f', (byte) 0xE9});
        Files.createDirectories(dir.resolve("packs/blocked"));
        Files.writeString(dir.resolve("packs/blocked/templates"), "a file where the templates folder belongs");
        Files.createDirectories(dir.resolve("plugins"));
        Files.copy(Path.of(System.getProperty("msgforge.test.kafkaPlugin")), dir.resolve("plugins/kafka.jar"));
        registry.add("msgforge.config", () -> dir.resolve("msgforge.json").toString());
        registry.add("msgforge.plugins", () -> dir.resolve("plugins").toString());
        registry.add("msgforge.packs", () -> dir.resolve("packs") + ",packs");
        registry.add("msgforge.allowed-hosts", () -> "console.example, Other.Example");
        registry.add("msgforge.env-allow", () -> "HOME");
        registry.add("msgforge.show", () -> "HOME");
        registry.add("msgforge.reports", () -> dir.resolve("reports").toString());
        Files.writeString(dir.resolve("team.css"), ":root { --accent: #00aeef; }");
        registry.add("msgforge.theme", () -> dir.resolve("team.css").toString());
        registry.add("msgforge.title", () -> "Orders bench");
        registry.add("spring.artemis.embedded.enabled", () -> "false");   // the broker on the test classpath belongs to MsgforgeTest
    }

    @TestConfiguration
    static class Browser {
        /** Requests carry a Host header, as every real one does. */
        @Bean
        MockMvcBuilderCustomizer hostHeader() {
            return builder -> builder.defaultRequest(get("/").header("Host", HOST));
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, String content) {
        return request.contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private JsonNode sent(MockHttpServletRequestBuilder request, String content) throws Exception {
        return json.readTree(mvc.perform(body(request, content)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String connections() throws Exception {
        return mvc.perform(get("/api/connections")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** Waits for the newest run to end and returns it. */
    private JsonNode lastRun() throws Exception {
        for (int i = 0; i < 500; i++) {
            JsonNode run = json.readTree(mvc.perform(get("/api/runs")).andReturn().getResponse().getContentAsString()).get(0);
            if (!run.get("finishedAt").isNull()) return run;
            Thread.sleep(20);
        }
        throw new AssertionError("the run did not end");
    }

    @Test
    void onlyLocalAndAllowedHostsAreAnswered() throws Exception {
        for (String ok : new String[] {"localhost:8585", "localhost", "127.0.0.1:8585", "[::1]:8585", "console.example", "other.example:443"}) {
            mvc.perform(get("/api/packs").header("Host", ok)).andExpect(status().isOk());
        }
        // a page that rebinds its own name to 127.0.0.1 is same-origin to the browser, but still says its name here
        mvc.perform(get("/api/connections").header("Host", "evil.example:8585")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("MSGFORGE_ALLOWED_HOSTS")));
        mvc.perform(get("/api/connections").header("Host", "localhost.evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/index.html").header("Host", "evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }

    @Test
    void writesFromAnotherSiteAreRefused() throws Exception {
        String preview = "{\"pack\":\"demo\",\"body\":\"x\"}";
        mvc.perform(body(post("/api/preview"), preview)).andExpect(status().isOk());
        mvc.perform(body(post("/api/preview"), preview).header("Origin", "http://" + HOST)).andExpect(status().isOk());
        mvc.perform(body(post("/api/preview"), preview).header("Origin", "http://evil.example")).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/preview"), preview).header("Origin", "http://localhost:9999")).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/preview"), preview).header("Origin", "null")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/runs/x").header("Origin", "http://evil.example")).andExpect(status().isForbidden());
        mvc.perform(body(put("/api/connections/x"), "{}").header("Origin", "http://evil.example")).andExpect(status().isForbidden());
        // a browser that says the request crosses sites is believed, whatever Origin says
        mvc.perform(body(post("/api/preview"), preview).header("Sec-Fetch-Site", "cross-site")).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/preview"), preview).header("Sec-Fetch-Site", "same-site")).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/preview"), preview).header("Sec-Fetch-Site", "same-origin")).andExpect(status().isOk());
        // reads carry no Origin check: a browser does not let another site see the answer
        mvc.perform(get("/api/packs").header("Origin", "http://evil.example")).andExpect(status().isOk());
    }

    @Test
    void noPageMayShowTheConsoleInAFrame() throws Exception {
        for (String path : new String[] {"/index.html", "/api/packs"}) {
            mvc.perform(get(path)).andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Content-Security-Policy", "frame-ancestors 'none'"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        }
        mvc.perform(get("/api/packs").header("Host", "evil.example")).andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void aConnectionWhoseTypeIsNotInstalledShowsNoSettingsAndFailsReadably() throws Exception {
        JsonNode gone = null;
        for (JsonNode c : json.readTree(connections())) if (c.get("id").asText().equals("gone")) gone = c;
        assertEquals("rabbit (not installed)", gone.get("typeLabel").asText());
        assertTrue(gone.get("settings").isEmpty() && gone.get("secretsSet").isEmpty(), gone.toString());
        assertFalse(connections().contains("S3CRET-GONE"));

        sent(post("/api/runs"), "{\"pack\":\"demo\",\"template\":\"order.json\",\"connection\":\"gone\",\"destination\":\"Q\",\"count\":1}");
        JsonNode run = lastRun();
        assertEquals("FAILED", run.get("status").asText());
        assertTrue(run.get("error").asText().contains("\"rabbit\", which is not installed"), run.toString());
        mvc.perform(body(post("/api/runs"), "{\"pack\":\"demo\",\"template\":\"order.json\",\"connection\":\"dry-run\",\"destination\":\"Q\",\"count\":1,"
                + "\"expect\":{\"connection\":\"gone\",\"destination\":\"Q\"}}")).andExpect(status().isBadRequest());
    }

    @Test
    void anEnvironmentValueNeverComesBackInAnError() throws Exception {
        String home = System.getenv("HOME");   // any variable that is set; a directory, so not a usable trust store
        String asTrustStore = sent(post("/api/connections/test"), "{\"name\":\"x\",\"type\":\"ibm-mq\",\"settings\":"
                + "{\"hosts\":\"h\",\"channel\":\"c\",\"cipherSuite\":\"X\",\"trustStore\":\"${HOME}\"}}").toString();
        assertTrue(asTrustStore.contains("Trust store file not found: ${HOME}"), asTrustStore);
        assertFalse(asTrustStore.contains(home), asTrustStore);
        // inside a longer value, and through a type that takes the value apart
        String inside = sent(post("/api/connections/test"), "{\"name\":\"x\",\"type\":\"ibm-mq\",\"settings\":"
                + "{\"hosts\":\"h\",\"channel\":\"c\",\"cipherSuite\":\"X\",\"keyStore\":\"${HOME}/no/such.p12\"}}").toString();
        assertTrue(inside.contains("${HOME}/no/such.p12"), inside);
        assertFalse(inside.contains(home), inside);
        String asHosts = sent(post("/api/connections/test"), "{\"name\":\"x\",\"type\":\"ibm-mq\",\"settings\":{\"hosts\":\"${HOME}\",\"channel\":\"c\"}}").toString();
        assertTrue(asHosts.contains("\"ok\":false"), asHosts);
        assertFalse(asHosts.contains(home), asHosts);
        // a variable outside the allow-list is refused by name, and not read
        String notAllowed = sent(post("/api/connections/test"), "{\"name\":\"x\",\"type\":\"http\",\"settings\":{\"url\":\"http://127.0.0.1:1/${PATH}\"}}").toString();
        assertTrue(notAllowed.contains("Environment variable PATH may not be used in a connection"), notAllowed);
        assertFalse(notAllowed.contains(System.getenv("PATH")), notAllowed);
        // an unset variable is named, which gives nothing away
        mvc.perform(body(post("/api/connections/test"), "{\"name\":\"x\",\"type\":\"http\",\"settings\":{\"url\":\"http://${MSGFORGE_NOT_SET_ANYWHERE}\"}}"))
                .andExpect(jsonPath("$.error").value("Environment variable MSGFORGE_NOT_SET_ANYWHERE is not set"));
    }

    @Test
    void aStoredSecretNeverComesBackAndCannotBeRedirected() throws Exception {
        // an endpoint that answers every request with the credentials it was sent
        HttpServer echo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        echo.createContext("/", exchange -> {
            byte[] reply = ("you sent " + exchange.getRequestHeaders().getFirst("Authorization")).getBytes();
            exchange.sendResponseHeaders(500, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        echo.start();
        try {
            String url = "http://127.0.0.1:" + echo.getAddress().getPort();
            JsonNode saved = sent(post("/api/connections"), "{\"name\":\"Api\",\"type\":\"http\",\"settings\":{\"url\":\"" + url + "\",\"authorization\":\"Bearer ST0RED\"},\"destinations\":[\"x\"]}");
            String id = saved.get("id").asText();
            assertEquals("[\"authorization\"]", saved.get("secretsSet").toString());
            assertFalse(saved.toString().contains("ST0RED"));

            // the broker's own error quotes the secret; what the run reports does not
            sent(post("/api/runs"), "{\"pack\":\"demo\",\"template\":\"order.json\",\"connection\":\"" + id + "\",\"destination\":\"x\",\"count\":1}");
            JsonNode run = lastRun();
            assertEquals("HTTP 500: you sent ***", run.get("error").asText());

            // renaming or changing destinations keeps the secret
            JsonNode renamed = sent(put("/api/connections/" + id), "{\"name\":\"Api 2\",\"type\":\"http\",\"settings\":{\"url\":\"" + url + "\"},\"destinations\":[\"y\"]}");
            assertEquals("[\"authorization\"]", renamed.get("secretsSet").toString());
            // pointing it somewhere else does not: the secret has to be typed again, or cleared
            String elsewhere = "{\"name\":\"Api 2\",\"type\":\"http\",\"settings\":{\"url\":\"http://127.0.0.1:1\"%s},\"destinations\":[\"y\"]}";
            mvc.perform(body(put("/api/connections/" + id), elsewhere.formatted(""))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Re-enter Authorization header: the connection's settings changed"));
            assertTrue(sent(post("/api/connections/test"), "{\"id\":\"" + id + "\"," + elsewhere.formatted("").substring(1)).get("error").asText().startsWith("Re-enter"));
            assertTrue(connections().contains(url), "the refused edit changed nothing");
            assertEquals("[\"authorization\"]", sent(put("/api/connections/" + id), elsewhere.formatted(",\"authorization\":\"Bearer N3W\"")).get("secretsSet").toString());
            assertEquals("[]", sent(put("/api/connections/" + id), elsewhere.formatted(",\"authorization\":\"\"").replace(":1\"", ":2\"")).get("secretsSet").toString());

            // another type has a setting of the same name; the stored password does not move over to it
            JsonNode mq = sent(post("/api/connections"), "{\"name\":\"MQ\",\"type\":\"ibm-mq\",\"settings\":{\"hosts\":\"h\",\"channel\":\"c\",\"password\":\"MQPA55\"}}");
            assertEquals("[\"password\"]", mq.get("secretsSet").toString());
            JsonNode asKafka = sent(put("/api/connections/" + mq.get("id").asText()), "{\"name\":\"MQ\",\"type\":\"kafka\",\"settings\":{\"bootstrap\":\"127.0.0.1:1\"}}");
            assertEquals("[]", asKafka.get("secretsSet").toString());

            String everything = connections() + mvc.perform(get("/api/runs")).andReturn().getResponse().getContentAsString();
            for (String secret : new String[] {"ST0RED", "N3W", "MQPA55", "S3CRET-GONE", "TYP0-SECRET", "0LD-KEY-SECRET", "R1GHT-SECRET"}) assertFalse(everything.contains(secret), secret);
            assertTrue(Files.readString(dir.resolve("msgforge.json")).contains("S3CRET-GONE"), "what the console cannot show is still kept on disk");
        } finally {
            echo.stop(0);
        }
    }

    @Test
    void thePluginJarAddsItsTypeAndASavedFileWithGapsStillLoads() throws Exception {
        mvc.perform(get("/api/types")).andExpect(jsonPath("$[*].id", hasItem("kafka")));
        mvc.perform(get("/api/connections")).andExpect(jsonPath("$[3].type").value("dry-run")).andExpect(jsonPath("$[3].destinations").isEmpty());
        mvc.perform(get("/api/scenarios")).andExpect(jsonPath("$[0].name").value("Bare"));
        mvc.perform(get("/api/connections")).andExpect(content().string(not(containsString("null"))));
    }

    @Test
    void badInputIsA400ThatSaysWhatIsWrong() throws Exception {
        String preview = "{\"pack\":\"demo\",\"body\":\"%s\"%s}";
        mvc.perform(body(post("/api/preview"), preview.formatted("{{now VV}}", ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("{{now}} cannot use the pattern VV")));
        mvc.perform(body(post("/api/preview"), preview.formatted("{{digits 2000000000}}", ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("{{digits}} gives at most 10000 characters"));
        mvc.perform(body(post("/api/preview"), preview.formatted("{{decimal 1 2 999999999}}", ""))).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/preview"), preview.formatted("{{a}}", ",\"vars\":{\"a\":null}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Override a has no value"));
        mvc.perform(body(post("/api/preview"), preview.formatted("\\\\{{ user.name }}", ""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.rendered").value("{{ user.name }}"));

        String run = "{\"pack\":\"demo\",\"template\":\"order.json\",\"connection\":\"dry-run\",\"destination\":\"Q\",\"count\":1%s}";
        mvc.perform(body(post("/api/runs"), run.formatted(",\"headers\":{\"a\":null}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Header a has no value"));
        mvc.perform(body(post("/api/runs"), run.formatted(",\"vars\":{\"a\":null}"))).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/runs"), run.formatted(",\"parts\":[{\"pack\":\"demo\",\"template\":\"order.json\",\"vars\":{\"a\":null}}]"))).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/runs"), run.formatted(",\"rate\":-1"))).andExpect(status().isBadRequest());

        mvc.perform(get("/api/packs/odd/templates/latin1.txt")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("latin1.txt is not UTF-8 text. Save it as UTF-8 and try again."));
        mvc.perform(body(post("/api/runs"), "{\"pack\":\"odd\",\"template\":\"latin1.txt\",\"connection\":\"dry-run\",\"destination\":\"Q\",\"count\":1}"))
                .andExpect(status().isBadRequest());

        // what Spring decides about a request keeps its status, in the same {"error": …} shape
        mvc.perform(body(post("/api/runs"), "{not json")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/runs").contentType(MediaType.TEXT_PLAIN).content("{}")).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/messages/x/one/0")).andExpect(status().isBadRequest());
        // and what nobody expected is a 500 in that shape too, not a page of HTML
        mvc.perform(put("/api/packs/blocked/templates/t.txt").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error", containsString("Unexpected error (UncheckedIOException)")));
    }

    @Test
    void unknownIdsAre404() throws Exception {
        String connection = "{\"name\":\"x\",\"type\":\"dry-run\"}";
        String scenario = "{\"name\":\"x\",\"parts\":[{\"pack\":\"demo\",\"template\":\"order.json\",\"weight\":1}],\"targets\":[{\"connection\":\"dry-run\",\"destination\":\"Q\"}],\"count\":1}";
        mvc.perform(body(put("/api/connections/nope"), connection)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/connections/nope")).andExpect(status().isNotFound());
        mvc.perform(body(put("/api/scenarios/nope"), scenario)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("no scenario nope"));
        mvc.perform(delete("/api/scenarios/nope")).andExpect(status().isNotFound());
        mvc.perform(post("/api/scenarios/nope/run")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/runs/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/messages/nope/1/0")).andExpect(status().isNotFound());
        mvc.perform(get("/api/packs/demo/templates/nope.json")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/packs/nope")).andExpect(status().isNotFound());
        mvc.perform(body(post("/api/preview"), "{\"pack\":\"nope\",\"body\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(body(post("/api/runs"), "{\"pack\":\"demo\",\"template\":\"order.json\",\"connection\":\"nope\",\"destination\":\"Q\",\"count\":1}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/scenarios")).andExpect(jsonPath("$.length()").value(1));   // the PUT to an unknown id created nothing
    }

    @Test
    void aScenarioIsCheckedWhenItIsSaved() throws Exception {
        String scenario = "{\"name\":\"S\",\"parts\":[{\"pack\":\"not-there-yet\",\"template\":\"t.json\",\"weight\":%s}],"
                + "\"targets\":[{\"connection\":\"later\",\"destination\":\"Q\"}],\"count\":%s,\"rate\":%s,\"timeoutSeconds\":%s}";
        mvc.perform(body(post("/api/scenarios"), scenario.formatted(1, 2000000000, 0, 10))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Messages must be 1 to 1000000000"));
        mvc.perform(body(post("/api/scenarios"), scenario.formatted(1, 0, 0, 10))).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/scenarios"), scenario.formatted(1, 5, -1, 10))).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/scenarios"), scenario.formatted(1001, 5, 0, 10))).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/scenarios"), scenario.formatted(1, 5, 0, 0))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Timeout must be 1 to 600 seconds"));
        // its pack and connection are not: it can be prepared before they exist
        String id = sent(post("/api/scenarios"), scenario.formatted(1, 5, 0, 10)).get("id").asText();
        mvc.perform(body(put("/api/scenarios/" + id), scenario.formatted(1, 2000000000, 0, 10))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/scenarios/" + id)).andExpect(status().isOk());
    }

    @Test
    void aPackThatCannotBeReadIsListedWithItsErrorAndTheRestStillWork() throws Exception {
        JsonNode packs = json.readTree(mvc.perform(get("/api/packs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode broken = null, demo = null;
        for (JsonNode p : packs) {
            if (p.get("id").asText().equals("broken")) broken = p;
            if (p.get("id").asText().equals("demo")) demo = p;
        }
        assertTrue(broken.get("error").asText().startsWith("broken/pack.yml is not valid YAML"), broken.toString());
        assertTrue(broken.get("templates").isEmpty());
        assertTrue(demo.get("error").isNull() && demo.get("templates").size() == 3, demo.toString());
        mvc.perform(body(post("/api/preview"), "{\"pack\":\"broken\",\"body\":\"x\"}")).andExpect(status().isBadRequest());

        // creating a pack is one request: refused whole, so the name is still free afterwards
        mvc.perform(body(post("/api/packs"), "{\"id\":\"mine\",\"name\":\"Mine\",\"fields\":{\"bad name\":\"1\"}}")).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/packs"), "{\"id\":\"mine\",\"name\":\"Mine\",\"fields\":{\"region\":\"EU\"}}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.region").value("EU"));
        mvc.perform(delete("/api/packs/mine")).andExpect(status().isOk());
    }

    @Test
    void theMessageListLeavesBodiesOutAndOneMessageHasItsBody() throws Exception {
        String id = sent(post("/api/runs"), "{\"pack\":\"demo\",\"template\":\"order.json\",\"body\":\"B0DY-{{seq}}\",\"connection\":\"dry-run\",\"destination\":\"Q\",\"count\":2,"
                + "\"headers\":{\"trace\":\"T-{{seq}}\"}}").get("id").asText();
        lastRun();
        String list = mvc.perform(get("/api/messages")).andExpect(status().isOk()).andExpect(jsonPath("$[0].runId").value(id))
                .andExpect(jsonPath("$[0].n").value(2)).andExpect(jsonPath("$[0].destination").value("Q")).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("B0DY") || list.contains("T-"), list);
        mvc.perform(get("/api/messages/" + id + "/1/0")).andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("B0DY-1")).andExpect(jsonPath("$.headers.trace").value("T-2"));
    }

    @Test
    void onlyTheSettingsATypeDeclaresAreShown() throws Exception {
        // the file has a misspelt key and one from an older version of the type; both hold passwords
        JsonNode typo = null;
        for (JsonNode c : json.readTree(connections())) if (c.get("id").asText().equals("typo")) typo = c;
        assertEquals("{\"hosts\":\"mq1\",\"channel\":\"C\"}", typo.get("settings").toString());
        assertEquals("[\"password\"]", typo.get("secretsSet").toString());
    }

    @Test
    void aTemplateCanBeCreatedWithoutReplacingOne() throws Exception {
        sent(post("/api/packs"), "{\"id\":\"fresh\",\"name\":\"Fresh\"}");
        MockHttpServletRequestBuilder create = put("/api/packs/fresh/templates/Order.json").contentType(MediaType.TEXT_PLAIN).header("If-None-Match", "*");
        mvc.perform(create.content("first")).andExpect(status().isOk());
        mvc.perform(put("/api/packs/fresh/templates/Order.json").contentType(MediaType.TEXT_PLAIN).header("If-None-Match", "*").content("second"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("fresh already has a template named Order.json"));
        mvc.perform(get("/api/packs/fresh/templates/Order.json")).andExpect(content().string("first"));

        // The same name in another letter case: one file on a file system that ignores case, two on one that does not.
        // Either way nothing is replaced unasked.
        boolean sameFile = Files.exists(dir.resolve("packs/fresh/templates/order.json"));
        mvc.perform(put("/api/packs/fresh/templates/order.json").contentType(MediaType.TEXT_PLAIN).header("If-None-Match", "*").content("third"))
                .andExpect(sameFile ? status().isConflict() : status().isOk());
        mvc.perform(get("/api/packs/fresh/templates/Order.json")).andExpect(content().string("first"));

        // without the header a PUT replaces, as saving an open template must
        mvc.perform(put("/api/packs/fresh/templates/Order.json").contentType(MediaType.TEXT_PLAIN).content("replaced")).andExpect(status().isOk());
        mvc.perform(get("/api/packs/fresh/templates/Order.json")).andExpect(content().string("replaced"));

        mvc.perform(delete("/api/packs/fresh")).andExpect(status().isOk());
    }

    @Test
    void aPackThatMayNotBeWrittenToIsA400ThatSaysSo() throws Exception {
        sent(post("/api/packs"), "{\"id\":\"locked\",\"name\":\"Locked\"}");
        java.io.File templates = dir.resolve("packs/locked/templates").toFile();
        assertTrue(templates.setWritable(false));
        try {
            // root may write whatever the permissions say (a build inside a container runs as root), so there is nothing to see
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(templates.toPath()), "this user is not held back by file permissions");
            mvc.perform(put("/api/packs/locked/templates/new.json").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("This pack is read-only")));
        } finally {
            templates.setWritable(true);
            mvc.perform(delete("/api/packs/locked")).andExpect(status().isOk());
        }
    }

    @Test
    void aSampleIsTurnedIntoATemplateWithoutSavingAnything() throws Exception {
        String sample = "<Shipment>\n  <Id>3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d</Id>\n  <Sent>2024-03-09T14:05:33Z</Sent>\n  <Carrier>Blue Harbor</Carrier>\n  <Parcels>3</Parcels>\n</Shipment>\n";
        // against the demo pack, whose carrier field offers Blue Harbor, and asked to use its fields
        mvc.perform(post("/api/recognise").param("pack", "demo").param("fields", "true").contentType(MediaType.TEXT_PLAIN).content(sample)).andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("<Shipment>\n  <Id>{{uuid}}</Id>\n  <Sent>{{now}}</Sent>\n  <Carrier>{{carrier}}</Carrier>\n  <Parcels>3</Parcels>\n</Shipment>\n"))
                .andExpect(jsonPath("$.replacements.length()").value(3))
                .andExpect(jsonPath("$.replacements[0].line").value(2))
                .andExpect(jsonPath("$.replacements[0].value").value("3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d"))
                .andExpect(jsonPath("$.replacements[0].placeholder").value("{{uuid}}"))
                .andExpect(jsonPath("$.replacements[0].field").doesNotExist())
                .andExpect(jsonPath("$.replacements[2].placeholder").value("{{carrier}}"))
                .andExpect(jsonPath("$.replacements[2].field").value("carrier"));
        // not asked to (an import), or against no pack, the carrier is just text
        for (String pack : new String[] {"demo", ""}) {
            mvc.perform(post("/api/recognise").param("pack", pack).contentType(MediaType.TEXT_PLAIN).content(sample)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.replacements.length()").value(2)).andExpect(jsonPath("$.template", containsString("<Carrier>Blue Harbor</Carrier>")));
        }
        // a long string in a JSON sample used to be a 500 (a stack overflow)
        mvc.perform(post("/api/recognise").param("pack", "demo").contentType(MediaType.TEXT_PLAIN)
                        .content("{\"id\":\"3f2b8c1e-9a4d-4e7b-8c21-5d6f7a8b9c0d\",\"attachment\":\"" + "QUJD".repeat(5_000) + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.replacements.length()").value(1)).andExpect(jsonPath("$.replacements[0].placeholder").value("{{uuid}}"));
        mvc.perform(post("/api/recognise").param("pack", "demo").contentType(MediaType.TEXT_PLAIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.template").value("")).andExpect(jsonPath("$.replacements").isEmpty());
        mvc.perform(post("/api/recognise").param("pack", "nope").contentType(MediaType.TEXT_PLAIN).content(sample)).andExpect(status().isNotFound());
        mvc.perform(post("/api/recognise").param("pack", "demo").contentType(MediaType.TEXT_PLAIN).content("x".repeat(1_000_001))).andExpect(status().isBadRequest());
        // nothing was written: the demo pack still has its three templates
        mvc.perform(get("/api/packs")).andExpect(jsonPath("$[?(@.id == 'demo')].templates.length()").value(hasItem(3)));
    }

    // ---- load runs, monitors, reports, metrics

    @Test
    void aLoadRunHasItsStatisticsItsDetailAndItsReports() throws Exception {
        // dry run hands a message to whoever listens on its destination, so watching the destination sent to sees every message
        String id = sent(post("/api/runs"), """
                {"label":"Nightly <soak>","pack":"demo","template":"order.json","connection":"dry-run","destination":"LOAD.IN","count":60,"rate":0,"timeoutSeconds":1,
                 "load":{"senders":2},"invalid":{"percent":50,"kinds":["empty","field-removed"]},"seed":7,
                 "watches":[{"connection":"dry-run","destination":"LOAD.IN","buckets":[{"pointer":"/currency"},{"name":"has id","regex":"orderId"}]}]}""").get("id").asText();
        JsonNode run = lastRun();
        assertEquals(id, run.get("id").asText());
        assertEquals("DONE", run.get("status").asText());
        assertEquals(60, run.get("sent").asInt());
        assertEquals(0, run.get("failed").asInt());
        assertEquals(7, run.get("seed").asInt());
        assertEquals(2, run.get("senders").asInt());
        assertTrue(run.get("invalid").asInt() > 10 && run.get("invalid").asInt() < 50, run.toString());
        assertEquals(run.get("invalid").asInt(), run.get("faults").get("empty").asInt() + run.get("faults").get("field-removed").asInt());
        assertTrue(run.get("latency").get("p99").isNumber() && run.get("actualRate").asDouble() > 0 && run.get("targetRate").isNull(), run.toString());
        assertEquals(60, run.get("watched").get(0).get("count").asInt());
        assertTrue(run.get("verdict").isNull(), "nothing to verify, nothing failed");

        JsonNode detail = json.readTree(mvc.perform(get("/api/runs/" + id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(id, detail.get("run").get("id").asText());
        assertEquals(60, detail.get("series").get(0).get("sent").asInt());
        JsonNode watch = detail.get("watches").get(0);
        assertEquals("/currency", watch.get("tables").get(0).get("name").asText());
        assertEquals("has id", watch.get("tables").get(1).get("name").asText());
        assertEquals(60, watch.get("tables").get(0).get("values").findValues("count").stream().mapToInt(JsonNode::asInt).sum());
        assertTrue(watch.get("recent").size() == 50 && watch.get("samples").size() == 3, "the newest 50 arrivals without their bodies, and three bodies as a sample");
        // the Sent list says which fault a message was given
        String messages = mvc.perform(get("/api/messages")).andReturn().getResponse().getContentAsString();
        assertTrue(messages.contains("\"fault\":\"empty\"") && messages.contains("\"fault\":null"), messages);

        // the run as a file, in each format; always a download, never a page of the console
        String name = "msgforge-\\d{8}-\\d{9}-" + id + "-Nightly-soak\\.";
        String html = mvc.perform(get("/api/runs/" + id + "/report")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/html;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.matchesPattern("attachment; filename=\"" + name + "html\"")))
                .andExpect(header().stringValues("Content-Security-Policy", hasItem(containsString("sandbox")))).andReturn().getResponse().getContentAsString();
        assertTrue(html.contains("Nightly &lt;soak&gt;") && html.contains("<svg class=\"chart\"") && !html.contains("<soak>"), html);
        mvc.perform(get("/api/runs/" + id + "/report?format=json")).andExpect(status().isOk()).andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.run.sent").value(60)).andExpect(jsonPath("$.watches[0].samples.length()").value(0));
        mvc.perform(get("/api/runs/" + id + "/report?format=csv")).andExpect(status().isOk()).andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.matchesPattern("attachment; filename=\"" + name + "csv\"")))
                .andExpect(content().string(containsString("\r\nsent,60\r\n")));
        mvc.perform(get("/api/runs/" + id + "/report?format=series")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.matchesPattern("attachment; filename=\"" + name + "series\\.csv\"")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("t_seconds,seconds,target_rate,sent,failed,invalid,sent_rate\r\n0,")));
        mvc.perform(get("/api/runs/" + id + "/report?format=pdf")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("A report is html, json, csv or series"));
        mvc.perform(get("/api/runs/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/runs/nope/report")).andExpect(status().isNotFound());

        // with a reports folder set, the run was saved there when it ended
        JsonNode saved = null;
        for (int i = 0; i < 100 && saved == null; i++) {
            for (JsonNode r : json.readTree(mvc.perform(get("/api/reports")).andExpect(jsonPath("$.enabled").value(true)).andReturn().getResponse().getContentAsString()).get("reports")) {
                if (r.get("id").asText().equals(id)) saved = r;
            }
            Thread.sleep(20);
        }
        assertEquals("Nightly-soak", saved.get("label").asText());
        assertEquals(4, saved.get("files").size());
        mvc.perform(get("/api/reports/" + saved.get("files").get(0).asText())).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment"))).andExpect(content().string(containsString("Nightly &lt;soak&gt;")));
        mvc.perform(get("/api/reports/" + saved.get("files").get(2).asText())).andExpect(status().isOk()).andExpect(content().string(containsString("sent,60")));
        mvc.perform(get("/api/reports/msgforge.json")).andExpect(status().isNotFound());
        mvc.perform(get("/api/reports/msgforge-20260101-000000000-0000000a-x.html")).andExpect(status().isNotFound());
    }

    @Test
    void aRunVerifiesInvalidMessagesAndWhatCannotRunIsA400() throws Exception {
        // the loop hands every message back, the invalid ones too: accepted although invalid
        sent(post("/api/runs"), """
                {"pack":"demo","template":"order.json","connection":"dry-run","destination":"V.IN","count":40,"expect":{"connection":"dry-run","destination":"V.IN"},
                 "timeoutSeconds":1,"headers":{"ref":"{{ref}}"},"body":"{\\"id\\":\\"{{ref=uuid}}\\"}","match":"{{ref}}","invalid":{"percent":50,"kinds":["empty"]},"seed":1}""");
        JsonNode run = lastRun();
        assertEquals("FAIL", run.get("verdict").asText());
        assertEquals(2, run.get("checks").size());
        JsonNode invalid = run.get("checks").get(1);
        assertTrue(invalid.get("invalid").asBoolean());
        assertEquals("nowhere", invalid.get("expected").asText());
        assertEquals(invalid.get("sent").asInt(), invalid.get("accepted").asInt());
        assertEquals(run.get("checks").get(0).get("sent").asInt(), run.get("checks").get(0).get("arrived").asInt());

        String run1 = "{\"pack\":\"demo\",\"template\":\"order.json\",\"connection\":\"dry-run\",\"destination\":\"Q\",\"count\":%s%s}";
        String[][] refused = {
                {"0", "", "needs a duration"},
                {"0", ",\"rate\":5,\"expect\":{\"connection\":\"dry-run\",\"destination\":\"Q\"},\"load\":{\"durationSeconds\":5}", "at most 20000"},
                {"5", ",\"load\":{\"senders\":99}", "Senders must be 1 to 64"},
                {"5", ",\"load\":{\"rampUpSeconds\":5}", "A ramp-up needs a rate"},
                {"5", ",\"invalid\":{\"percent\":120}", "Invalid messages must be 0 to 100 percent"},
                {"5", ",\"invalid\":{\"percent\":5,\"kinds\":[\"scrambled\"]}", "Unknown kind of invalid message: scrambled"},
                {"5", ",\"invalid\":{\"percent\":5,\"expect\":{\"connection\":\"dry-run\"}}", "Where invalid messages arrive needs a destination"},
                {"5", ",\"watches\":[{\"connection\":\"dry-run\",\"destination\":\"W\",\"buckets\":[{\"regex\":\"(\"}]}]", "is not a regular expression"},
                {"5", ",\"watches\":[{\"connection\":\"dry-run\",\"destination\":\"W\",\"buckets\":[{\"pointer\":\"/a\",\"header\":\"b\"}]}]", "exactly one of pointer, regex and header"},
                {"5", ",\"watches\":[{\"connection\":\"gone\",\"destination\":\"W\"}]", "which cannot receive"},
                {"5", ",\"watches\":[null]", "A watched destination is empty"},
                {"5", ",\"raw\":true,\"expect\":{\"connection\":\"dry-run\",\"destination\":\"Q\"}", "A raw message cannot be verified"},
                {"5", ",\"destination\":\"${PATH}\"", "Environment variable PATH may not be used"},
        };
        for (String[] r : refused) {
            mvc.perform(body(post("/api/runs"), run1.formatted(r[0], r[1]))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString(r[2])));
        }
        // a watch on a connection that is not there is a 404, like a target on one
        mvc.perform(body(post("/api/runs"), run1.formatted("5", ",\"watches\":[{\"connection\":\"nope\",\"destination\":\"W\"}]"))).andExpect(status().isNotFound());

        // a body as typed, with no pack and no template
        String raw = sent(post("/api/runs"), "{\"raw\":true,\"body\":\"{{uuid}} as typed\",\"headers\":{\"h\":\"{{seq}}\"},\"connection\":\"dry-run\",\"destination\":\"Q\",\"count\":1}").get("id").asText();
        lastRun();
        mvc.perform(get("/api/messages/" + raw + "/1/0")).andExpect(jsonPath("$.body").value("{{uuid}} as typed")).andExpect(jsonPath("$.headers.h").value("{{seq}}"))
                .andExpect(jsonPath("$.template").value("raw"));
    }

    @Test
    void aScenarioKeepsItsLoadPlanAndADestinationMayNameTheEnvironment() throws Exception {
        String scenario = """
                {"name":"Soak","parts":[{"pack":"demo","template":"order.json","weight":1}],"targets":[{"connection":"dry-run","destination":"S.${HOME}"}],
                 "count":0,"rate":400,"timeoutSeconds":1,"load":{"rampUpSeconds":1,"durationSeconds":1,"senders":2},"invalid":{"percent":10,"kinds":["truncated"]},"seed":99,
                 "watches":[{"connection":"dry-run","destination":"S.${HOME}","buckets":[{"header":"origin"}]}]}""";
        JsonNode saved = sent(post("/api/scenarios"), scenario);
        String id = saved.get("id").asText();
        assertEquals(1, saved.get("load").get("rampUpSeconds").asInt());
        assertEquals(99, saved.get("seed").asInt());
        assertEquals("truncated", saved.get("invalid").get("kinds").get(0).asText());
        assertEquals("origin", saved.get("watches").get(0).get("buckets").get(0).get("header").asText());
        String file = Files.readString(dir.resolve("msgforge.json"));
        assertTrue(file.contains("\"durationSeconds\" : 1") && file.contains("S.${HOME}"), "saved as written: " + file);

        mvc.perform(post("/api/scenarios/" + id + "/run")).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0)).andExpect(jsonPath("$.durationSeconds").value(1));
        JsonNode run = lastRun();
        assertEquals("DONE", run.get("status").asText());
        assertEquals("S.${HOME}", run.get("targets").get(0).get("destination").asText(), "shown as written, sent to the name it stands for");
        assertTrue(run.get("sent").asInt() > 20 && run.get("sent").asInt() <= 201, "400 a second reached over the one second it ran: " + run.get("sent"));
        assertEquals(run.get("sent").asInt(), run.get("watched").get(0).get("count").asInt(), "the watch resolved to the same destination");
        assertEquals(99, run.get("seed").asInt());

        // what could never run is refused when it is saved
        mvc.perform(body(put("/api/scenarios/" + id), scenario.replace("\"percent\":10", "\"percent\":-1"))).andExpect(status().isBadRequest());
        mvc.perform(body(put("/api/scenarios/" + id), scenario.replace("\"header\":\"origin\"", "\"regex\":\"[\""))).andExpect(status().isBadRequest());
        mvc.perform(body(put("/api/scenarios/" + id), scenario.replace("\"durationSeconds\":1,", ""))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("needs a duration")));
        mvc.perform(delete("/api/scenarios/" + id)).andExpect(status().isOk());
    }

    private JsonNode monitor(String id) throws Exception {
        for (JsonNode m : json.readTree(mvc.perform(get("/api/monitors")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())) {
            if (m.get("id").asText().equals(id)) return m;
        }
        return null;
    }

    @Test
    void savedMonitorsStartWithTheApplicationAndShareADestinationWithARun() throws Exception {
        // from the file: one that listens, and one whose connection cannot receive, which is listed as down and did not stop the start
        JsonNode always = null;
        for (int i = 0; i < 250 && (always == null || !always.get("state").asText().equals("up")); i++) {
            Thread.sleep(20);
            always = monitor("always");
        }
        assertEquals("up", always.get("state").asText());
        assertEquals("Rejected", always.get("name").asText());
        assertTrue(always.get("saved").asBoolean());
        JsonNode orphan = monitor("orphan");
        for (int i = 0; i < 250 && !orphan.get("state").asText().equals("down"); i++) {
            Thread.sleep(20);
            orphan = monitor("orphan");
        }
        assertEquals("Cannot receive: Old broker is a rabbit (not installed) connection, which cannot receive.", orphan.get("error").asText());
        assertFalse(orphan.get("retryAt").isNull());

        // a run that expects its invalid messages on the monitored destination is not refused: both see every reject
        long before = always.get("watch").get("count").asLong();
        sent(post("/api/connections"), "{\"name\":\"Routed\",\"type\":\"dry-run\",\"settings\":{\"route\":\"SHARED.IN > SHARED.OUT, invalid > ALWAYS.REJECTED\"}}");
        String routed = null;
        for (JsonNode c : json.readTree(connections())) if (c.get("name").asText().equals("Routed")) routed = c.get("id").asText();
        sent(post("/api/runs"), """
                {"pack":"demo","template":"order.json","connection":"%s","destination":"SHARED.IN","count":60,"seed":8,"timeoutSeconds":5,
                 "expect":{"connection":"dry-run","destination":"SHARED.OUT"},
                 "invalid":{"percent":40,"kinds":["empty","truncated"],"expect":{"connection":"dry-run","destination":"ALWAYS.REJECTED"}}}""".formatted(routed));
        JsonNode run = lastRun();
        assertEquals("PASS", run.get("verdict").asText(), run.toString());
        int rejected = run.get("checks").get(1).get("arrived").asInt();
        assertTrue(rejected > 10, run.toString());
        always = monitor("always");
        assertEquals(before + rejected, always.get("watch").get("count").asLong(), "the monitor counted the rejects the run was matching");
        assertEquals(rejected, always.get("watch").get("tables").get(0).get("values").findValues("count").stream().mapToInt(JsonNode::asInt).sum());
        assertEquals("up", always.get("state").asText(), "and is still listening after the run has ended");
        mvc.perform(delete("/api/connections/" + routed)).andExpect(status().isOk());
    }

    @Test
    void aMonitorIsStartedSavedResetAndStoppedOverTheApi() throws Exception {
        JsonNode monitor = sent(post("/api/monitors"), "{\"name\":\"Late ones\",\"connection\":\"dry-run\",\"destination\":\"MON.Q\",\"buckets\":[{\"header\":\"kind\"},{\"pointer\":\"/site\"}]}");
        String id = monitor.get("id").asText();
        assertEquals("MON.Q", monitor.get("watch").get("destination").asText());
        assertEquals("up", monitor.get("state").asText());
        assertFalse(monitor.get("saved").asBoolean());
        sent(post("/api/runs"), "{\"pack\":\"demo\",\"template\":\"order.json\",\"body\":\"{\\\"site\\\":\\\"north-1\\\"}\",\"connection\":\"dry-run\",\"destination\":\"MON.Q\",\"count\":3,"
                + "\"headers\":{\"kind\":\"late\"}}");
        lastRun();
        JsonNode counted = monitor(id);
        assertEquals(3, counted.get("watch").get("count").asInt());
        assertEquals("late", counted.get("watch").get("tables").get(0).get("values").get(0).get("value").asText());
        assertEquals("north-1", counted.get("watch").get("tables").get(1).get("values").get(0).get("value").asText());
        assertEquals(18, counted.get("watch").get("recent").get(0).get("bytes").asInt());
        assertEquals("{\"site\":\"north-1\"}", counted.get("watch").get("samples").get(0).asText());

        // kept in the file, taken out again, and removed with the monitor when it is stopped while saved
        mvc.perform(put("/api/monitors/" + id + "/saved")).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));
        JsonNode file = json.readTree(dir.resolve("msgforge.json").toFile()).get("monitors");
        assertEquals("Late ones", file.get(file.size() - 1).get("name").asText());
        assertEquals("kind", file.get(file.size() - 1).get("buckets").get(0).get("header").asText());
        mvc.perform(delete("/api/monitors/" + id + "/saved")).andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(false));
        assertFalse(Files.readString(dir.resolve("msgforge.json")).contains("Late ones"));
        mvc.perform(put("/api/monitors/" + id + "/saved")).andExpect(status().isOk());
        mvc.perform(post("/api/monitors/" + id + "/reset")).andExpect(status().isOk()).andExpect(jsonPath("$.watch.count").value(0));
        // stopping a saved monitor only stops it: it stays listed and in the file, and can be started again
        mvc.perform(delete("/api/monitors/" + id)).andExpect(status().isOk());
        assertEquals("stopped", monitor(id).get("state").asText());
        assertTrue(Files.readString(dir.resolve("msgforge.json")).contains("Late ones"));
        mvc.perform(post("/api/monitors/" + id + "/start")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("up"));
        mvc.perform(delete("/api/monitors/" + id)).andExpect(status().isOk());
        // taking it out of the file is the other step, and removes a stopped one from the list
        mvc.perform(delete("/api/monitors/" + id + "/saved")).andExpect(status().isOk());
        assertFalse(Files.readString(dir.resolve("msgforge.json")).contains("Late ones"));
        assertTrue(Files.readString(dir.resolve("msgforge.json")).contains("ALWAYS.REJECTED"), "the others stay");
        mvc.perform(delete("/api/monitors/" + id)).andExpect(status().isNotFound());
        mvc.perform(post("/api/monitors/" + id + "/start")).andExpect(status().isNotFound());
        mvc.perform(post("/api/monitors/" + id + "/reset")).andExpect(status().isNotFound());
        mvc.perform(put("/api/monitors/" + id + "/saved")).andExpect(status().isNotFound());
        assertEquals(null, monitor(id));
        // saved in the same request that starts it
        String kept = sent(post("/api/monitors"), "{\"connection\":\"dry-run\",\"destination\":\"KEPT.Q\",\"save\":true}").get("id").asText();
        assertTrue(Files.readString(dir.resolve("msgforge.json")).contains("KEPT.Q"));
        mvc.perform(delete("/api/monitors/" + kept)).andExpect(status().isOk());
        mvc.perform(delete("/api/monitors/" + kept + "/saved")).andExpect(status().isOk());
        assertFalse(Files.readString(dir.resolve("msgforge.json")).contains("KEPT.Q"));

        mvc.perform(body(post("/api/monitors"), "{\"connection\":\"gone\",\"destination\":\"Q\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Old broker is a rabbit (not installed) connection, which cannot receive."));
        mvc.perform(body(post("/api/monitors"), "{\"connection\":\"dry-run\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Destination is required"));
        mvc.perform(body(post("/api/monitors"), "{\"connection\":\"dry-run\",\"destination\":\"ALWAYS.REJECTED\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A monitor is already on Dry run / ALWAYS.REJECTED"));
        mvc.perform(body(post("/api/monitors"), "{\"connection\":\"nope\",\"destination\":\"Q\"}")).andExpect(status().isNotFound());
        mvc.perform(body(post("/api/monitors"), "{\"connection\":\"dry-run\",\"destination\":\"Q\",\"buckets\":[{}]}")).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/monitors"), "{\"connection\":\"dry-run\",\"destination\":\"Q\"}").header("Origin", "http://evil.example")).andExpect(status().isForbidden());
        mvc.perform(put("/api/monitors/always/saved").header("Origin", "http://evil.example")).andExpect(status().isForbidden());
    }

    @Test
    void metricsAreNumbersAndNamesAndFollowTheSameHostRules() throws Exception {
        sent(post("/api/runs"), "{\"pack\":\"demo\",\"template\":\"order.json\",\"connection\":\"dry-run\",\"destination\":\"M.${HOME}\",\"count\":4,\"timeoutSeconds\":1,"
                + "\"watches\":[{\"connection\":\"dry-run\",\"destination\":\"M.${HOME}\"}]}");
        lastRun();
        String metrics = mvc.perform(get("/metrics")).andExpect(status().isOk()).andExpect(header().string("Content-Type", "text/plain;version=0.0.4;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn().getResponse().getContentAsString();
        assertTrue(metrics.contains("# TYPE msgforge_messages_sent_total counter\nmsgforge_messages_sent_total "), metrics);
        assertTrue(metrics.contains("msgforge_runs_total{outcome=\"done\"} ") && metrics.contains("msgforge_runs_active ") && metrics.contains("msgforge_send_rate "), metrics);
        assertTrue(metrics.contains("msgforge_arrivals_total{connection=\"Dry run\",destination=\"M.${HOME}\",source=\"run\"} 4\n"), "a destination is labelled as it was written: " + metrics);
        // nothing from the saved file's settings, nothing read from the environment, no message content
        for (String never : new String[] {"S3CRET-GONE", "TYP0-SECRET", "0LD-KEY-SECRET", "R1GHT-SECRET", "mq1", System.getenv("HOME"), "orderId"}) {
            assertFalse(metrics.contains(never), never);
        }
        mvc.perform(get("/metrics").header("Host", "evil.example")).andExpect(status().isForbidden());
        mvc.perform(get("/metrics").header("Host", "console.example")).andExpect(status().isOk());
    }

    // ---- what the console asks about itself and its connections

    @Test
    void theConsoleLearnsItsTitleItsThemeAndWhetherAConnectionAnswers() throws Exception {
        mvc.perform(get("/api/console")).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Orders bench")).andExpect(jsonPath("$.theme").value(true));
        mvc.perform(get("/theme.css")).andExpect(status().isOk()).andExpect(header().string("Content-Type", "text/css;charset=UTF-8"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(content().string(":root { --accent: #00aeef; }"));
        mvc.perform(get("/theme.css").header("Host", "evil.example")).andExpect(status().isForbidden());
        // the page links it after its own style sheet
        String page = mvc.perform(get("/index.html")).andReturn().getResponse().getContentAsString();
        assertTrue(page.indexOf("href=\"app.css\"") > 0 && page.indexOf("href=\"theme.css\"") > page.indexOf("href=\"app.css\""), "theme.css after app.css");

        // a connection's last check: asked for, remembered, listed by id
        mvc.perform(post("/api/connections/dry-run/check")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(post("/api/connections/gone/check")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error", containsString("which is not installed")));
        mvc.perform(get("/api/connections/status")).andExpect(status().isOk()).andExpect(jsonPath("$.dry-run.ok").value(true)).andExpect(jsonPath("$.gone.ok").value(false))
                .andExpect(jsonPath("$.typo").doesNotExist());
        mvc.perform(post("/api/connections/nope/check")).andExpect(status().isNotFound());
        mvc.perform(post("/api/connections/dry-run/check").header("Origin", "http://evil.example")).andExpect(status().isForbidden());
        String status = mvc.perform(get("/api/connections/status")).andReturn().getResponse().getContentAsString();
        assertFalse(status.contains("S3CRET-GONE") || status.contains("R1GHT-SECRET"), status);

        // names and queues with variables are shown by what they stand for; the request still uses what is written
        JsonNode made = sent(post("/api/connections"), "{\"name\":\"Env ${HOME}\",\"type\":\"dry-run\",\"destinations\":[\"IN.${HOME}\",\"PLAIN\"]}");
        assertEquals("Env " + System.getenv("HOME"), made.get("title").asText());
        assertEquals("Env ${HOME}", made.get("name").asText());
        assertEquals("IN." + System.getenv("HOME"), made.get("queues").get("IN.${HOME}").asText());
        assertEquals(1, made.get("queues").size());
        // and only a variable listed to be shown: one a connection may use is not thereby one the console gives out
        JsonNode kept = sent(post("/api/connections"), "{\"name\":\"${MSGFORGE_CONFIG} ${JAVA_HOME}\",\"type\":\"dry-run\",\"destinations\":[\"${MSGFORGE_CONFIG}\"]}");
        assertEquals("${MSGFORGE_CONFIG} ${JAVA_HOME}", kept.get("title").asText());
        assertEquals(0, kept.get("queues").size());
        mvc.perform(delete("/api/connections/" + kept.get("id").asText())).andExpect(status().isOk());
        mvc.perform(delete("/api/connections/" + made.get("id").asText())).andExpect(status().isOk());

        // a run with an override that is an expression: each message gets a value from the narrower range
        String id = sent(post("/api/runs"), "{\"pack\":\"demo\",\"template\":\"order.json\",\"connection\":\"dry-run\",\"destination\":\"Q\",\"count\":3,"
                + "\"vars\":{\"currency\":\"{{pick JPY}}\",\"customer\":\"Plain Name\"}}").get("id").asText();
        lastRun();
        mvc.perform(get("/api/messages/" + id + "/1/0")).andExpect(jsonPath("$.body", containsString("\"currency\": \"JPY\""))).andExpect(jsonPath("$.body", containsString("\"customer\": \"Plain Name\"")));
    }
}
