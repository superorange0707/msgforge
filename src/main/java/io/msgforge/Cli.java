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
 *   run <scenario> [--report file] [--save dir] [--url http://host:port] [--count n] [--rate r]
 *                  [--duration s] [--ramp-up s] [--senders n] [--seed n] [--invalid percent]
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

    /** The command is the first argument that is not a --setting, so settings may come before it as well as after. */
    static boolean handles(String[] args) {
        String command = command(args);
        return command != null && (command.equals("run") || command.equals("list"));
    }

    private static String command(String[] args) {
        for (String a : args) if (!a.startsWith("--")) return a;
        return null;
    }

    /** @return the exit code */
    static int main(String[] args, PrintStream out) {
        return new Cli(out).execute(args);
    }

    /** What a run's caller needs from it, the same whether it ran here or on a server: the API's run view as JSON. */
    private interface Backend extends AutoCloseable {
        List<JsonNode> scenarios() throws Exception;

        JsonNode start(JsonNode scenario, Scenarios.Overrides overrides) throws Exception;

        JsonNode run(String id) throws Exception;

        /** The run as a report in one of {@link Reports#FORMATS}. */
        String report(String id, String format) throws Exception;

        void cancel(String id);

        @Override
        void close();
    }

    private int execute(String[] args) {
        String command = command(args), scenario = null, report = null, save = null, url = null;
        boolean commandSeen = false;
        Integer count = null, duration = null, rampUp = null, senders = null;
        Double rate = null, invalid = null;
        Long seed = null;
        List<String> spring = new ArrayList<>();   // everything else is a setting, as for the server
        try {
            for (int i = 0; i < args.length; i++) {
                if (!commandSeen && args[i].equals(command)) {
                    commandSeen = true;
                    continue;
                }
                String a = args[i], name = a.contains("=") ? a.substring(0, a.indexOf('=')) : a;
                boolean ours = List.of("--report", "--save", "--url", "--count", "--rate", "--duration", "--ramp-up", "--senders", "--seed", "--invalid").contains(name);
                String value = !ours ? null : a.contains("=") ? a.substring(a.indexOf('=') + 1) : ++i < args.length ? args[i] : null;
                if (ours && value == null) throw new IllegalArgumentException(name + " needs a value");
                switch (name) {
                    case "--report" -> report = value;
                    case "--url" -> url = value.replaceAll("/+$", "");
                    case "--count" -> count = number(name, value, Integer::valueOf);
                    case "--rate" -> rate = number(name, value, Double::valueOf);
                    case "--save" -> save = value;
                    case "--duration" -> duration = number(name, value, Integer::valueOf);
                    case "--ramp-up" -> rampUp = number(name, value, Integer::valueOf);
                    case "--senders" -> senders = number(name, value, Integer::valueOf);
                    case "--seed" -> seed = number(name, value, Long::valueOf);
                    case "--invalid" -> invalid = number(name, value, Double::valueOf);
                    default -> {
                        if (a.startsWith("--")) spring.add(a);
                        else if (scenario == null && command.equals("run")) scenario = a;
                        else throw new IllegalArgumentException("unexpected argument " + a);
                    }
                }
            }
            if (command.equals("run") && scenario == null) throw new IllegalArgumentException("run needs the name or id of a scenario");
        } catch (IllegalArgumentException e) {
            out.println("msgforge: " + e.getMessage() + ". bin/msgforge --help shows the usage.");
            return ERROR;
        }
        Path reportFile = report == null ? null : workdir().resolve(report), saveDir = save == null ? null : workdir().resolve(save);
        Scenarios.Overrides overrides = new Scenarios.Overrides(count, rate, duration, rampUp, senders, seed, invalid);

        Backend backend;
        try {
            backend = url == null ? local(spring) : remote(url);
        } catch (Exception e) {   // the cause says what is wrong (a msgforge.json that is not JSON); the wrappers only say where
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return error(scenario, reportFile, "msgforge could not start: " + Runs.describe(root));
        }
        try (backend) {
            List<JsonNode> scenarios = backend.scenarios();
            if (command.equals("list")) {
                if (scenarios.isEmpty()) out.println("No scenarios saved.");
                for (JsonNode s : scenarios) out.println(s.path("id").asText() + "  " + s.path("name").asText());
                return PASSED;
            }
            return run(backend, find(scenarios, scenario), overrides, reportFile, saveDir);
        } catch (Exception e) {
            return error(scenario, reportFile, Runs.describe(e));
        }
    }

    private static <T> T number(String option, String value, java.util.function.Function<String, T> parse) {
        try {
            return parse.apply(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " needs a number, not " + value);
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

    private int run(Backend backend, JsonNode scenario, Scenarios.Overrides overrides, Path report, Path saveDir) throws Exception {
        String name = scenario.path("name").asText();
        JsonNode run = backend.start(scenario, overrides);
        String id = run.path("id").asText();
        out.println("Scenario " + name + ": run " + id + ", " + plan(run));
        // A pipeline that is cancelled stops the JVM; the run on a server would otherwise carry on.
        Thread cancel = new Thread(() -> backend.cancel(id));
        Runtime.getRuntime().addShutdownHook(cancel);
        String status = "";
        long done = -1, lastPrint = 0;
        while (true) {
            run = backend.run(id);
            // the status is what a run sets last when it ends, so a view that says it ended has everything else too
            boolean ended = !List.of("RUNNING", "VERIFYING").contains(run.path("status").asText());
            long handled = run.path("sent").asLong() + run.path("failed").asLong();
            if (!run.path("status").asText().equals(status) || (System.nanoTime() - lastPrint > 2_000_000_000L && handled != done) || ended) {
                status = run.path("status").asText();
                done = handled;
                lastPrint = System.nanoTime();
                out.println("  " + status.toLowerCase() + ", " + progress(run));
            }
            if (ended) break;
            Thread.sleep(200);
        }
        Runtime.getRuntime().removeShutdownHook(cancel);

        for (JsonNode c : run.path("checks")) out.println("  " + (passed(c) ? "PASS " : "FAIL ") + checkName(c) + ": " + counts(c));
        for (JsonNode e : run.path("errors")) out.println("  " + e.path("count").asLong() + " x " + e.path("message").asText());
        JsonNode latency = run.path("latency");
        if (latency.isObject()) {
            out.println("  " + run.path("actualRate").asText() + " a second on average over " + run.path("elapsedMs").asLong() / 1000.0 + " s, send p50 "
                    + latency.path("p50").asText() + " ms, p95 " + latency.path("p95").asText() + " ms, p99 " + latency.path("p99").asText() + " ms, seed " + run.path("seed").asText());
        }
        String verdict = run.path("verdict").asText(null);
        int code = !status.equals("DONE") ? ERROR : "FAIL".equals(verdict) ? FAILED : PASSED;
        out.println(code == ERROR ? status + ": " + run.path("error").asText("") : verdict != null ? verdict : "DONE (nothing to verify)");
        if (report != null) writeReport(report, name, run);
        if (saveDir != null) {
            for (String format : Reports.FORMATS) {
                String file = "msgforge-" + id + "." + (format.equals("series") ? "series.csv" : format);
                write(saveDir.resolve(file), backend.report(id, format));
            }
            out.println("Reports written to " + saveDir + " (msgforge-" + id + ".html, .json, .csv, .series.csv)");
        }
        return code;
    }

    private static String plan(JsonNode run) {
        long count = run.path("count").asLong();
        int duration = run.path("durationSeconds").asInt(0);
        return (count > 0 ? count + " messages" : "no number of messages") + (duration > 0 ? ", for " + duration + " s at most" : count > 0 ? "" : ", until stopped")
                + (run.path("rate").isNumber() ? ", " + run.path("rate").asText() + " a second" : "");
    }

    private static String progress(JsonNode run) {
        long count = run.path("count").asLong(), failed = run.path("failed").asLong(), invalid = run.path("invalid").asLong();
        return "sent " + run.path("sent").asLong() + (count > 0 ? " of " + count : "") + (failed > 0 ? ", failed " + failed : "") + (invalid > 0 ? ", invalid " + invalid : "");
    }

    private static boolean passed(JsonNode c) {
        return c.path("arrived").asInt() == c.path("sent").asInt();
    }

    private static String checkName(JsonNode c) {
        return c.path("template").asText() + (c.path("invalid").asBoolean() ? " (invalid)" : "") + " → " + c.path("expected").asText();
    }

    private static String counts(JsonNode c) {
        boolean invalid = c.path("invalid").asBoolean();
        return "sent " + c.path("sent").asInt() + (invalid ? ", rejected as expected " : ", arrived ") + c.path("arrived").asInt()
                + (c.path("accepted").asInt() > 0 ? ", accepted although invalid " + c.path("accepted").asInt() : "")
                + (c.path("rejected").asInt() > 0 ? ", valid but rejected " + c.path("rejected").asInt() : "")
                + ", misrouted " + c.path("misrouted").asInt() + ", missing " + c.path("missing").asInt()
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
            long failed = run.path("failed").asLong();
            if (failed > 0) failures++;
            cases.add(testcase(name, seconds, failed == 0 ? "" : "<failure message=\"" + xml(failed + " of " + (failed + run.path("sent").asLong()) + " sends failed") + "\"/>"));
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
        Reports reports = context.getBean(Reports.class);
        return new Backend() {
            public List<JsonNode> scenarios() {
                return scenarios.list().stream().map(s -> (JsonNode) json.valueToTree(s)).toList();
            }

            public JsonNode start(JsonNode scenario, Scenarios.Overrides overrides) {
                return json.valueToTree(scenarios.run(scenario.path("id").asText(), overrides));
            }

            public JsonNode run(String id) {
                return json.valueToTree(runs.detail(id, false).run());
            }

            public String report(String id, String format) {
                return reports.render(runs.detail(id, false), format);
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
                HttpResponse<String> response = send(method, path, body);
                JsonNode answer;
                try {
                    answer = response.body().isBlank() ? json.missingNode() : json.readTree(response.body());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {   // a login page, a proxy's error page
                    throw new IllegalStateException(url + " answered " + response.statusCode()
                            + " with something that is not msgforge's answer; a proxy that asks for a login may be in front of it");
                }
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(url + " answered " + response.statusCode() + ": " + answer.path("error").asText(response.body()));
                }
                return answer;
            }

            HttpResponse<String> send(String method, String path, JsonNode body) throws Exception {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(30))
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
                if (body != null) request.header("Content-Type", "application/json");
                try {
                    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                } catch (java.net.ConnectException e) {
                    throw new IllegalStateException("cannot reach " + url + " (" + Runs.describe(e) + ")");
                }
            }

            public List<JsonNode> scenarios() throws Exception {
                List<JsonNode> list = new ArrayList<>();
                call("GET", "/api/scenarios", null).forEach(list::add);
                return list;
            }

            /** The scenario as a run request, which is what the server does with it too; this way the options fit in. */
            public JsonNode start(JsonNode scenario, Scenarios.Overrides o) throws Exception {
                ObjectNode request = scenario.deepCopy();
                request.put("label", scenario.path("name").asText());
                request.remove(List.of("id", "name"));
                if (o.count() != null) request.put("count", o.count());
                if (o.rate() != null) request.put("rate", o.rate());
                if (o.seed() != null) request.put("seed", o.seed());
                if (o.durationSeconds() != null || o.rampUpSeconds() != null || o.senders() != null) {
                    ObjectNode load = request.path("load").isObject() ? (ObjectNode) request.get("load") : request.putObject("load");
                    if (o.durationSeconds() != null) load.put("durationSeconds", o.durationSeconds());
                    if (o.rampUpSeconds() != null) load.put("rampUpSeconds", o.rampUpSeconds());
                    if (o.senders() != null) load.put("senders", o.senders());
                }
                if (o.invalidPercent() != null) {
                    (request.path("invalid").isObject() ? (ObjectNode) request.get("invalid") : request.putObject("invalid")).put("percent", o.invalidPercent());
                }
                return call("POST", "/api/runs", request);
            }

            public JsonNode run(String id) throws Exception {
                return call("GET", "/api/runs/" + id, null).path("run");
            }

            public String report(String id, String format) throws Exception {
                HttpResponse<String> response = send("GET", "/api/runs/" + id + "/report?format=" + format, null);
                if (response.statusCode() != 200) throw new IllegalStateException(url + " answered " + response.statusCode() + " for the report");
                return response.body();
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
