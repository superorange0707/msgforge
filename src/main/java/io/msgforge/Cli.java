package io.msgforge;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * msgforge without the console: run a saved scenario and exit with its verdict, for a pipeline.
 *
 *   run <scenario> [--report file] [--url http://host:port] [--count n] [--rate r]
 *   list [--url http://host:port]
 *
 * Without --url it reads the same settings as the server (msgforge.json, packs, plugins, MSGFORGE_* and --msgforge.*)
 * and runs in this process without opening a port. With --url it asks a running msgforge over its HTTP API.
 * Either way a run is followed through the same view, so the output and the exit code do not depend on where it ran.
 */
final class Cli {

    static final int PASSED = 0, FAILED = 1, ERROR = 2;

    private final PrintStream out;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Cli(PrintStream out) {
        this.out = out;
    }

    static boolean handles(String[] args) {
        return args.length > 0 && (args[0].equals("run") || args[0].equals("list"));
    }

    /** @return the exit code */
    static int main(String[] args, PrintStream out) {
        return new Cli(out).execute(args);
    }

    /** What a run's caller needs from it, the same whether it ran here or on a server: the API's run view as JSON. */
    private interface Backend extends AutoCloseable {
        List<JsonNode> scenarios() throws Exception;

        JsonNode start(JsonNode scenario, Integer count, Double rate) throws Exception;

        JsonNode run(String id) throws Exception;

        void cancel(String id);

        @Override
        void close();
    }

    private int execute(String[] args) {
        String command = args[0], scenario = null, report = null, url = null;
        Integer count = null;
        Double rate = null;
        List<String> spring = new ArrayList<>();   // everything else is a setting, as for the server
        try {
            for (int i = 1; i < args.length; i++) {
                String a = args[i], name = a.contains("=") ? a.substring(0, a.indexOf('=')) : a;
                boolean ours = List.of("--report", "--url", "--count", "--rate").contains(name);
                String value = !ours ? null : a.contains("=") ? a.substring(a.indexOf('=') + 1) : ++i < args.length ? args[i] : null;
                if (ours && value == null) throw new IllegalArgumentException(name + " needs a value");
                switch (name) {
                    case "--report" -> report = value;
                    case "--url" -> url = value.replaceAll("/+$", "");
                    case "--count" -> count = Integer.valueOf(value);
                    case "--rate" -> rate = Double.valueOf(value);
                    default -> {
                        if (a.startsWith("--")) spring.add(a);
                        else if (scenario == null && command.equals("run")) scenario = a;
                        else throw new IllegalArgumentException("unexpected argument " + a);
                    }
                }
            }
            if (command.equals("run") && scenario == null) throw new IllegalArgumentException("run needs the name or id of a scenario");
        } catch (IllegalArgumentException e) {   // NumberFormatException included
            out.println("msgforge: " + e.getMessage() + ". bin/msgforge --help shows the usage.");
            return ERROR;
        }
        Path reportFile = report == null ? null : workdir().resolve(report);

        Backend backend;
        try {
            backend = url == null ? local(spring) : remote(url);
        } catch (Exception e) {
            return error(scenario, reportFile, "msgforge could not start: " + Runs.describe(e));
        }
        try (backend) {
            List<JsonNode> scenarios = backend.scenarios();
            if (command.equals("list")) {
                if (scenarios.isEmpty()) out.println("No scenarios saved.");
                for (JsonNode s : scenarios) out.println(s.path("id").asText() + "  " + s.path("name").asText());
                return PASSED;
            }
            return run(backend, find(scenarios, scenario), count, rate, reportFile);
        } catch (Exception e) {
            return error(scenario, reportFile, Runs.describe(e));
        }
    }

    /** The bin scripts start in the install folder; a relative --report means the folder the user was in. */
    private static Path workdir() {
        return Path.of(System.getProperty("msgforge.workdir", "."));
    }

    /** An id first, then an exact name. Two scenarios of the same name are not guessed between. */
    private static JsonNode find(List<JsonNode> scenarios, String wanted) {
        for (JsonNode s : scenarios) if (s.path("id").asText().equals(wanted)) return s;
        List<JsonNode> named = scenarios.stream().filter(s -> s.path("name").asText().equals(wanted)).toList();
        if (named.size() == 1) return named.get(0);
        if (named.isEmpty()) throw new IllegalArgumentException("No scenario named " + wanted + ". bin/msgforge list shows the saved ones.");
        throw new IllegalArgumentException(named.size() + " scenarios are named " + wanted + ". Use the id of one: "
                + String.join(", ", named.stream().map(s -> s.path("id").asText()).toList()));
    }

    private int run(Backend backend, JsonNode scenario, Integer count, Double rate, Path report) throws Exception {
        String name = scenario.path("name").asText();
        JsonNode run = backend.start(scenario, count, rate);
        String id = run.path("id").asText();
        out.println("Scenario " + name + ": run " + id + ", " + run.path("count").asInt() + " messages");
        // A pipeline that is cancelled stops the JVM; the run on a server would otherwise carry on.
        Thread cancel = new Thread(() -> backend.cancel(id));
        Runtime.getRuntime().addShutdownHook(cancel);
        String status = "";
        int sent = -1;
        long lastPrint = 0;
        while (true) {
            run = backend.run(id);
            boolean ended = !run.path("finishedAt").isNull();
            if (!run.path("status").asText().equals(status) || (System.nanoTime() - lastPrint > 2_000_000_000L && run.path("sent").asInt() != sent) || ended) {
                status = run.path("status").asText();
                sent = run.path("sent").asInt();
                lastPrint = System.nanoTime();
                out.println("  " + status.toLowerCase() + ", sent " + sent + " of " + run.path("count").asInt());
            }
            if (ended) break;
            Thread.sleep(200);
        }
        Runtime.getRuntime().removeShutdownHook(cancel);

        for (JsonNode c : run.path("checks")) out.println("  " + (passed(c) ? "PASS " : "FAIL ") + checkName(c) + ": " + counts(c));
        String verdict = run.path("verdict").asText(null);
        int code = !status.equals("DONE") ? ERROR : "FAIL".equals(verdict) ? FAILED : PASSED;
        out.println(code == ERROR ? status + ": " + run.path("error").asText("") : verdict != null ? verdict : "DONE (nothing to verify)");
        if (report != null) writeReport(report, name, run);
        return code;
    }

    private static boolean passed(JsonNode c) {
        return c.path("arrived").asInt() == c.path("sent").asInt();
    }

    private static String checkName(JsonNode c) {
        return c.path("template").asText() + " → " + c.path("expected").asText();
    }

    private static String counts(JsonNode c) {
        return "sent " + c.path("sent").asInt() + ", arrived " + c.path("arrived").asInt() + ", misrouted " + c.path("misrouted").asInt()
                + ", missing " + c.path("missing").asInt()
                + (c.path("medianMs").isNull() ? "" : ", median " + c.path("medianMs").asLong() + " ms, max " + c.path("maxMs").asLong() + " ms");
    }

    private int error(String scenario, Path report, String message) {
        out.println("ERROR: " + message);
        if (report != null) {
            try {
                write(report, suite(scenario == null ? "msgforge" : scenario, 0, List.of(testcase(scenario == null ? "msgforge" : scenario, 0,
                        "<error message=\"" + xml(message) + "\"/>")), 0, 1));
            } catch (IOException e) {
                out.println("ERROR: cannot write " + report + ": " + e.getMessage());
            }
        }
        return ERROR;
    }

    // ---- JUnit XML: one testsuite per run, one testcase per check, or one for the run when it has none

    private void writeReport(Path file, String name, JsonNode run) throws IOException {
        double seconds = Duration.between(Instant.parse(run.path("startedAt").asText()), Instant.parse(run.path("finishedAt").asText())).toMillis() / 1000.0;
        List<String> cases = new ArrayList<>();
        int failures = 0, errors = 0;
        String status = run.path("status").asText();
        if (!status.equals("DONE")) {
            errors++;
            cases.add(testcase(name, seconds, "<error message=\"" + xml(status + ": " + run.path("error").asText("")) + "\"/>"));
        } else if (run.path("checks").isEmpty()) {
            cases.add(testcase(name, seconds, ""));
        } else {
            for (JsonNode c : run.path("checks")) {
                if (!passed(c)) failures++;
                cases.add(testcase(checkName(c), seconds, passed(c) ? "" : "<failure message=\"" + xml("arrived " + c.path("arrived").asInt() + " of "
                        + c.path("sent").asInt() + ", misrouted " + c.path("misrouted").asInt() + ", missing " + c.path("missing").asInt()) + "\"/>"));
            }
        }
        write(file, suite(name, seconds, cases, failures, errors));
        out.println("Report written to " + file);
    }

    private static String suite(String name, double seconds, List<String> cases, int failures, int errors) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"" + xml(name) + "\" tests=\"" + cases.size() + "\" failures=\"" + failures
                + "\" errors=\"" + errors + "\" skipped=\"0\" time=\"" + seconds + "\">\n" + String.join("", cases) + "</testsuite>\n";
    }

    private static String testcase(String name, double seconds, String inside) {
        return "  <testcase classname=\"msgforge\" name=\"" + xml(name) + "\" time=\"" + seconds + "\"" + (inside.isEmpty() ? "/>\n" : ">" + inside + "</testcase>\n");
    }

    private static void write(Path file, String text) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String xml(String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\n' -> b.append("&#10;");
                default -> {
                    if (c >= 0x20 || c == '\t') b.appendCodePoint(c);   // other control characters are not allowed in XML 1.0
                }
            }
        });
        return b.toString();
    }

    // ---- where the run happens

    /** The server's beans without the server: no port is opened and nothing is written to msgforge.json. */
    private Backend local(List<String> settings) {
        SpringApplication app = new SpringApplication(MsgforgeApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        // the run's own lines are the output; warnings (a plugin that did not load) still show
        app.setDefaultProperties(Map.of("logging.level.root", "WARN"));
        ConfigurableApplicationContext context = app.run(settings.toArray(String[]::new));
        Scenarios scenarios = context.getBean(Scenarios.class);
        Runs runs = context.getBean(Runs.class);
        return new Backend() {
            public List<JsonNode> scenarios() {
                return scenarios.list().stream().map(s -> (JsonNode) json.valueToTree(s)).toList();
            }

            public JsonNode start(JsonNode scenario, Integer count, Double rate) {
                return json.valueToTree(scenarios.run(scenario.path("id").asText(), count, rate));
            }

            public JsonNode run(String id) {
                return json.valueToTree(runs.list().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow());
            }

            public void cancel(String id) {
                // the context's own shutdown cancels every run
            }

            public void close() {
                SpringApplication.exit(context);
            }
        };
    }

    /** A msgforge that is already running, through the same HTTP API the console uses. */
    private Backend remote(String url) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new Backend() {
            JsonNode call(String method, String path, JsonNode body) throws Exception {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(30))
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
                if (body != null) request.header("Content-Type", "application/json");
                HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                JsonNode answer = response.body().isBlank() ? json.missingNode() : json.readTree(response.body());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(url + " answered " + response.statusCode() + ": " + answer.path("error").asText(response.body()));
                }
                return answer;
            }

            public List<JsonNode> scenarios() throws Exception {
                List<JsonNode> list = new ArrayList<>();
                call("GET", "/api/scenarios", null).forEach(list::add);
                return list;
            }

            /** The scenario as a run request, which is what the server does with it too; this way --count and --rate fit in. */
            public JsonNode start(JsonNode scenario, Integer count, Double rate) throws Exception {
                ObjectNode request = scenario.deepCopy();
                request.put("label", scenario.path("name").asText());
                request.remove(List.of("id", "name"));
                if (count != null) request.put("count", count);
                if (rate != null) request.put("rate", rate);
                return call("POST", "/api/runs", request);
            }

            // ponytail: the API has no single-run lookup, so the list is read. It keeps the 20 latest runs that have ended:
            // on a server where more than 20 others end while this one is followed, this one is lost.
            public JsonNode run(String id) throws Exception {
                for (JsonNode r : call("GET", "/api/runs", null)) if (r.path("id").asText().equals(id)) return r;
                throw new IllegalStateException("run " + id + " is no longer listed on " + url);
            }

            public void cancel(String id) {
                try {
                    call("DELETE", "/api/runs/" + id, null);
                } catch (Exception ignored) {
                    // it may have ended already
                }
            }

            public void close() {
            }
        };
    }
}
