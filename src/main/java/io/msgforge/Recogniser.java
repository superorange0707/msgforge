package io.msgforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Turns a real message into a template by finding the values that have to differ from one message to the next
 * and putting placeholders in their place: ids, timestamps, dates, account numbers, what a plugin's generator
 * says is its own, and, when asked to, whole values that are one of a pack field's choices.
 *
 * It is deliberately timid. In a routing test the values that decide the route (a currency, a country, an
 * amount, a type code) must stay as the sample has them, so nothing is guessed. A date without a time is not
 * touched, nor a number, except fourteen digits that read as a moment. A pack field replaces a value only on
 * request, and only when the value is the whole text of an element, attribute, JSON string or delimited cell.
 * Everything else in the document is left byte for byte.
 */
@Component
public class Recogniser {

    /** field is the pack field the value was a choice of, null for everything else. */
    public record Replacement(int line, String value, String placeholder, String field) {}

    public record Result(String template, List<Replacement> replacements) {}

    /** same: whether a second occurrence of the value has to render the same (an id, an account) or may be generated anew (a time). */
    private record Found(int start, int end, String value, String expression, boolean same, String field) {}

    private static final Pattern UUID = Pattern.compile("(?<![0-9A-Za-z-])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9A-Za-z-])");
    // with a letter in it: 32 digits are a number, and numbers are not guessed
    private static final Pattern HEX32 = Pattern.compile("(?<![0-9A-Za-z])(?=[0-9]*[a-fA-F])[0-9a-fA-F]{32}(?![0-9A-Za-z])");
    // a date and a time; a date on its own is a version, a birthday or part of a name far more often than it is "now"
    private static final Pattern TIME = Pattern.compile("(?<![0-9A-Za-z])(\\d{4})-(\\d{2})-(\\d{2})"
            + "([T ])(\\d{2}):(\\d{2}):(\\d{2})(?:([.,])(\\d{1,9}))?(Z|[+-]\\d{2}:\\d{2}|[+-]\\d{4})?(?![0-9])");
    // ponytail: fourteen digits that read as a moment of this century. A fourteen-digit id that happens to do so is taken for one too.
    private static final Pattern COMPACT = Pattern.compile("(?<![0-9A-Za-z.])(20\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(?![0-9A-Za-z]|\\.\\d)");
    private static final Pattern IBAN = Pattern.compile("(?<![0-9A-Za-z])[A-Z]{2}\\d{2}[A-Z0-9]{10,30}(?![0-9A-Za-z])");
    // What a value is called where it stands: <Name>value, name="value, "name": "value
    private static final Pattern CALLED = Pattern.compile(
            "(?:<(?:[\\w.-]+:)?([\\w.-]+)(?:\\s[^<>]*)?>|([\\w.-]+)\\s*=\\s*[\"']?|\"([^\"\\\\]+)\"\\s*:\\s*\"?)\\s*$");

    private final TemplateEngine engine;

    public Recogniser(TemplateEngine engine) {
        this.engine = engine;
    }

