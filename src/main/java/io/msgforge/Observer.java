package io.msgforge;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Counts what arrives on one destination, a reject or dead-letter queue say, and sorts it into buckets by values
 * pulled from each arrival. A run uses one per destination it watches; a monitor is one that the user starts and stops.
 *
 * What arrives is somebody else's data, and a bucket's regular expression is typed by a user: neither is trusted.
 * Of an arrival only the bucket values, its size and the time are kept, and the newest few bodies cut short as a
 * sample. Nothing here is logged. Every table is capped, and an expression gets a limited time on a limited text.
 */
final class Observer {

    static final int MAX_BUCKETS = 5, MAX_VALUES = 50, MAX_VALUE_TEXT = 80, MAX_EXPRESSION = 200, RECENT = 50, SAMPLES = 3, SAMPLE_TEXT = 2000;
    // ponytail: an expression sees the first 64 KB of a body and JSON is parsed up to 1 MB; a value further in is "(none)".
    static final int MAX_SCAN = 65_536, MAX_JSON = 1_000_000;
    static final long REGEX_NANOS = 50_000_000L;
    static final String NONE = "(none)", OTHER = "(other)", TOO_SLOW = "(too slow)";

    /**
     * How to pull one value out of an arrival. Exactly one of the three is set: pointer is a JSON pointer into the
     * body (/reason), regex a regular expression whose first group is the value (the whole match when it has no
     * group), header the name of a header. name is the table's title; without one the expression is.
     */
    public record Bucket(String name, String pointer, String regex, String header) {}

    public record Count(String value, long count) {}

    public record Table(String name, List<Count> values) {}

    /** One arrival: when, its bucket values in the order of the tables, the number of the sent message it was recognised as (or null), its size. */
    public record Recent(Instant at, List<String> values, Long message, int bytes) {}

    /** What the run list shows: counts only. rate is arrivals a second over the last 10 seconds. */
    public record Summary(String connection, String destination, long count, double rate, Instant lastAt) {}

    public record View(String connection, String destination, long count, double rate, Instant lastAt, Instant since,
                       List<Table> tables, List<Recent> recent, List<String> samples) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String connection, destination;
    private final List<Extractor> extractors = new ArrayList<>();
    private final List<Map<String, long[]>> tables = new ArrayList<>();
    private final Deque<Recent> recent = new ArrayDeque<>();
    private final Deque<String> samples = new ArrayDeque<>();
    private final Stats.Window window = new Stats.Window();
    private long count, sinceNanos = System.nanoTime();
    private Instant lastAt, since = Instant.now();

    Observer(String connection, String destination, List<Bucket> buckets) {
        this.connection = connection;
        this.destination = destination;
        for (Bucket b : check(buckets)) {
            extractors.add(new Extractor(b));
            tables.add(new LinkedHashMap<>());
        }
    }

