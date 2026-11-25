package io.msgforge;

import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /metrics in the Prometheus text format, for watching a soak test from outside: what the process has sent since
 * it started, how runs ended, what arrived on watched destinations, and how fast it is sending now.
 *
 * Numbers and names only. No message content, no setting of any connection, nothing read from the environment:
 * a destination is labelled as it was typed (${MSGFORGE_QUEUE} stays that text). Label values come from users, and
 * are escaped the way the format asks.
 */
// ponytail: written by hand, a dozen series. If histograms per destination or JVM metrics are wanted, that is the day for Micrometer.
@RestController
public class Metrics {

    private final Runs runs;
    private final Monitors monitors;

    public Metrics(Runs runs, Monitors monitors) {
        this.runs = runs;
        this.monitors = monitors;
    }

    @GetMapping(value = "/metrics", produces = "text/plain;version=0.0.4;charset=UTF-8")
    public String metrics() {
        Runs.Totals totals = runs.totals();
        List<Runs.View> list = runs.list();
        StringBuilder out = new StringBuilder();
        counter(out, "msgforge_messages_sent_total", "Messages sent since msgforge started.", totals.sent.sum());
        counter(out, "msgforge_messages_failed_total", "Messages a send failed for since msgforge started.", totals.failed.sum());
        counter(out, "msgforge_messages_invalid_total", "Messages sent invalid on purpose since msgforge started.", totals.invalid.sum());

        head(out, "msgforge_runs_total", "Runs that have ended, by how: pass and fail are verdicts, done is a run with nothing to verify.", "counter");
        for (String outcome : List.of("pass", "fail", "done", "failed", "cancelled")) {
            var n = totals.runs.get(outcome);
            out.append("msgforge_runs_total{outcome=\"").append(outcome).append("\"} ").append(n == null ? 0 : n.sum()).append('\n');
        }
        List<Runs.View> active = list.stream().filter(r -> r.finishedAt() == null).toList();
        gauge(out, "msgforge_runs_active", "Runs that are sending or waiting for arrivals now.", active.size());
        gauge(out, "msgforge_send_rate", "Messages a second going out now, over all active runs.",
                active.stream().filter(r -> r.status() == Runs.Status.RUNNING).mapToDouble(Runs.View::actualRate).sum());
        gauge(out, "msgforge_target_rate", "Messages a second the active runs' plans ask for now. Runs that send as fast as they can add nothing.",
                active.stream().filter(r -> r.status() == Runs.Status.RUNNING && r.targetRate() != null).mapToDouble(Runs.View::targetRate).sum());
        gauge(out, "msgforge_senders", "Sender threads of the active runs.", active.stream().mapToInt(Runs.View::senders).sum());

        head(out, "msgforge_arrivals_total", "Messages that arrived on a destination a run watched (source run, since start) or a monitor is on (source monitor, since its reset).", "counter");
        totals.arrivals.forEach((where, n) -> arrivals(out, where.connection(), where.destination(), "run", n.sum()));
        for (Monitors.View m : monitors.list()) arrivals(out, m.watch().connection(), m.watch().destination(), "monitor", m.watch().count());
        return out.toString();
    }

    private static void arrivals(StringBuilder out, String connection, String destination, String source, long n) {
        out.append("msgforge_arrivals_total{connection=\"").append(label(connection)).append("\",destination=\"").append(label(destination))
                .append("\",source=\"").append(source).append("\"} ").append(n).append('\n');
    }

    private static void head(StringBuilder out, String name, String help, String type) {
        out.append("# HELP ").append(name).append(' ').append(help).append("\n# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void counter(StringBuilder out, String name, String help, long value) {
        head(out, name, help, "counter");
        out.append(name).append(' ').append(value).append('\n');
    }

    private static void gauge(StringBuilder out, String name, String help, double value) {
        head(out, name, help, "gauge");
        out.append(name).append(' ').append(value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.3f", value)).append('\n');
    }

    /** A label value: backslash, quote and line feed are the three characters the format has an escape for. Other control characters are dropped. */
    static String label(String value) {
        StringBuilder b = new StringBuilder();
        // cut before escaping, so the cut can never fall inside an escape
        value.codePoints().limit(200).forEach(c -> {
            if (c == '\\') b.append("\\\\");
            else if (c == '"') b.append("\\\"");
            else if (c == '\n') b.append("\\n");
            else if (c >= 0x20 && c != 0x7f) b.appendCodePoint(c);
        });
        return b.toString();
    }
}
