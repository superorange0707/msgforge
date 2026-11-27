package io.msgforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the console asks about itself: which version it is talking to, what to call itself, and the style sheet
 * an installation may lay over its own.
 *
 * A theme is one CSS file named by MSGFORGE_THEME. The console links /theme.css after its own style sheet, so the
 * file can set the colour variables at the top of app.css, or anything else. It is read at each request: an edit
 * shows at the next reload. Without the setting, /theme.css is empty.
 */
@RestController
public class Console {

    // ponytail: a style sheet, not an asset pipeline. A theme that wants fonts or images embeds them as data: URLs within this size.
    static final long MAX_THEME_BYTES = 512 * 1024;

    private final Path theme;
    private final String title;

    public Console(@Value("${msgforge.theme:}") String theme, @Value("${msgforge.title:}") String title) {
        this.theme = theme == null || theme.isBlank() ? null : Path.of(theme.trim()).toAbsolutePath().normalize();
        this.title = title == null || title.isBlank() ? null : title.trim().length() > 40 ? title.trim().substring(0, 40) : title.trim();
    }

    /**
     * The page, with its script and style sheet linked under a name that changes with their content
     * (app.js?v=1a2b3c4d5e6f). A browser that kept the files of an older version asks for the new ones by their new
     * name instead of being told that what it has is still good: the jar dates every file to 1980, so a validator
     * could never tell two versions apart. The page itself is never kept.
     */
    @GetMapping({"/", "/index.html"})
    public ResponseEntity<String> page() {
        return ResponseEntity.ok().header("Content-Type", "text/html;charset=UTF-8").header("Cache-Control", "no-cache, no-store").body(page);
    }

    private final String page = versioned();

    static String versioned() {
        try {
            String html = read("static/index.html"), hash = hash(read("static/app.js") + "\n" + read("static/app.css"));
            return html.replace("href=\"app.css\"", "href=\"app.css?v=" + hash + "\"").replace("src=\"app.js\"", "src=\"app.js?v=" + hash + "\"");
        } catch (IOException e) {
            throw new IllegalStateException("the console's files are missing from the program", e);
        }
    }

    private static String read(String resource) throws IOException {
        try (var in = Console.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException(resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String hash(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** version is null when msgforge runs from its sources. title is null unless MSGFORGE_TITLE gives one. */
    @GetMapping("/api/console")
    public Map<String, Object> console() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", Console.class.getPackage().getImplementationVersion());
        out.put("title", title);
        out.put("theme", theme != null);
        return out;
    }

    /**
     * The one file the setting names, and only if it is a style sheet of a sane size: the path comes from whoever
     * starts msgforge, and still nothing but a .css file is ever sent, as text/css, which a browser will not run.
     * A file that is missing or not acceptable gives an empty sheet with a comment saying why, never an error page:
     * the console must look like itself, not broken, when a theme is.
     */
    @GetMapping("/theme.css")
    public ResponseEntity<String> theme() {
        return ResponseEntity.ok().header("Content-Type", "text/css;charset=UTF-8").header("Cache-Control", "no-cache").body(sheet());
    }

    private String sheet() {
        if (theme == null) return "";
        try {
            if (!Files.exists(theme)) return "/* the file MSGFORGE_THEME names is not there */";
            // The file itself, wherever links lead: a link called theme.css to something else is that something else.
            Path file = theme.toRealPath();
            if (!file.getFileName().toString().toLowerCase().endsWith(".css")) return "/* MSGFORGE_THEME must name a .css file */";
            if (!Files.isRegularFile(file)) return "/* the file MSGFORGE_THEME names is not there */";
            if (Files.size(file) > MAX_THEME_BYTES) return "/* the theme is larger than " + MAX_THEME_BYTES / 1024 + " KB */";
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {   // unreadable, or not text
            return "/* the theme could not be read */";
        }
    }
}