    /** @param fields the fields of the pack the template is for; empty when it is for none */
    /**
     * @param fields    the fields of the pack the template is for; empty when it is for none. They are always known
     *                  as placeholders and never used as names.
     * @param useFields whether a whole value that is one of a field's choices becomes that field. Off for an import:
     *                  a sample that stands for "the EUR route" must still do so afterwards. On when the user asks.
     */
    public Result recognise(String text, Map<String, String> fields, boolean useFields) {
        TreeMap<Integer, Integer> taken = new TreeMap<>();   // start -> end of what is settled: placeholders, and values already recognised
        List<Integer> escapes = new ArrayList<>();           // where a {{ that is not a placeholder of ours needs its backslash
        Set<String> names = new HashSet<>();                 // names bound in the text already
        List<Found> found = new ArrayList<>();

        // What is a placeholder already stays. A {{…}} that only looks like one (another template language's) would
        // fail to render as "unknown placeholder", so it is escaped. Either way a second pass finds nothing to do.
        for (Matcher m = TemplateEngine.TOKEN.matcher(text); m.find();) {
            taken.put(m.start(), m.end());
            if (m.group(4) != null) continue;
            if (m.group(1) != null) names.add(m.group(1));
            if (!names.contains(m.group(2)) && !fields.containsKey(m.group(2)) && !engine.knows(m.group(2))) escapes.add(m.start());
        }

        // Whole values first: what the pack's fields and the plugins know about takes precedence over the general shapes.
        Map<String, String> choices = useFields ? choices(fields) : Map.of();
        for (int[] span : wholeValues(text)) {
            String value = text.substring(span[0], span[1]);
            String field = choices.get(value), expression = field != null ? field : engine.recognised(value);
            if (expression != null) add(text, found, taken, new Found(span[0], span[1], value, expression, true, field));
        }

        each(text, UUID, found, taken, v -> "uuid", true);
        each(text, HEX32, found, taken, v -> "id", true);
        for (Matcher m = TIME.matcher(text); m.find();) {
            String expression = time(m);
            if (expression != null) add(text, found, taken, new Found(m.start(), m.end(), m.group(), expression, false, null));
        }
        for (Matcher m = COMPACT.matcher(text); m.find();) {
            if (moment(m, 2)) add(text, found, taken, new Found(m.start(), m.end(), m.group(), "now yyyyMMddHHmmss", false, null));
        }
        each(text, IBAN, found, taken, v -> TemplateEngine.ibanCountry(v) == null ? null : "iban " + TemplateEngine.ibanCountry(v), true);
        found.sort(Comparator.comparingInt(Found::start));

        // A value that occurs more than once keeps meaning one thing: generated once under a name, repeated by that name.
        Map<String, Integer> times = new HashMap<>();
        for (Found f : found) if (f.same()) times.merge(f.value(), 1, Integer::sum);
        Map<String, String> named = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder();
        List<Replacement> replacements = new ArrayList<>();
        int at = 0, escape = 0, line = 1, counted = 0;
        for (Found f : found) {
            String placeholder = "{{" + f.expression() + "}}";
            if (f.same() && times.get(f.value()) > 1) {
                if (named.containsKey(f.value())) {
                    placeholder = "{{" + named.get(f.value()) + "}}";
                } else {
                    String name = name(text, f.start(), fields, names);
                    named.put(f.value(), name);
                    placeholder = "{{" + name + "=" + f.expression() + "}}";
                }
            }
            for (; escape < escapes.size() && escapes.get(escape) < f.start(); escape++) {
                out.append(text, at, escapes.get(escape)).append('\\');
                at = escapes.get(escape);
            }
            out.append(text, at, f.start()).append(placeholder);
            at = f.end();
            for (; counted < f.start(); counted++) if (text.charAt(counted) == '\n') line++;   // counted on from the last hit, not from the top
            replacements.add(new Replacement(line, f.value(), placeholder, f.field()));
        }
        for (; escape < escapes.size(); escape++) {
            out.append(text, at, escapes.get(escape)).append('\\');
            at = escapes.get(escape);
        }
        return new Result(out.append(text, at, text.length()).toString(), replacements);
    }

    /**
     * Where the whole values are, as [start, end) without the white space around them: the text of an XML element
     * and of an attribute; a JSON string that is not a key; a cell of a delimited line, or what follows the = in
     * key=value. Which it is is told from the content, not from a file name: a sample may be pasted, or misnamed.
     * Scanned by hand, character by character: a pattern for "a quoted string" recurses once per character in
     * java.util.regex and overflowed the stack on a string of a few thousand.
     */
    static List<int[]> wholeValues(String text) {
        List<int[]> out = new ArrayList<>();
        String head = text.stripLeading();
        int n = text.length(), i = 0;
        if (head.startsWith("<")) {
            while (i < n) {
                if (text.startsWith("<!--", i)) {                       // not a value: a comment
                    i = closing(text, "-->", i);
                } else if (text.startsWith("<![CDATA[", i)) {            // nor a section that is to be taken literally
                    i = closing(text, "]]>", i);
                } else if (text.charAt(i) == '<') {                      // a tag: its quoted attribute values, in which < and > are just text
                    for (i++; i < n && text.charAt(i) != '>'; i++) {
                        char quote = text.charAt(i);
                        if (quote != '"' && quote != '\'') continue;
                        int end = text.indexOf(quote, i + 1);
                        if (end < 0) return out;
                        span(text, i + 1, end, out);
                        i = end;
                    }
                    i++;
                } else {                                                 // the text up to the next tag
                    int end = text.indexOf('<', i);
                    if (end < 0) end = n;
                    span(text, i, end, out);
                    i = end;
                }
            }
        } else if (head.startsWith("{") || head.startsWith("[")) {
            while ((i = text.indexOf('"', i)) >= 0) {
                int end = i + 1;
                while (end < n && text.charAt(end) != '"') end += text.charAt(end) == '\\' ? 2 : 1;
                if (end >= n) break;
                int after = end + 1;
                while (after < n && Character.isWhitespace(text.charAt(after))) after++;
                if (after >= n || text.charAt(after) != ':') span(text, i + 1, end, out);   // followed by a colon it is a key
                i = end + 1;
            }
        } else {
            while (i < n) {
                int from = i;
                while (from < n && text.charAt(from) == ' ') from++;
                int end;
                if (from < n && text.charAt(from) == '"' && (end = text.indexOf('"', from + 1)) >= 0) {
                    span(text, from + 1, end, out);                      // a quoted cell is one value, commas and all
                    i = end + 1;
                    while (i < n && ",;|\t\r\n".indexOf(text.charAt(i)) < 0) i++;
                } else {
                    for (end = from; end < n && ",;|\t\r\n".indexOf(text.charAt(end)) < 0; end++) {
                        if (text.charAt(end) == '=') from = end + 1;      // key=value: the value
                    }
                    span(text, from, end, out);
                    i = end;
                }
                i++;
            }
        }
        return out;
    }

