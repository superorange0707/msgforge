package io.msgforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;

/**
 * A scenario is a saved run: which templates in what mix, to which targets, how many and how fast,
 * and where each template's messages should arrive.
 * Its numbers are checked when it is saved. Its packs and connections are checked when it runs,
 * so a scenario can be prepared before the pack or broker it needs is in place.
 */
@Component
public class Scenarios {

    /** load, invalid, seed and watches are optional and mean what they mean in a {@link Runs.Request}. */
    public record Scenario(String id, String name, List<Runs.Part> parts, List<Runs.Target> targets, String mode,
                           int count, double rate, Map<String, String> headers, Integer timeoutSeconds, String match,
                           Runs.Load load, Faults.Spec invalid, Long seed, List<Runs.Watch> watches) {

        /** A scenario as it was before load plans. */
        public Scenario(String id, String name, List<Runs.Part> parts, List<Runs.Target> targets, String mode,
                        int count, double rate, Map<String, String> headers, Integer timeoutSeconds, String match) {
            this(id, name, parts, targets, mode, count, rate, headers, timeoutSeconds, match, null, null, null, null);
        }

        Scenario withId(String id) {
            return new Scenario(id, name, parts, targets, mode, count, rate, headers, timeoutSeconds, match, load, invalid, seed, watches);
        }
    }

    /** What the command line may replace in a scenario for one run. Null leaves the scenario's own. */
    public record Overrides(Integer count, Double rate, Integer durationSeconds, Integer rampUpSeconds, Integer senders, Long seed, Double invalidPercent) {}

    private final ConfigStore store;
    private final Runs runs;
    private final List<Scenario> scenarios = new ArrayList<>();

    public Scenarios(ConfigStore store, Runs runs) {
        this.store = store;
        this.runs = runs;
        List<Scenario> saved = store.read("scenarios", new TypeReference<List<Scenario>>() {});
        if (saved == null) return;
        for (Scenario s : saved) {
            // A hand-edited file may leave things out; fill them in so nothing later meets a null.
            String id = s.id() == null || s.id().isBlank() ? UUID.randomUUID().toString().substring(0, 8) : s.id();
            scenarios.add(new Scenario(id, s.name() == null || s.name().isBlank() ? id : s.name(), s.parts() == null ? List.of() : s.parts(),
                    s.targets() == null ? List.of() : s.targets(), s.mode(), s.count(), s.rate(), s.headers(), s.timeoutSeconds(), s.match(),
                    s.load(), s.invalid(), s.seed(), s.watches()));
        }
    }

    public synchronized Scenario get(String id) {
        return find(id);
    }

    public synchronized List<Scenario> list() {
        return List.copyOf(scenarios);
    }

    /** Creates (blank or unknown id) or replaces a scenario. */
    public synchronized Scenario save(Scenario in) {
        if (in.name() == null || in.name().isBlank()) throw new IllegalArgumentException("Name is required");
        if (in.parts() == null || in.parts().isEmpty()) throw new IllegalArgumentException("Add at least one template");
        if (in.targets() == null || in.targets().isEmpty()) throw new IllegalArgumentException("Add at least one target");
        Runs.check(in.count(), in.rate(), in.parts(), in.targets(), in.headers(), in.timeoutSeconds(), in.load(), in.invalid(), in.watches());
        int at = indexOf(in.id());
        Scenario s = new Scenario(at < 0 ? UUID.randomUUID().toString().substring(0, 8) : in.id(), in.name().trim(),
                List.copyOf(in.parts()), List.copyOf(in.targets()), "all".equals(in.mode()) ? "all" : "spread",
                in.count(), in.rate(), in.headers() == null ? Map.of() : Map.copyOf(in.headers()),
                in.timeoutSeconds(), in.match(), in.load(), in.invalid(), in.seed(), in.watches() == null ? null : List.copyOf(in.watches()));
        if (at < 0) scenarios.add(s);
        else scenarios.set(at, s);
        store.write("scenarios", scenarios);
        return s;
    }

    public synchronized void delete(String id) {
        scenarios.remove(find(id));
        store.write("scenarios", scenarios);
    }

    public Runs.View run(String id) {
        return run(id, new Overrides(null, null, null, null, null, null, null));
    }

    /** count and rate, when given, replace the scenario's own for this run (the command line's --count and --rate). */
    public Runs.View run(String id, Integer count, Double rate) {
        return run(id, new Overrides(count, rate, null, null, null, null, null));
    }

    public Runs.View run(String id, Overrides o) {
        Scenario s;
        synchronized (this) {
            s = find(id);
        }
        Runs.Load own = s.load() == null ? new Runs.Load(null, null, null, null) : s.load();
        Runs.Load load = new Runs.Load(o.rampUpSeconds() != null ? o.rampUpSeconds() : own.rampUpSeconds(),
                o.durationSeconds() != null ? o.durationSeconds() : own.durationSeconds(), o.senders() != null ? o.senders() : own.senders(), own.unlimited());
        Faults.Spec invalid = o.invalidPercent() == null ? s.invalid()
                : new Faults.Spec(o.invalidPercent(), s.invalid() == null ? null : s.invalid().kinds(), s.invalid() == null ? null : s.invalid().expect());
        return runs.start(new Runs.Request(s.name(), s.parts(), s.targets(), s.mode(), o.count() != null ? o.count() : s.count(),
                o.rate() != null ? o.rate() : s.rate(), s.headers(),
                null, null, null, null, null, null, null, s.timeoutSeconds(), s.match(), load, invalid, o.seed() != null ? o.seed() : s.seed(), s.watches(), null));
    }

    private Scenario find(String id) {
        int at = indexOf(id);
        if (at < 0) throw new NoSuchElementException("no scenario " + id);
        return scenarios.get(at);
    }

    private int indexOf(String id) {
        for (int i = 0; i < scenarios.size(); i++) if (scenarios.get(i).id().equals(id)) return i;
        return -1;
    }
}
