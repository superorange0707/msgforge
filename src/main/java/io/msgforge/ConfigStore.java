package io.msgforge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Everything saved from the console lives in one JSON file, one section per feature, so a deployment ships a single file. */
@Component
public class ConfigStore {

    private final Path file;
    private final ObjectMapper json;
    private final ObjectNode root;

    public ConfigStore(@Value("${msgforge.config:msgforge.json}") String file, ObjectMapper json) {
        this.file = Path.of(file).toAbsolutePath().normalize();
        this.json = json;
        try {
            JsonNode saved = Files.isRegularFile(this.file) ? json.readTree(this.file.toFile()) : json.missingNode();
            if (saved instanceof ObjectNode o) this.root = o;
            else if (saved.isMissingNode()) this.root = json.createObjectNode();   // no file yet, or an empty one
            else throw new Invalid(this.file + " does not hold a JSON object.");
        } catch (JsonProcessingException e) {
            // The line number only: the parser's own message can quote the text around the error, and that may be a password.
            throw new Invalid(this.file + " is not valid JSON (line " + e.getLocation().getLineNr() + ").");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + this.file, e);
        }
    }

    /** The saved file cannot be used. It is left as it is: starting empty would overwrite it on the next save. */
    static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    /** Turns that into the one line Spring Boot prints, where a stack trace would bury it. Registered in META-INF/spring.factories. */
    static final class InvalidAnalyzer extends AbstractFailureAnalyzer<Invalid> {
        @Override
        protected FailureAnalysis analyze(Throwable rootFailure, Invalid cause) {
            return new FailureAnalysis(cause.getMessage(), "Fix the file or remove it, then start msgforge again. msgforge has not changed it.", cause);
        }
    }

    /** @return the saved section, or null when it was never saved */
    public synchronized <T> T read(String section, TypeReference<T> type) {
        return root.has(section) ? json.convertValue(root.get(section), type) : null;
    }

    public synchronized void write(String section, Object value) {
        root.set(section, json.valueToTree(value));
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(tmp);
            try {
                // It may hold passwords, so it is owner-only before the first byte is written.
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
