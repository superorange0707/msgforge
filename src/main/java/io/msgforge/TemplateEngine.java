package io.msgforge;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fills {{placeholders}} in a message template.
 *
 * {{name args...}}        a built-in generator, or a field defined in the pack
 * {{var=name args...}}    same, and remembers the value so a later {{var}} repeats it in this message
 * \{{                     a literal {{, for text that is not a placeholder
 */
@Component
public class TemplateEngine {

    // ponytail: at most 500 arguments. The engine matches each argument one stack frame deeper, so an unclosed {{name
    // followed by thousands of words overflowed the stack; past the limit the text is no placeholder and is sent as it is.
    static final Pattern TOKEN = Pattern.compile(
            "\\{\\{\\s*(?:(\\w+)\\s*=\\s*)?([\\w.-]+)((?:\\s+(?:\"[^\"]*\"|[^\\s}\"]+)){0,500})\\s*}}|(\\\\\\{\\{)");
    // ponytail: generous ceilings that keep one placeholder from asking for gigabytes; raise them if a format needs more.
    private static final int MAX_LENGTH = 10_000, MAX_SCALE = 18;
    private static final java.util.Set<String> BUILT_IN = java.util.Set.of(
            "uuid", "id", "now", "today", "seq", "int", "decimal", "pick", "digits", "alnum", "iban");
    private static final Pattern ARG = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    // ponytail: BBAN shapes for three countries (A = letter, # = digit); add a line per country when needed.
    private static final Map<String, String> BBAN = Map.of(
            "GB", "AAAA##############", "DE", "##################", "NL", "AAAA##########");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TemplateEngine.class);

    private final Map<String, Generator> custom = new LinkedHashMap<>();
    private final java.util.Set<String> complained = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public TemplateEngine() {
    }

    @Autowired
    public TemplateEngine(ObjectProvider<Generator> generators) {
        this(generators.orderedStream().toList());
    }

    TemplateEngine(List<Generator> generators) {
        generators.forEach(g -> custom.put(g.name(), g));
    }

    /** Names of the generators that came from beans or plugins, for the console's Insert menu. */
    public List<String> customGenerators() {
        return List.copyOf(custom.keySet());
    }

    /**
     * @param fields named expressions from the pack, e.g. currency -> {{pick GBP EUR}}
     * @param vars   values fixed for this message, e.g. overrides typed for a run
     */
    public String render(String template, Map<String, String> fields, Map<String, String> vars) {
        return message(fields, vars, new AtomicLong()).render(template);
    }

    /**
     * One message in the making: every text rendered through it shares the same {{name=...}} bindings.
     * seq is the counter behind {{seq}}. A run passes its own, so its messages count 1, 2, 3; a preview or a
     * check passes a new one and uses nothing up.
     */
    public Message message(Map<String, String> fields, Map<String, String> vars, AtomicLong seq) {
        return message(fields, vars, seq, null);
    }

    /**
     * The same, with the random choices drawn from the source given: the same source in the same state renders the
     * same message again, which is what makes a run with a seed repeatable. Ids ({{uuid}}, {{id}}), times ({{now}},
     * {{today}}) and generators from plugins do not come from it and differ from run to run.
     */
    public Message message(Map<String, String> fields, Map<String, String> vars, AtomicLong seq, RandomGenerator random) {
        return new Rendering(fields, new HashMap<>(vars), seq, random);
    }

    public interface Message {
        String render(String text);

        /** The first value {{uuid}} or {{id}} has produced in this message so far, or null. It identifies the message when it arrives somewhere. */
        String firstId();
    }

    private final class Rendering implements Message {
        private final Map<String, String> fields, bound;
        private final AtomicLong seq;
        private final RandomGenerator random;   // null: the thread's own, and ids from the system
        private String firstId;

        Rendering(Map<String, String> fields, Map<String, String> bound, AtomicLong seq, RandomGenerator random) {
            this.fields = fields;
            this.bound = bound;
            this.seq = seq;
            this.random = random;
        }

        @Override
        public String render(String text) {
            return render(text, 0);
        }

        @Override
        public String firstId() {
            return firstId;
        }

        private String render(String text, int depth) {
            if (depth > 8) throw new IllegalArgumentException("pack fields refer to each other in a loop");
            Matcher m = TOKEN.matcher(text);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                if (m.group(4) != null) {   // \{{
                    m.appendReplacement(out, "{{");
                    continue;
                }
                String bind = m.group(1), name = m.group(2);
                String value;
                if (bind != null && bound.containsKey(bind)) value = bound.get(bind);
                else if (bound.containsKey(name)) value = bound.get(name);
                else if (fields.containsKey(name)) value = render(fields.get(name), depth + 1);
                else {
                    value = builtin(name, args(m.group(3)), seq, random);
                    if (firstId == null && (name.equals("uuid") || name.equals("id"))) firstId = value;
                }
                if (bind != null) bound.put(bind, value);
                m.appendReplacement(out, Matcher.quoteReplacement(value));
            }
            m.appendTail(out);
            return out.toString();
        }
    }

    /** Whether {{name}} is a generator, built in or added. */
    boolean knows(String name) {
        return BUILT_IN.contains(name) || custom.containsKey(name);
    }

    /** The placeholder expression of the first added generator that says the value is one of its own, or null. */
    String recognised(String value) {
        for (Generator g : custom.values()) {
            String expression;
            try {
                expression = g.recognise(value);
            } catch (Throwable e) {   // Throwable: a plugin built against a missing class throws an Error
                complain(g, "failed while recognising a value (" + e.getClass().getName() + ")");
                continue;
            }
            if (expression == null) continue;
            // It may name itself, with arguments, and nothing else: an answer is text from a plugin that goes straight into the template.
            Matcher m = TOKEN.matcher("{{" + expression + "}}");
            if (m.matches() && m.group(1) == null && g.name().equals(m.group(2))) return expression.strip();
            complain(g, "answered recognise() with something other than a placeholder of its own");
        }
        return null;
    }

    /** Once per generator and reason: recognition asks about every value of every sample. Neither the value nor the exception's message is logged; they are the user's data. */
    private void complain(Generator g, String what) {
        if (complained.add(g.name() + what)) log.warn("Generator {} {}; it is taken as \"not mine\"", g.name(), what);
    }

    /** The country of an IBAN that {{iban}} could have generated (right length, valid check digits), or null. */
    static String ibanCountry(String value) {
        String shape = value.length() > 4 ? BBAN.get(value.substring(0, 2)) : null;
        boolean valid = shape != null && value.length() == shape.length() + 4 && value.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")
                && mod97(value.substring(4) + value.substring(0, 4)) == 1;
        return valid ? value.substring(0, 2) : null;
    }

    static List<String> args(String raw) {
        List<String> out = new ArrayList<>();
        Matcher m = ARG.matcher(raw);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2));
        return out;
    }

    private String builtin(String name, List<String> a, AtomicLong seq, RandomGenerator seeded) {
        Generator plugin = custom.get(name);
        if (plugin != null) {
            try {
                return String.valueOf(plugin.generate(a));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("{{" + name + "}}: " + e.getMessage());
            }
        }
        RandomGenerator r = seeded != null ? seeded : ThreadLocalRandom.current();
        try {
            return switch (name) {
                case "uuid" -> uuid().toString();
                case "id" -> uuid().toString().replace("-", ""); // 32 characters: fits fields that are limited to 35
                case "now" -> {
                    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
                    if (a.isEmpty()) yield DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now);
                    try {
                        yield DateTimeFormatter.ofPattern(a.get(0)).format(now);
                    } catch (IllegalArgumentException | DateTimeException e) {   // not a pattern, or one a UTC time cannot fill in
                        throw new IllegalArgumentException("{{now}} cannot use the pattern " + a.get(0) + ": " + e.getMessage());
                    }
                }
                case "today" -> LocalDate.now(ZoneOffset.UTC).toString();
                case "seq" -> Long.toString(seq.incrementAndGet());
                case "int" -> Long.toString(r.nextLong(Long.parseLong(a.get(0)), Long.parseLong(a.get(1)) + 1));
                case "decimal" -> BigDecimal
                        .valueOf(r.nextDouble(Double.parseDouble(a.get(0)), Double.parseDouble(a.get(1))))
                        .setScale(a.size() > 2 ? upTo(MAX_SCALE, a.get(2), "{{decimal}} gives at most " + MAX_SCALE + " decimal places") : 2, RoundingMode.HALF_UP).toPlainString();
                case "pick" -> a.get(r.nextInt(a.size()));
                case "digits", "alnum" -> random(r, (name.equals("digits") ? "#" : "*")
                        .repeat(upTo(MAX_LENGTH, a.get(0), "{{" + name + "}} gives at most " + MAX_LENGTH + " characters")));
                case "iban" -> iban(r, a.isEmpty() ? "GB" : a.get(0));
                default -> throw new IllegalArgumentException("unknown placeholder {{" + name + "}}");
            };
        } catch (IllegalArgumentException e) {
            // messages written here say what is wrong; anything else (a number that does not parse…) gets the general one
            if (e.getMessage() != null && (e.getMessage().startsWith("unknown placeholder") || e.getMessage().startsWith("{{"))) throw e;
            throw new IllegalArgumentException("bad arguments for {{" + name + "}}");
        } catch (IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("missing arguments for {{" + name + "}}");
        }
    }

    /**
     * Always from the system's secure source, seed or no seed. An id is what a message is recognised by: one that a
     * seed repeated would make a message left on a queue by the last run look like an arrival of this one, and one
     * drawn from a small seed could be guessed.
     */
    private static UUID uuid() {
        return UUID.randomUUID();
    }

    private static int upTo(int max, String number, String otherwise) {
        int n = Integer.parseInt(number);
        if (n < 0 || n > max) throw new IllegalArgumentException(otherwise);
        return n;
    }

    /** # = digit, A = letter, * = letter or digit. */
    private static String random(RandomGenerator r, String shape) {
        StringBuilder sb = new StringBuilder(shape.length());
        for (char c : shape.toCharArray()) {
            String pool = c == '#' ? "0123456789" : c == 'A' ? "ABCDEFGHIJKLMNOPQRSTUVWXYZ" : "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
            sb.append(pool.charAt(r.nextInt(pool.length())));
        }
        return sb.toString();
    }

    // ponytail: the mod-97 check digits are valid; national checks (sort code, account modulus) are not modelled.
    static String iban(RandomGenerator r, String country) {
        String shape = BBAN.get(country);
        if (shape == null) throw new IllegalArgumentException("{{iban}} supports " + new java.util.TreeSet<>(BBAN.keySet()));
        String bban = random(r, shape);
        return country + String.format("%02d", 98 - mod97(bban + country + "00")) + bban;
    }

    static int mod97(String s) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) digits.append(Character.getNumericValue(c)); // A=10 … Z=35
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
    }
}
