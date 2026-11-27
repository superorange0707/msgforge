package io.msgforge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A run as a file: JSON (everything), CSV (the summary, and the series over time), and one HTML page that needs
 * nothing else to open and prints as it looks. With a reports folder set, every run that ends is saved there in
 * all four, and only the newest are kept.
 *
 * A report holds names, counts, errors and bucket values. It never holds a message body, sent or arrived.
 */
@Component
public class Reports {

    private static final Logger log = LoggerFactory.getLogger(Reports.class);
    static final List<String> FORMATS = List.of("html", "json", "csv", "series");
    /** Every file this class writes, and the only names it will read or delete. */
    private static final Pattern NAME = Pattern.compile("msgforge-(\\d{8}-\\d{9})-([0-9a-f]{8})(?:-([A-Za-z0-9_-]{1,40}))?\\.(html|json|csv|series\\.csv)");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    /** One saved run: its files are the formats it was saved in. */
    public record Report(String id, String label, Instant at, List<String> files) {}

    /** enabled is false when no reports folder is set; keep is how many runs it holds before the oldest go. */
    public record Saved(boolean enabled, int keep, List<Report> reports) {}

    private final ObjectMapper json;
    private final Path dir;
    private final int keep;

    public Reports(Runs runs, ObjectMapper json, @Value("${msgforge.reports:}") String dir, @Value("${msgforge.reports-keep:50}") int keep) {
        this.json = json;
        this.dir = dir == null || dir.isBlank() ? null : Path.of(dir.trim()).toAbsolutePath().normalize();
        this.keep = Math.max(1, Math.min(keep, 10_000));
        if (this.dir != null) runs.onFinished(this::save);
    }

