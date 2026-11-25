package io.msgforge;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class Api {

    private static final Logger log = LoggerFactory.getLogger(Api.class);
    private static final String TEXT = "text/plain;charset=UTF-8";

    private final Packs packs;
    private final Runs runs;
    private final TemplateEngine engine;
    private final Connections connections;
    private final Scenarios scenarios;
    private final Recogniser recogniser;
    private final Monitors monitors;
    private final Reports reports;

    public Api(Packs packs, Runs runs, TemplateEngine engine, Connections connections, Scenarios scenarios, Recogniser recogniser,
               Monitors monitors, Reports reports) {
        this.recogniser = recogniser;
        this.monitors = monitors;
        this.reports = reports;
        this.packs = packs;
        this.runs = runs;
        this.engine = engine;
        this.connections = connections;
        this.scenarios = scenarios;
    }

    // ---- packs and templates

    @GetMapping("/packs")
    public List<Packs.Pack> packs() {
        return packs.list();
    }

    public record PackRequest(String id, String name, Map<String, String> fields) {}

    @PostMapping("/packs")
    public Packs.Pack createPack(@RequestBody PackRequest r) {
        return packs.create(r.id(), r.name(), r.fields() == null ? Map.of() : r.fields());
    }

    @PutMapping("/packs/{pack}")
    public Packs.Pack updatePack(@PathVariable String pack, @RequestBody PackRequest r) {
        return packs.update(pack, r.name(), r.fields() == null ? Map.of() : r.fields());
    }

    @DeleteMapping("/packs/{pack}")
    public void deletePack(@PathVariable String pack) {
        packs.delete(pack);
    }

    @GetMapping(value = "/packs/{pack}/templates/{name}", produces = TEXT)
    public String template(@PathVariable String pack, @PathVariable String name) {
        return packs.read(pack, name);
    }

    @PutMapping(value = "/packs/{pack}/templates/{name}", consumes = "text/plain")
    public void save(@PathVariable String pack, @PathVariable String name, @RequestBody(required = false) String body,
                     @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        // If-None-Match: * is HTTP for "create, never replace". The console sends it for a new template and an import,
        // so a name that is taken (in any letter case, where the file system ignores case) is not overwritten unasked.
        if ("*".equals(ifNoneMatch) && packs.exists(pack, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, pack + " already has a template named " + name);
        }
        packs.write(pack, name, body == null ? "" : body);
    }

    @DeleteMapping("/packs/{pack}/templates/{name}")
    public void deleteTemplate(@PathVariable String pack, @PathVariable String name) {
        packs.deleteTemplate(pack, name);
    }

    public record PreviewRequest(String pack, String body, Map<String, String> vars) {}

    @PostMapping("/preview")
    public Map<String, String> preview(@RequestBody PreviewRequest r) {
        if (r.body() == null) throw new IllegalArgumentException("body is required");
        Runs.noNulls("Override", r.vars());
        return Map.of("rendered", engine.render(r.body(), packs.get(r.pack()).fields(), r.vars() == null ? Map.of() : r.vars()));
    }

    /**
     * Turns a sample message into a template: the values that have to differ per message become placeholders.
     * Nothing is saved. With fields=true, whole values that are one of the pack's field choices become that field too
     * (see {@link Recogniser}); without it the pack only says which placeholders exist.
     */
    @PostMapping(value = "/recognise", consumes = "text/plain")
    public Recogniser.Result recognise(@RequestParam(required = false) String pack, @RequestParam(defaultValue = "false") boolean fields,
                                       @RequestBody(required = false) String body) {
        if (body != null && body.length() > 1_000_000) throw new IllegalArgumentException("sample is larger than 1 MB");
        return recogniser.recognise(body == null ? "" : body, pack == null || pack.isBlank() ? Map.of() : packs.get(pack).fields(), fields);
    }

    /** Generators added by plugins or beans; the built-in ones are listed by the console itself. */
    @GetMapping("/generators")
    public List<String> generators() {
        return engine.customGenerators();
    }

    // ---- connections

    public record TypeView(String id, String label, List<TransportType.Field> fields, boolean canReceive) {}

    @GetMapping("/types")
    public List<TypeView> types() {
        return connections.types().stream().map(t -> new TypeView(t.id(), t.label(), t.fields(), t.canReceive())).toList();
    }

    @GetMapping("/connections")
    public List<Connections.View> connections() {
        return connections.list();
    }

    @PostMapping("/connections")
    public Connections.View createConnection(@RequestBody Connections.Connection c) {
        return connections.save(new Connections.Connection(null, c.name(), c.type(), c.settings(), c.destinations()));
    }

    @PutMapping("/connections/{id}")
    public Connections.View updateConnection(@PathVariable String id, @RequestBody Connections.Connection c) {
        connections.get(id); // 404 rather than silently creating a new one
        return connections.save(new Connections.Connection(id, c.name(), c.type(), c.settings(), c.destinations()));
    }

    @DeleteMapping("/connections/{id}")
    public void deleteConnection(@PathVariable String id) {
        connections.delete(id);
    }

    /** Tries the connection as typed in the form. Always 200: a refused connection is a result, not an API error. */
    @PostMapping("/connections/test")
    public Map<String, Object> testConnection(@RequestBody Connections.Connection c) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            connections.test(c);
            out.put("ok", true);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", Runs.describe(e));
        }
        return out;
    }

    // ---- scenarios

    @GetMapping("/scenarios")
    public List<Scenarios.Scenario> scenarios() {
        return scenarios.list();
    }

    @PostMapping("/scenarios")
    public Scenarios.Scenario createScenario(@RequestBody Scenarios.Scenario s) {
        return scenarios.save(s.withId(null));
    }

    @PutMapping("/scenarios/{id}")
    public Scenarios.Scenario updateScenario(@PathVariable String id, @RequestBody Scenarios.Scenario s) {
        scenarios.get(id); // 404 rather than silently creating a new one
        return scenarios.save(s.withId(id));
    }

    @DeleteMapping("/scenarios/{id}")
    public void deleteScenario(@PathVariable String id) {
        scenarios.delete(id);
    }

    @PostMapping("/scenarios/{id}/run")
    public Runs.View runScenario(@PathVariable String id) {
        return scenarios.run(id);
    }

    // ---- runs

    @PostMapping("/runs")
    public Runs.View start(@RequestBody Runs.Request request) {
        return runs.start(request);
    }

    @GetMapping("/runs")
    public List<Runs.View> runs() {
        return runs.list();
    }

    /** One run in full: the list's view, the series over time, and what arrived on each watched destination. */
    @GetMapping("/runs/{id}")
    public Runs.Detail run(@PathVariable String id) {
        return runs.detail(id, true);
    }

    @DeleteMapping("/runs/{id}")
    public void cancel(@PathVariable String id) {
        runs.cancel(id);
    }

    /** The run as a file: format is html, json, csv (the summary) or series (the series over time, as CSV). */
    @GetMapping("/runs/{id}/report")
    public ResponseEntity<String> report(@PathVariable String id, @RequestParam(defaultValue = "html") String format) {
        Runs.Detail detail = runs.detail(id, false);
        return download(Reports.fileName(detail.run(), format), reports.render(detail, format));
    }

    // ---- saved reports

    @GetMapping("/reports")
    public Reports.Saved reports() {
        return reports.saved();
    }

    @GetMapping("/reports/{name}")
    public ResponseEntity<String> savedReport(@PathVariable String name) {
        return download(name, reports.read(name));
    }

    /** As a download, and never as a page of this site: a report quotes names and errors that came from outside. */
    private static ResponseEntity<String> download(String name, String content) {
        String type = name.endsWith(".html") ? "text/html" : name.endsWith(".json") ? "application/json" : "text/csv";
        return ResponseEntity.ok().header("Content-Type", type + ";charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'").body(content);
    }

    // ---- monitors

    @GetMapping("/monitors")
    public List<Monitors.View> monitors() {
        return monitors.list();
    }

    @PostMapping("/monitors")
    public Monitors.View startMonitor(@RequestBody Monitors.Request request) {
        return monitors.start(request);
    }

    @PostMapping("/monitors/{id}/reset")
    public Monitors.View resetMonitor(@PathVariable String id) {
        return monitors.reset(id);
    }

    @DeleteMapping("/monitors/{id}")
    public void stopMonitor(@PathVariable String id) {
        monitors.stop(id);
    }

    // ---- messages

    /** The latest messages without their bodies and headers: the console asks for this every second, and a body can be 1 MB. */
    public record MessageView(String runId, long n, int target, String connection, String destination, String template, Instant at, String fault) {}

    @GetMapping("/messages")
    public List<MessageView> messages() {
        return runs.messages().stream().map(m -> new MessageView(m.runId(), m.n(), m.target(), m.connection(), m.destination(), m.template(), m.at(), m.fault())).toList();
    }

    /** One message in full, for when it is selected. */
    @GetMapping("/messages/{runId}/{n}/{target}")
    public Runs.Sent message(@PathVariable String runId, @PathVariable long n, @PathVariable int target) {
        return runs.message(runId, n, target);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** Everything else still answers in the shape the console reads, {"error": …}. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        // What Spring itself decides about a request (wrong method, wrong content type) keeps its status.
        if (e instanceof ErrorResponse known) {
            return ResponseEntity.status(known.getStatusCode()).body(Map.of("error", String.valueOf(known.getBody().getDetail())));
        }
        if (e instanceof HttpMessageConversionException || e instanceof TypeMismatchException) {
            return ResponseEntity.badRequest().body(Map.of("error", "The request is not the JSON or the values this call expects"));
        }
        // The message stays in the log: an unexpected exception has not been checked for what it quotes.
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "Unexpected error (" + e.getClass().getSimpleName() + "). The server log has the details."));
    }
}
