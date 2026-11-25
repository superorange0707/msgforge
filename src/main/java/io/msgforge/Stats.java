package io.msgforge;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a run measures while it sends, in memory that does not grow with the run: counters, the send times as a
 * histogram, a series over time that halves its resolution when it is full, and the distinct errors with counts.
 * One lock guards all of it; every sender thread reports each message here.
 */
// ponytail: one lock per run. At some hundred thousand messages a second the senders would queue for it;
// give each sender its own Stats and add them up when read if that day comes.
final class Stats {

    static final int MAX_POINTS = 600, MAX_ERRORS = 20, MAX_ERROR_TEXT = 300;
    /** A run stops when this many sends in a row fail, or when sends have done nothing but fail for this long (and at least twice). */
    static final int MAX_STREAK = 100;
    static final long MAX_STREAK_NANOS = 10_000_000_000L;
    static final String OTHER_ERRORS = "(other errors)";

    /** Send times: the 50th, 95th and 99th percentile and the longest, in milliseconds. */
    public record Latency(double p50, double p95, double p99, double max) {}

    public record ErrorCount(String message, long count, Instant lastAt) {}

    /**
     * One stretch of the run: it starts t seconds in and covers `seconds`. target is the rate the plan asked for
     * then (null for a run that sends as fast as it can); the rest count messages, so sent / seconds is the rate.
     */
    public record Point(long t, double seconds, Double target, long sent, long failed, long invalid) {}

    private long sent, failed, invalid, maxNanos;
    private int streak;
    private long streakSince;
    private final long[] histogram = new long[488];
    private final Window window = new Window();
    private final Map<String, long[]> errors = new LinkedHashMap<>();   // message -> count, last time (epoch ms)
    private String lastError;
    // the series: point i covers seconds [i * step, (i + 1) * step) of the run
    private int step = 1, points;
    private final long[] seriesSent = new long[MAX_POINTS], seriesFailed = new long[MAX_POINTS], seriesInvalid = new long[MAX_POINTS];

    /** A message went out. elapsed is the time since the run began, took the time inside send. */
    synchronized void sent(long now, long elapsed, long took, boolean wasInvalid) {
        sent++;
        if (wasInvalid) invalid++;
        streak = 0;
        maxNanos = Math.max(maxNanos, took);
        histogram[bucket(took)]++;
        window.add(now, 1);
        int at = point(elapsed);
        seriesSent[at]++;
        if (wasInvalid) seriesInvalid[at]++;
    }

    /**
     * A send failed. Returns why the run should now stop, or null to go on: one refused message is a result to
     * count, a connection that only fails is not worth a million more attempts.
     */
    synchronized String failed(long now, long elapsed, String error) {
        failed++;
        seriesFailed[point(elapsed)]++;
        String text = error.length() > MAX_ERROR_TEXT ? error.substring(0, MAX_ERROR_TEXT) + "…" : error;
        lastError = text;
        long[] known = errors.get(text);
        if (known == null && errors.size() >= MAX_ERRORS) known = errors.computeIfAbsent(OTHER_ERRORS, k -> new long[2]);
        if (known == null) errors.put(text, known = new long[2]);
        known[0]++;
        known[1] = System.currentTimeMillis();
        if (streak++ == 0) streakSince = now;
        if (streak >= MAX_STREAK) return "Stopped after " + streak + " sends in a row failed: " + text;
        if (streak > 1 && now - streakSince >= MAX_STREAK_NANOS) {
            return "Stopped after every send failed for " + (now - streakSince) / 1_000_000_000L + " seconds: " + text;
        }
        return null;
    }

    private int point(long elapsed) {
        long second = Math.max(0, elapsed / 1_000_000_000L);
        while (second / step >= MAX_POINTS) {   // full: two neighbours become one, and each point covers twice as long
            for (int i = 0; i < MAX_POINTS / 2; i++) {
                seriesSent[i] = seriesSent[2 * i] + seriesSent[2 * i + 1];
                seriesFailed[i] = seriesFailed[2 * i] + seriesFailed[2 * i + 1];
                seriesInvalid[i] = seriesInvalid[2 * i] + seriesInvalid[2 * i + 1];
            }
            for (int i = MAX_POINTS / 2; i < MAX_POINTS; i++) seriesSent[i] = seriesFailed[i] = seriesInvalid[i] = 0;
            step *= 2;
            points = (points + 1) / 2;
        }
        int at = (int) (second / step);
        points = Math.max(points, at + 1);
        return at;
    }