    /** Refuses buckets that could never work, with a message that says which and why. Returns them, never null. */
    static List<Bucket> check(List<Bucket> buckets) {
        if (buckets == null) return List.of();
        if (buckets.size() > MAX_BUCKETS) throw new IllegalArgumentException("At most " + MAX_BUCKETS + " buckets for one destination");
        for (Bucket b : buckets) {
            if (b == null) throw new IllegalArgumentException("A bucket is empty: it needs a pointer, a regex or a header");
            int given = (blank(b.pointer()) ? 0 : 1) + (blank(b.regex()) ? 0 : 1) + (blank(b.header()) ? 0 : 1);
            if (given != 1) throw new IllegalArgumentException("A bucket needs exactly one of pointer, regex and header");
            String expression = !blank(b.pointer()) ? b.pointer() : !blank(b.regex()) ? b.regex() : b.header();
            if (expression.length() > MAX_EXPRESSION || b.name() != null && b.name().length() > MAX_VALUE_TEXT) {
                throw new IllegalArgumentException("A bucket's expression can be " + MAX_EXPRESSION + " characters long and its name " + MAX_VALUE_TEXT);
            }
            try {
                if (!blank(b.pointer())) JsonPointer.compile(b.pointer().trim());
                if (!blank(b.regex())) Pattern.compile(b.regex());
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("The bucket expression " + b.regex() + " is not a regular expression: " + e.getDescription());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("The bucket pointer " + b.pointer() + " is not a JSON pointer: it starts with / like /reason or /error/code");
            }
        }
        return buckets;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** One bucket, ready to use. An expression that runs out of time three times is not asked again. */
    private static final class Extractor {
        final String name, header;
        final JsonPointer pointer;
        final Pattern regex;
        int slow;

        Extractor(Bucket b) {
            pointer = blank(b.pointer()) ? null : JsonPointer.compile(b.pointer().trim());
            regex = blank(b.regex()) ? null : Pattern.compile(b.regex());
            header = blank(b.header()) ? null : b.header().trim();
            name = !blank(b.name()) ? b.name().trim() : pointer != null ? b.pointer().trim() : regex != null ? b.regex() : header;
        }

        String value(TransportType.Arrival a, JsonNode json) {
            String found = null;
            if (header != null) {
                found = a.headers().get(header);
                if (found == null) {
                    for (Map.Entry<String, String> h : a.headers().entrySet()) if (h.getKey().equalsIgnoreCase(header)) found = h.getValue();
                }
            } else if (pointer != null) {
                JsonNode at = json == null ? null : json.at(pointer);
                if (at != null && !at.isMissingNode() && !at.isNull()) found = at.isValueNode() ? at.asText() : at.isArray() ? "[…]" : "{…}";
            } else {
                if (slow >= 3) return TOO_SLOW;
                try {
                    String text = a.body().length() > MAX_SCAN ? a.body().substring(0, MAX_SCAN) : a.body();
                    Matcher m = regex.matcher(new Timed(text, System.nanoTime() + REGEX_NANOS));
                    if (m.find()) found = m.groupCount() > 0 ? m.group(1) : m.group();
                    slow = 0;
                } catch (TimedOut | StackOverflowError e) {   // an expression that backtracks without end, or nests too deep
                    slow++;
                    return TOO_SLOW;
                }
            }
            if (found == null || found.isBlank()) return NONE;
            found = found.strip();
            return found.length() > MAX_VALUE_TEXT ? found.substring(0, MAX_VALUE_TEXT) + "…" : found;
        }
    }

    private static final class TimedOut extends RuntimeException {
        TimedOut() {
            super(null, null, false, false);
        }
    }

    /** The text as the regex engine reads it, character by character, with a look at the clock every so often. */
    private static final class Timed implements CharSequence {
        private final CharSequence text;
        private final long deadline;
        private int reads;

        Timed(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 1023) == 0 && System.nanoTime() > deadline) throw new TimedOut();
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Timed(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /** message is the number of the sent message this arrival was recognised as, or null. */
    void arrived(TransportType.Arrival a, Long message) {
        JsonNode json = null;
        if (extractors.stream().anyMatch(e -> e.pointer != null) && a.body().length() <= MAX_JSON) {
            try {
                json = JSON.readTree(a.body());
            } catch (Exception | StackOverflowError notJson) {
                // then every pointer finds nothing
            }
        }
        long now = System.nanoTime();
        synchronized (this) {
            List<String> values = new ArrayList<>(extractors.size());
            for (int i = 0; i < extractors.size(); i++) {
                String value = extractors.get(i).value(a, json);
                Map<String, long[]> table = tables.get(i);
                // a full table counts new values together: the values come from outside and could be endless
                if (!table.containsKey(value) && table.size() >= MAX_VALUES) value = OTHER;
                table.computeIfAbsent(value, v -> new long[1])[0]++;
                values.add(value);
            }
            count++;
            lastAt = Instant.now();
            window.add(now, 1);
            recent.addFirst(new Recent(lastAt, values, message, utf8Length(a.body())));
            if (recent.size() > RECENT) recent.removeLast();
            samples.addFirst(a.body().length() > SAMPLE_TEXT ? a.body().substring(0, SAMPLE_TEXT) + "…" : a.body());
            if (samples.size() > SAMPLES) samples.removeLast();
        }
    }

    private static int utf8Length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += c < 0x80 ? 1 : c < 0x800 ? 2 : Character.isSurrogate(c) ? 2 : 3;   // a surrogate pair is 4 bytes, 2 per half
        }
        return n;
    }

    synchronized void reset() {
        tables.forEach(Map::clear);
        recent.clear();
        samples.clear();
        window.clear();
        count = 0;
        lastAt = null;
        since = Instant.now();
        sinceNanos = System.nanoTime();
        extractors.forEach(e -> e.slow = 0);
    }

    synchronized long count() {
        return count;
    }

    synchronized Summary summary() {
        long now = System.nanoTime();
        return new Summary(connection, destination, count, window.rate(now, 10, now - sinceNanos), lastAt);
    }

    /** samples: whether to include the newest bodies. A report leaves them out. */
    synchronized View view(boolean withSamples) {
        List<Table> out = new ArrayList<>();
        for (int i = 0; i < extractors.size(); i++) {
            List<Count> values = new ArrayList<>();
            tables.get(i).forEach((value, n) -> values.add(new Count(value, n[0])));
            values.sort((a, b) -> Long.compare(b.count(), a.count()));
            out.add(new Table(extractors.get(i).name, values));
        }
        long now = System.nanoTime();
        return new View(connection, destination, count, window.rate(now, 10, now - sinceNanos), lastAt, since, out,
                List.copyOf(recent), withSamples ? List.copyOf(samples) : List.of());
    }
}