    private static int closing(String text, String mark, int from) {
        int end = text.indexOf(mark, from);
        return end < 0 ? text.length() : end + mark.length();
    }

    private static void span(String text, int start, int end, List<int[]> out) {
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (end > start) out.add(new int[] {start, end});
    }

    /** value -> field, for the fields that are one {{pick …}}. A value two fields offer is left out: nobody can say which is meant. */
    private static Map<String, String> choices(Map<String, String> fields) {
        Map<String, String> out = new HashMap<>();
        Set<String> twice = new HashSet<>();
        fields.forEach((field, definition) -> {
            Matcher m = TemplateEngine.TOKEN.matcher(definition.strip());
            if (!m.matches() || m.group(1) != null || !"pick".equals(m.group(2))) return;
            for (String choice : new HashSet<>(TemplateEngine.args(m.group(3)))) {
                if (out.put(choice, field) != null) twice.add(choice);
            }
        });
        out.keySet().removeAll(twice);
        return out;
    }

    private static void each(String text, Pattern shape, List<Found> found, TreeMap<Integer, Integer> taken, Function<String, String> expression, boolean same) {
        for (Matcher m = shape.matcher(text); m.find();) {
            String e = expression.apply(m.group());
            if (e != null) add(text, found, taken, new Found(m.start(), m.end(), m.group(), e, same, null));
        }
    }

    /**
     * Takes the value unless part of it is settled already, or it stands right after a backslash: a backslash before
     * {{ is how a template writes a literal {{, so C:\logs\<id>\app.log would render with the placeholder's text in it.
     */
    private static void add(String text, List<Found> found, TreeMap<Integer, Integer> taken, Found f) {
        // What is taken does not overlap itself, so only the stretch that starts last before this one ends can reach into it.
        Map.Entry<Integer, Integer> before = taken.lowerEntry(f.end());
        if (before != null && before.getValue() > f.start()) return;
        if (f.start() > 0 && text.charAt(f.start() - 1) == '\\') return;
        found.add(f);
        taken.put(f.start(), f.end());
    }

    /** The placeholder that prints the current time in the shape the sample has, or null when the digits are no date. */
    private static String time(Matcher m) {
        int month = Integer.parseInt(m.group(2)), day = Integer.parseInt(m.group(3));
        if (month < 1 || month > 12 || day < 1 || day > 31 || !moment(m, 5)) return null;
        String zone = m.group(10), fraction = m.group(9);
        if (m.group(4).equals("T") && fraction == null && "Z".equals(zone)) return "now";
        String pattern = "yyyy-MM-dd" + (m.group(4).equals("T") ? "'T'" : " ") + "HH:mm:ss"
                + (fraction == null ? "" : m.group(8) + "S".repeat(fraction.length()))
                + (zone == null ? "" : zone.equals("Z") ? "XXX" : zone.contains(":") ? "xxx" : "xx");
        return pattern.contains(" ") ? "now \"" + pattern + "\"" : "now " + pattern;
    }

    /** Whether the three groups from first are an hour, a minute and a second, and (for the compact form) the two before them a month and a day. */
    private static boolean moment(Matcher m, int first) {
        if (first == 2) {
            int month = Integer.parseInt(m.group(2)), day = Integer.parseInt(m.group(3));
            if (month < 1 || month > 12 || day < 1 || day > 31) return false;
            first = 4;
        }
        return Integer.parseInt(m.group(first)) < 24 && Integer.parseInt(m.group(first + 1)) < 60 && Integer.parseInt(m.group(first + 2)) < 60;
    }

    /** A name for a repeated value: what it is called where it first stands, if that can be a name and is free; otherwise id1, id2… */
    private String name(String text, int start, Map<String, String> fields, Set<String> names) {
        Matcher m = CALLED.matcher(text).region(Math.max(0, start - 300), start);
        String name = m.find() ? m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3) : null;
        for (int n = 1; name == null || !name.matches("[A-Za-z_]\\w*") || names.contains(name) || fields.containsKey(name) || engine.knows(name); n++) {
            name = "id" + n;
        }
        names.add(name);
        return name;
    }
}
