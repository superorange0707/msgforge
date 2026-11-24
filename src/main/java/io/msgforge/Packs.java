package io.msgforge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * A pack is a folder: an optional pack.yml (name, fields) plus templates/.
 * Packs are read from disk on every call, so dropping in a folder or editing a file takes effect at once.
 */
@Component
public class Packs {

    // Pack ids and template names come from URLs: one path segment, no leading dot, no separators.
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][\\w.-]{0,63}");
    private static final int MAX_TEMPLATE_CHARS = 1_000_000;

    private final List<Path> roots;

    public Packs(@Value("${msgforge.packs:packs}") String[] roots) {
        this.roots = Arrays.stream(roots).map(r -> Path.of(r.trim()).toAbsolutePath().normalize()).toList();
    }

    /** error is set, and the rest empty, for a pack in the list whose pack.yml cannot be read. */
    public record Pack(String id, String name, Map<String, String> fields, List<String> templates, String error) {}

    /** One pack that cannot be read is listed with its error; it does not take the others down with it. */
    public List<Pack> list() {
        List<Pack> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path root : roots) {
            for (String id : names(root, true)) {
                if (!seen.add(id)) continue;
                try {
                    out.add(get(id));
                } catch (RuntimeException e) {
                    out.add(new Pack(id, id, Map.of(), List.of(), String.valueOf(e.getMessage())));
                }
            }
        }
        return out;
    }

    public Pack get(String id) {
        Path dir = dir(id);
        Map<?, ?> meta = Map.of();
        Path yml = dir.resolve("pack.yml");
        if (Files.isRegularFile(yml)) {
            try {
                if (new Yaml(new SafeConstructor(new LoaderOptions())).load(text(yml)) instanceof Map<?, ?> m) meta = m;
            } catch (YAMLException e) {
                // the parser's message runs over several lines; the first says what is wrong and where
                throw new IllegalArgumentException(id + "/pack.yml is not valid YAML: " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (meta.get("fields") instanceof Map<?, ?> f) f.forEach((k, v) -> fields.put(String.valueOf(k), String.valueOf(v)));
        Object name = meta.get("name");
        return new Pack(id, name == null ? id : name.toString(), fields, names(dir.resolve("templates"), false), null);
    }

    /** New packs go in the first configured folder. Everything is checked before the folder is made, so a refused pack leaves nothing behind. */
    public Pack create(String id, String name, Map<String, String> fields) {
        safe(id);
        if (roots.stream().anyMatch(r -> Files.exists(r.resolve(id)))) throw new IllegalArgumentException("A pack named " + id + " already exists");
        clean(fields);
        try {
            Files.createDirectories(roots.get(0).resolve(id).resolve("templates"));
        } catch (IOException e) {
            throw failed(e);
        }
        return update(id, name, fields);
    }

    /** Rewrites pack.yml. Comments in a hand-written file are not kept. */
    public Pack update(String id, String name, Map<String, String> fields) {
        Path dir = dir(id);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name == null || name.isBlank() ? id : name.trim());
        meta.put("fields", clean(fields));
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            Files.writeString(dir.resolve("pack.yml"), new Yaml(options).dump(meta));
        } catch (IOException e) {
            throw failed(e);
        }
        return get(id);
    }

    private static Map<String, String> clean(Map<String, String> fields) {
        Map<String, String> clean = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (key == null || !FIELD.matcher(key.trim()).matches()) throw new IllegalArgumentException("Invalid field name: " + key);
            clean.put(key.trim(), value == null ? "" : value);
        });
        return clean;
    }

    /** Removes the pack folder and everything in it. */
    public void delete(String id) {
        try (Stream<Path> walk = Files.walk(dir(id))) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    public String read(String pack, String template) {
        Path file = file(pack, template);
        if (!Files.isRegularFile(file)) throw new NoSuchElementException("no template " + pack + "/" + template);
        return text(file);
    }

    /** Whether a file of that name is there, as the file system sees it: on one that ignores case, Order.json is order.json. */
    public boolean exists(String pack, String template) {
        return Files.exists(file(pack, template));
    }

    /**
     * A change that the file system refused: the pack is on a read-only mount, or its files belong to another user.
     * That is the user's to sort out, so it is said in words; anything else is unexpected.
     */
    private static RuntimeException failed(IOException e) {
        boolean readOnly = e instanceof AccessDeniedException
                || e instanceof FileSystemException f && String.valueOf(f.getReason()).contains("Read-only");
        return readOnly ? new IllegalArgumentException("This pack is read-only: msgforge is not allowed to change its files. Copy it to a pack of your own to edit it.")
                : new UncheckedIOException(e);
    }

    private static String text(Path file) {
        try {
            return Files.readString(file);
        } catch (MalformedInputException e) {
            throw new IllegalArgumentException(file.getFileName() + " is not UTF-8 text. Save it as UTF-8 and try again.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(String pack, String template, String body) {
        if (body.length() > MAX_TEMPLATE_CHARS) throw new IllegalArgumentException("template is larger than 1 MB");
        Path file = file(pack, template);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, body);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    public void deleteTemplate(String pack, String template) {
        try {
            if (!Files.deleteIfExists(file(pack, template))) throw new NoSuchElementException("no template " + pack + "/" + template);
        } catch (IOException e) {
            throw failed(e);
        }
    }

    private Path file(String pack, String template) {
        return dir(pack).resolve("templates").resolve(safe(template));
    }

    private Path dir(String id) {
        safe(id);
        return roots.stream().map(r -> r.resolve(id)).filter(Files::isDirectory).findFirst()
                .orElseThrow(() -> new NoSuchElementException("no pack " + id));
    }

    private static String safe(String name) {
        if (name == null || !SAFE.matcher(name).matches()) throw new IllegalArgumentException("Use letters, digits, dot, dash and underscore: " + name);
        return name;
    }

    private static List<String> names(Path dir, boolean directories) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> directories ? Files.isDirectory(p) : Files.isRegularFile(p))
                    .map(p -> p.getFileName().toString()).filter(n -> SAFE.matcher(n).matches()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