    synchronized long sent() {
        return sent;
    }

    synchronized long failed() {
        return failed;
    }

    synchronized long invalid() {
        return invalid;
    }

    synchronized String lastError() {
        return lastError;
    }

    /** Messages a second over the last two seconds, or over the run so far when it is younger than that. */
    synchronized double rate(long now, long elapsed) {
        return window.rate(now, 2, elapsed);
    }

    synchronized List<ErrorCount> errors() {
        List<ErrorCount> out = new ArrayList<>();
        errors.forEach((message, n) -> out.add(new ErrorCount(message, n[0], Instant.ofEpochMilli(n[1]))));
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }

    /** Null until something was sent. */
    synchronized Latency latency() {
        if (sent == 0) return null;
        return new Latency(percentile(.50), percentile(.95), percentile(.99), ms(maxNanos));
    }

    /** elapsed is how long the run has sent for: the last point ends there, not at the end of its step. */
    synchronized List<Point> series(long elapsed, Runs.Plan plan) {
        List<Point> out = new ArrayList<>(points);
        double end = elapsed / 1e9;
        for (int i = 0; i < points; i++) {
            long t = (long) i * step;
            double seconds = Math.max(0.001, Math.min(step, end - t));
            if (i < points - 1 || end - t >= step) seconds = step;
            Double target = plan.rate() <= 0 ? null : round((plan.dueBy(t + seconds) - plan.dueBy(t)) / seconds);
            out.add(new Point(t, round(seconds), target, seriesSent[i], seriesFailed[i], seriesInvalid[i]));
        }
        return out;
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 1000.0) / 1000.0;
    }

    // ponytail: the histogram's buckets are an eighth of a power of two wide, so a percentile is right to about 6%.
    private double percentile(double p) {
        long want = (long) Math.ceil(p * sent), seen = 0;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (seen >= want) return Math.min(ms(middle(i)), ms(maxNanos));
        }
        return ms(maxNanos);
    }

    static int bucket(long nanos) {
        if (nanos < 16) return (int) Math.max(0, nanos);
        int e = 63 - Long.numberOfLeadingZeros(nanos);
        return (e - 3) * 8 + (int) ((nanos >>> (e - 3)) & 7) + 8;
    }

    private static long middle(int bucket) {
        if (bucket < 16) return bucket;
        int e = (bucket - 8) / 8 + 3, sub = (bucket - 8) % 8;
        return ((8L + sub) << (e - 3)) + (1L << (e - 3)) / 2;
    }

    /** Counts per second for the last few seconds, to say how fast something is happening now. Not thread-safe: its owner locks. */
    static final class Window {
        private static final int SLOTS = 16;
        private final long[] second = new long[SLOTS], count = new long[SLOTS];

        void add(long now, long n) {
            long s = Math.floorDiv(now, 1_000_000_000L);   // nanoTime may be negative
            int i = (int) Math.floorMod(s, (long) SLOTS);
            if (second[i] != s) {
                second[i] = s;
                count[i] = 0;
            }
            count[i] += n;
        }

        /** Per second over the last `seconds` (at most 15), or over `age` nanoseconds when that is shorter. */
        double rate(long now, int seconds, long age) {
            long s = Math.floorDiv(now, 1_000_000_000L), sum = 0;
            for (int i = 0; i < SLOTS; i++) if (second[i] > s - seconds && second[i] <= s) sum += count[i];
            // the seconds counted are whole ones and the part of this one that has passed
            double covered = seconds - 1 + Math.floorMod(now, 1_000_000_000L) / 1e9;
            return round(sum / Math.max(0.05, Math.min(covered, age / 1e9)));
        }

        void clear() {
            java.util.Arrays.fill(count, 0);
        }
    }
}
