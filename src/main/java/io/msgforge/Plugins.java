package io.msgforge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Loads extensions from jars in the plugins folder, so organisation-specific connection types and
 * generators live outside this code base. A plugin jar lists its classes the standard Java way:
 *
 *   META-INF/services/io.msgforge.TransportType
 *   META-INF/services/io.msgforge.Generator
 *
 * Plugins run with the same rights as msgforge itself: only put jars you trust in that folder.
 */
@Configuration
public class Plugins {

    private static final Logger log = LoggerFactory.getLogger(Plugins.class);
    private static final List<Class<?>> EXTENSION_POINTS = List.of(TransportType.class, Generator.class);

    @Bean
    static BeanFactoryPostProcessor pluginBeans(Environment env) {
        return factory -> {
            for (Object plugin : load(Path.of(env.getProperty("msgforge.plugins", "plugins")))) {
                factory.registerSingleton("plugin:" + plugin.getClass().getName(), plugin);
                log.info("Loaded plugin {}", plugin.getClass().getName());
            }
        };
    }

    /**
     * Each jar is loaded on its own, so one that cannot be loaded (it names a class it does not contain, or was
     * built against something missing) is logged and skipped, and msgforge starts with the rest.
     */
    static List<Object> load(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        List<Object> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path jar : files.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) {
                // Kept open for the life of the process: plugin classes load lazily from it.
                URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, Plugins.class.getClassLoader());
                try {
                    List<Object> fromJar = new ArrayList<>();
                    for (Class<?> point : EXTENSION_POINTS) ServiceLoader.load(point, loader).forEach(fromJar::add);
                    found.addAll(fromJar);
                } catch (ServiceConfigurationError | LinkageError e) {
                    log.warn("Skipped plugin {}: {}", jar.getFileName(), Runs.describe(e));
                    loader.close();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }
}
