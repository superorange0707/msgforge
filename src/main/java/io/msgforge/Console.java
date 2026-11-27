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
            if (!theme.getFileName().toString().toLowerCase().endsWith(".css")) return "/* MSGFORGE_THEME must name a .css file */";
            if (!Files.isRegularFile(theme)) return "/* the file MSGFORGE_THEME names is not there */";
            if (Files.size(theme) > MAX_THEME_BYTES) return "/* the theme is larger than " + MAX_THEME_BYTES / 1024 + " KB */";
            return Files.readString(theme, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {   // unreadable, or not text
            return "/* the theme could not be read */";
        }
    }
}