    /** msgforge-20260102-030405123-1a2b3c4d-nightly-soak.html: the start time to the millisecond (names sort by it), the run's id, and its label as far as a file name takes it. */
    static String fileName(Runs.View run, String format) {
        String slug = run.label().replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) slug = slug.substring(0, 40);
        return "msgforge-" + STAMP.format(run.startedAt()) + "-" + run.id() + (slug.isEmpty() ? "" : "-" + slug) + "." + (format.equals("series") ? "series.csv" : format);
    }

    public String render(Runs.Detail detail, String format) {
        try {
            return switch (format) {
                case "html" -> html(detail);
                case "json" -> json.writerWithDefaultPrettyPrinter().writeValueAsString(detail);
                case "csv" -> summaryCsv(detail);
                case "series" -> seriesCsv(detail);
                default -> throw new IllegalArgumentException("A report is html, json, csv or series");
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The report could not be written as JSON");
        }
    }

    // ---- the reports folder

    /** Called when a run ends. A folder that cannot be written is logged by its error's kind and the run is not disturbed. */
    private synchronized void save(Runs.Detail detail) {
        try {
            Files.createDirectories(dir);
            for (String format : FORMATS) {
                Files.writeString(dir.resolve(fileName(detail.run(), format)), render(detail, format), StandardCharsets.UTF_8);
            }
            // Oldest first out. Only files named the way this class names them are ever touched.
            List<Report> all = list();
            for (Report old : all.subList(Math.min(keep, all.size()), all.size())) {
                for (String file : old.files()) Files.deleteIfExists(dir.resolve(file));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("The report of run {} could not be saved ({})", detail.run().id(), e.getClass().getSimpleName());
        }
    }

    public Saved saved() {
        if (dir == null) return new Saved(false, keep, List.of());
        try {
            return new Saved(true, keep, list());
        } catch (IOException e) {
            throw new UncheckedIOException("The reports folder cannot be read", e);
        }
    }

    /** Newest first. */
    private List<Report> list() throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        Map<String, List<String>> byRun = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (String name : files.map(f -> f.getFileName().toString()).filter(n -> NAME.matcher(n).matches()).sorted(Comparator.reverseOrder()).toList()) {
                Matcher m = NAME.matcher(name);
                m.matches();
                byRun.computeIfAbsent(m.group(1) + "-" + m.group(2), k -> new ArrayList<>()).add(name);
            }
        }
        List<Report> out = new ArrayList<>();
        byRun.forEach((run, files) -> {
            Matcher m = NAME.matcher(files.get(0));
            m.matches();
            files.sort(Comparator.comparingInt(f -> FORMATS.indexOf(f.endsWith(".series.csv") ? "series" : f.substring(f.lastIndexOf('.') + 1))));
            out.add(new Report(m.group(2), m.group(3) == null ? "" : m.group(3), Instant.from(STAMP.parse(m.group(1))), List.copyOf(files)));
        });
        return out;
    }

    /** One saved file. The name has to be one this class wrote: nothing else in the folder, and nothing outside it, can be asked for. */
    public String read(String name) {
        if (dir == null || name == null || !NAME.matcher(name).matches()) throw new NoSuchElementException("no report " + name);
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) throw new NoSuchElementException("no report " + name);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The report cannot be read", e);
        }
    }

    // ---- CSV

    /**
     * One CSV cell. Text that came from outside (an error, a bucket value) could start with = + - or @, which a
     * spreadsheet takes for a formula: it gets a ' in front.
     */
    static String cell(Object value) {
        if (value == null) return "";
        if (value instanceof Number) return value.toString();
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.matches("[^,\"\\r\\n]*") ? s : "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static void row(StringBuilder out, Object... cells) {
        for (int i = 0; i < cells.length; i++) out.append(i == 0 ? "" : ",").append(cell(cells[i]));
        out.append("\r\n");
    }

    private static String seriesCsv(Runs.Detail d) {
        StringBuilder out = new StringBuilder();
        row(out, "t_seconds", "seconds", "target_rate", "sent", "failed", "invalid", "sent_rate");
        for (Stats.Point p : d.series()) {
            row(out, p.t(), p.seconds(), p.target(), p.sent(), p.failed(), p.invalid(), Stats.round(p.sent() / p.seconds()));
        }
        return out.toString();
    }

    private static Map<String, Object> summary(Runs.View r) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("label", r.label());
        s.put("run", r.id());
        s.put("status", r.status());
        s.put("verdict", r.verdict());
        s.put("error", r.error());
        s.put("started", r.startedAt());
        s.put("finished", r.finishedAt());
        s.put("templates", String.join(" ", r.templates()));
        s.put("targets", String.join(" | ", r.targets().stream().map(t -> t.connection() + " / " + t.destination()).toList()));
        s.put("mode", r.mode());
        s.put("messages_planned", r.count() == 0 ? null : r.count());
        s.put("duration_planned_seconds", r.durationSeconds());
        s.put("ramp_up_seconds", r.rampUpSeconds());
        s.put("target_rate", r.rate());
        s.put("senders", r.senders());
        s.put("seed", r.seed());
        s.put("sent", r.sent());
        s.put("failed", r.failed());
        s.put("invalid", r.invalid());
        s.put("sending_seconds", r.elapsedMs() / 1000.0);
        s.put("average_rate", r.actualRate());
        s.put("send_p50_ms", r.latency() == null ? null : r.latency().p50());
        s.put("send_p95_ms", r.latency() == null ? null : r.latency().p95());
        s.put("send_p99_ms", r.latency() == null ? null : r.latency().p99());
        s.put("send_max_ms", r.latency() == null ? null : r.latency().max());
        return s;
    }

    private static String summaryCsv(Runs.Detail d) {
        Runs.View r = d.run();
        StringBuilder out = new StringBuilder();
        row(out, "name", "value");
        summary(r).forEach((name, value) -> row(out, name, value));
        if (!r.faults().isEmpty()) {
            out.append("\r\n");
            row(out, "invalid_kind", "messages");
            r.faults().forEach((kind, n) -> row(out, kind, n));
        }
        if (!r.checks().isEmpty()) {
            out.append("\r\n");
            row(out, "check", "messages", "expected", "sent", "arrived", "misrouted", "missing", "rejected", "accepted", "unrecognised", "median_ms", "max_ms");
            for (Runs.Check c : r.checks()) {
                row(out, c.template(), c.invalid() ? "invalid" : "valid", c.expected(), c.sent(), c.arrived(), c.misrouted(), c.missing(), c.rejected(), c.accepted(), c.unrecognised(), c.medianMs(), c.maxMs());
            }
        }
        if (!r.errors().isEmpty()) {
            out.append("\r\n");
            row(out, "error", "count", "last");
            for (Stats.ErrorCount e : r.errors()) row(out, e.message(), e.count(), e.lastAt());
        }
        for (Observer.View w : d.watches()) {
            out.append("\r\n");
            row(out, "watched", "arrivals", "last");
            row(out, w.connection() + " / " + w.destination(), w.count(), w.lastAt());
            for (Observer.Table t : w.tables()) {
                row(out, "bucket " + t.name(), "arrivals");
                for (Observer.Count c : t.values()) row(out, c.value(), c.count());
            }
        }
        return out.toString();
    }

    // ---- HTML

    static String esc(Object value) {
        if (value == null) return "";
        StringBuilder b = new StringBuilder();
        value.toString().codePoints().forEach(c -> {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.appendCodePoint(c);
            }
        });
        return b.toString();
    }

    private static String num(double v) {
        return v >= 100 || v == Math.rint(v) ? String.format(Locale.ROOT, "%,.0f", v) : String.format(Locale.ROOT, v >= 10 ? "%.1f" : "%.2f", v);
    }

    private static String span(long ms) {
        Duration d = Duration.ofMillis(ms);
        return d.toHours() > 0 ? String.format(Locale.ROOT, "%d h %02d min", d.toHours(), d.toMinutesPart())
                : d.toMinutes() > 0 ? String.format(Locale.ROOT, "%d min %02d s", d.toMinutes(), d.toSecondsPart()) : String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    private static String tile(String label, String value, String tone) {
        return "<div class=\"tile" + (tone.isEmpty() ? "" : " " + tone) + "\"><b>" + esc(value) + "</b><span>" + esc(label) + "</span></div>";
    }

    private static String table(String title, List<String> head, List<List<Object>> rows) {
        StringBuilder b = new StringBuilder("<section><h2>" + esc(title) + "</h2><table><thead><tr>");
        head.forEach(h -> b.append("<th>").append(esc(h)).append("</th>"));
        b.append("</tr></thead><tbody>");
        for (List<Object> row : rows) {
            b.append("<tr>");
            for (Object cell : row) b.append(cell instanceof Number ? "<td class=\"n\">" + esc(cell instanceof Double v ? num(v) : String.format(Locale.ROOT, "%,d", ((Number) cell).longValue())) : "<td>" + esc(cell)).append("</td>");
            b.append("</tr>");
        }
        return b.append("</tbody></table></section>").toString();
    }

    /** The rate over time as an SVG drawing: what the plan asked for, what went out, and what failed. */
    static String chart(List<Stats.Point> series) {
        int w = 960, h = 260, left = 56, right = 16, top = 14, bottom = 30;
        if (series.isEmpty()) return "";
        double max = 1, end = 1;
        for (Stats.Point p : series) {
            max = Math.max(max, Math.max(p.sent() / p.seconds(), Math.max(p.failed() / p.seconds(), p.target() == null ? 0 : p.target())));
            end = Math.max(end, p.t() + p.seconds());
        }
        max = nice(max);
        double sx = (w - left - right) / end, sy = (h - top - bottom) / max;
        StringBuilder sent = new StringBuilder(), failed = new StringBuilder(), target = new StringBuilder();
        boolean anyFailed = false, anyTarget = false;
        for (Stats.Point p : series) {
            String x = String.format(Locale.ROOT, "%.1f", left + (p.t() + p.seconds() / 2) * sx);
            sent.append(sent.isEmpty() ? "M" : "L").append(x).append(' ').append(String.format(Locale.ROOT, "%.1f", h - bottom - p.sent() / p.seconds() * sy));
            failed.append(failed.isEmpty() ? "M" : "L").append(x).append(' ').append(String.format(Locale.ROOT, "%.1f", h - bottom - p.failed() / p.seconds() * sy));
            anyFailed |= p.failed() > 0;
            if (p.target() != null) {
                anyTarget = true;
                target.append(target.isEmpty() ? "M" : "L").append(x).append(' ').append(String.format(Locale.ROOT, "%.1f", h - bottom - p.target() * sy));
            }
        }
        StringBuilder b = new StringBuilder("<svg class=\"chart\" viewBox=\"0 0 " + w + " " + h + "\" role=\"img\" aria-label=\"Messages a second over the run\">");
        for (int i = 0; i <= 4; i++) {
            double y = h - bottom - (h - top - bottom) * i / 4.0;
            b.append(String.format(Locale.ROOT, "<line class=\"grid\" x1=\"%d\" x2=\"%d\" y1=\"%.1f\" y2=\"%.1f\"/><text class=\"axis\" x=\"%d\" y=\"%.1f\" text-anchor=\"end\">%s</text>",
                    left, w - right, y, y, left - 8, y + 4, esc(num(max * i / 4))));
        }
        // a label at whole seconds only, at most seven of them: a four-second run is not 0:01, 0:01, 0:03, 0:03
        int step = 86400;
        for (int s : new int[] {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 21600, 43200}) {
            if (end / s <= 6) {
                step = s;
                break;
            }
        }
        for (int at = 0; at <= end; at += step) {
            double x = left + (w - left - right) * at / (double) end;
            b.append(String.format(Locale.ROOT, "<text class=\"axis\" x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>", x, h - 8, esc(clock(at))));
        }
        String first = sent.substring(1, sent.indexOf(" ")), last = sent.substring(sent.lastIndexOf("L") < 0 ? 1 : sent.lastIndexOf("L") + 1, sent.lastIndexOf(" "));
        b.append("<path class=\"area\" d=\"").append(sent).append("L").append(last).append(' ').append(h - bottom).append("L").append(first).append(' ').append(h - bottom).append("Z\"/>");
        if (anyTarget) b.append("<path class=\"target\" d=\"").append(target).append("\"/>");
        b.append("<path class=\"sent\" d=\"").append(sent).append("\"/>");
        if (anyFailed) b.append("<path class=\"failed\" d=\"").append(failed).append("\"/>");
        return b.append("</svg><p class=\"legend\"><i class=\"k sent\"></i>sent a second" + (anyTarget ? "<i class=\"k target\"></i>target" : "")
                + (anyFailed ? "<i class=\"k failed\"></i>failed a second" : "") + "</p>").toString();
    }

    /** The next round number up: 1, 2, 5 times a power of ten, so the axis reads 0, 50, 100 and not 0, 43.7, 87.4. */
    static double nice(double v) {
        double power = Math.pow(10, Math.floor(Math.log10(v)));
        for (double step : new double[] {1, 2, 4, 5, 8, 10}) if (v <= step * power) return step * power;
        return 10 * power;
    }

    private static String clock(double seconds) {
        long s = Math.round(seconds);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static final String STYLE = """
            :root{--bg:#eef5fb;--card:#fff;--line:#d9e6f0;--text:#05263b;--muted:#557085;--accent:#0086d1;--ok:#0e9a72;--bad:#d6364a;--warn:#a87400}
            @media (prefers-color-scheme:dark){:root{--bg:#021523;--card:#0a2437;--line:#173a52;--text:#e9f4fc;--muted:#8fb0c7;--accent:#46c8f8;--ok:#3ddc97;--bad:#ff7b88;--warn:#f5c259}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 Inter,-apple-system,"Segoe UI",Roboto,sans-serif}
            main{max-width:1040px;margin:0 auto;padding:32px 24px 48px}
            header{display:flex;align-items:baseline;gap:12px;flex-wrap:wrap;margin-bottom:6px}
            h1{margin:0;font-size:24px;letter-spacing:-.02em}h2{margin:0 0 10px;font-size:13px;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}
            .chip{font-size:11px;font-weight:650;letter-spacing:.07em;padding:3px 9px;border-radius:99px;background:color-mix(in srgb,var(--muted) 16%,transparent);color:var(--muted)}
            .chip.PASS,.chip.DONE{color:var(--ok);background:color-mix(in srgb,var(--ok) 14%,transparent)}
            .chip.FAIL,.chip.FAILED{color:var(--bad);background:color-mix(in srgb,var(--bad) 14%,transparent)}
            .chip.CANCELLED{color:var(--warn);background:color-mix(in srgb,var(--warn) 14%,transparent)}
            .sub{color:var(--muted);margin:0 0 22px;font-variant-numeric:tabular-nums}
            .error{margin:0 0 20px;padding:10px 14px;border-radius:10px;border:1px solid color-mix(in srgb,var(--bad) 30%,transparent);background:color-mix(in srgb,var(--bad) 8%,transparent);color:var(--bad);overflow-wrap:anywhere}
            .tiles{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:20px}
            .tile,section{background:var(--card);border:1px solid var(--line);border-radius:12px}
            .tile{padding:12px 14px}.tile b{display:block;font-size:22px;font-weight:650;letter-spacing:-.02em;font-variant-numeric:tabular-nums}
            .tile span{color:var(--muted);font-size:12px}.tile.bad b{color:var(--bad)}.tile.ok b{color:var(--ok)}
            section{padding:16px 18px;margin-bottom:14px;break-inside:avoid}
            .chart{display:block;width:100%;height:auto}.grid{stroke:var(--line)}.axis{fill:var(--muted);font-size:11px}
            .sent{fill:none;stroke:var(--accent);stroke-width:2;stroke-linejoin:round}.area{fill:var(--accent);opacity:.12}
            .target{fill:none;stroke:var(--muted);stroke-width:1.5;stroke-dasharray:6 5}.failed{fill:none;stroke:var(--bad);stroke-width:2;stroke-linejoin:round}
            .legend{margin:8px 0 0;color:var(--muted);font-size:12px}.k{display:inline-block;width:18px;height:0;border-top:2px solid var(--accent);margin:0 6px 3px 0;vertical-align:middle}
            .k.target{border-top:2px dashed var(--muted);margin-left:16px}.k.failed{border-color:var(--bad);margin-left:16px}
            table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}th{text-align:left;color:var(--muted);font-weight:550;font-size:12px}
            th,td{padding:6px 10px 6px 0;border-bottom:1px solid var(--line);vertical-align:top;overflow-wrap:anywhere}tr:last-child td{border-bottom:0}td.n{text-align:right;white-space:nowrap}th:last-child,td:last-child{padding-right:0}
            footer{color:var(--muted);font-size:12px;margin-top:22px}
            @media print{:root{--bg:#fff;--card:#fff;--line:#cfd9e2;--text:#000;--muted:#44525e;--accent:#0073b3;--ok:#0b7a5a;--bad:#b3202f;--warn:#7a5500}main{max-width:none;padding:0}section,.tile{border-color:#b9c6d1}}
            """;

    private static String html(Runs.Detail d) {
        Runs.View r = d.run();
        String shown = r.status() == Runs.Status.DONE && r.verdict() != null ? r.verdict() : r.status().name();
        StringBuilder b = new StringBuilder("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + esc(r.label()) + " · msgforge report</title><style>\n" + STYLE + "</style></head><body><main>");
        b.append("<header><h1>").append(esc(r.label())).append("</h1><span class=\"chip ").append(shown).append("\">").append(shown).append("</span></header>");
        b.append("<p class=\"sub\">").append(esc(SHOWN.format(r.startedAt()))).append(r.finishedAt() == null ? " · still running" : " · " + esc(span(Duration.between(r.startedAt(), r.finishedAt()).toMillis())))
                .append(" · run ").append(esc(r.id())).append("</p>");
        if (r.error() != null) b.append("<p class=\"error\">").append(esc(r.error())).append("</p>");

        b.append("<div class=\"tiles\">").append(tile("sent", String.format(Locale.ROOT, "%,d", r.sent()), ""))
                .append(tile("failed", String.format(Locale.ROOT, "%,d", r.failed()), r.failed() > 0 ? "bad" : ""));
        if (!r.faults().isEmpty()) b.append(tile("invalid on purpose", String.format(Locale.ROOT, "%,d", r.invalid()), ""));
        b.append(tile(r.rate() == null ? "rate" : "rate · target " + num(r.rate()) + "/s", num(r.actualRate()) + "/s", "")).append(tile("sending", span(r.elapsedMs()), ""));
        if (r.latency() != null) {
            b.append(tile("send p50", num(r.latency().p50()) + " ms", "")).append(tile("send p95", num(r.latency().p95()) + " ms", "")).append(tile("send p99", num(r.latency().p99()) + " ms", ""));
        }
        b.append("</div>");
        if (!d.series().isEmpty()) b.append("<section><h2>Rate</h2>").append(chart(d.series())).append("</section>");

        if (!r.checks().isEmpty()) {
            List<List<Object>> rows = new ArrayList<>();
            for (Runs.Check c : r.checks()) {
                String result = c.invalid() ? c.arrived() + " rejected as expected" + (c.accepted() > 0 ? ", " + c.accepted() + " accepted although invalid" : "")
                        + (c.unrecognised() > 0 ? ", " + c.unrecognised() + " accepted, unrecognisable" : "")
                        : c.arrived() + " arrived" + (c.rejected() > 0 ? ", " + c.rejected() + " valid but rejected" : "");
                if (c.misrouted() > 0) result += ", " + c.misrouted() + " misrouted";
                if (c.missing() > 0) result += ", " + c.missing() + " missing";
                rows.add(List.of(c.template() + (c.invalid() ? " (invalid)" : ""), c.expected(), c.sent(), result, c.medianMs() == null ? "" : c.medianMs() + " ms",
                        c.arrived() == c.sent() ? "PASS" : "FAIL"));
            }
            b.append(table("Arrivals", List.of("Template", "Expected on", "Sent", "Result", "Median", ""), rows));
        }
        if (!r.faults().isEmpty()) {
            List<List<Object>> rows = new ArrayList<>();
            r.faults().forEach((kind, n) -> rows.add(List.of(kind, n)));
            b.append(table("Invalid messages", List.of("Kind", "Messages"), rows));
        }
        if (!r.errors().isEmpty()) {
            List<List<Object>> rows = new ArrayList<>();
            for (Stats.ErrorCount e : r.errors()) rows.add(List.of(e.message(), e.count(), SHOWN.format(e.lastAt())));
            b.append(table("Send errors", List.of("Error", "Count", "Last"), rows));
        }
        for (Observer.View w : d.watches()) {
            if (w.count() == 0 && w.tables().isEmpty()) continue;
            b.append("<section><h2>").append(esc(w.connection() + " / " + w.destination())).append("</h2><p class=\"sub\">")
                    .append(String.format(Locale.ROOT, "%,d", w.count())).append(" arrived").append(w.lastAt() == null ? "" : ", the last at " + esc(SHOWN.format(w.lastAt()))).append("</p>");
            for (Observer.Table t : w.tables()) {
                b.append("<table><thead><tr><th>").append(esc(t.name())).append("</th><th style=\"text-align:right\">Arrivals</th></tr></thead><tbody>");
                for (Observer.Count c : t.values()) b.append("<tr><td>").append(esc(c.value())).append("</td><td class=\"n\">").append(String.format(Locale.ROOT, "%,d", c.count())).append("</td></tr>");
                b.append("</tbody></table><br>");
            }
            b.append("</section>");
        }
        List<List<Object>> plan = new ArrayList<>();
        plan.add(List.of("Templates", String.join(", ", r.templates())));
        plan.add(List.of(r.targets().size() == 1 ? "Target" : "Targets (" + ("all".equals(r.mode()) ? "copy to all" : "spread") + ")",
                String.join(", ", r.targets().stream().map(t -> t.connection() + " / " + t.destination()).toList())));
        if (r.count() > 0) plan.add(List.of("Messages", String.format(Locale.ROOT, "%,d", r.count())));
        if (r.durationSeconds() != null && r.durationSeconds() > 0) plan.add(List.of("Duration", span(r.durationSeconds() * 1000L)));
        plan.add(List.of("Rate", r.rate() == null ? "as fast as possible" : num(r.rate()) + " a second"
                + (r.rampUpSeconds() != null && r.rampUpSeconds() > 0 ? ", reached over " + span(r.rampUpSeconds() * 1000L) : "")));
        plan.add(List.of("Senders", String.valueOf(r.senders())));
        plan.add(List.of("Seed", String.valueOf(r.seed())));
        b.append(table("Plan", List.of("", ""), plan));
        String version = Reports.class.getPackage().getImplementationVersion();
        return b.append("<footer>msgforge").append(version == null ? "" : " " + esc(version)).append(" · no message bodies are part of this report</footer></main></body></html>\n").toString();
    }
}
