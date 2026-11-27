/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.ia.agent.documentation;

import io.yupiik.ia.agent.core.events.YcaEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Minisite preaction that renders the {@code events.adoc} partial: the list of hookable {@link YcaEvent}
 * implementations, keyed by their {@link YcaEvent#id()} (the simple class name, matching the
 * {@code yca.hooks.<id>} configuration key), with each event's {@link YcaEvent#description()} that documents both
 * its input payload and the output {@code HookResponse} schema.
 *
 * <p>Discovery scans the {@code io.yupiik.ia.agent.core.events} package on the classpath (the minisite build runs
 * from {@code target/classes}, so a directory scan is enough) and only keeps concrete, non-nested event types that
 * implement {@link YcaEvent}. No event record is instantiated: {@code id()} and {@code description()} are resolved
 * reflectively off an all-null canonical instance.</p>
 */
public class EventsDocumentationGenerator implements Runnable {
    private static final String PACKAGE = "io.yupiik.ia.agent.core.events";
    private static final String RESOURCE = PACKAGE.replace('.', '/');

    private final Path sourceBase;
    private final Map<String, String> configuration;

    public EventsDocumentationGenerator(final Path sourceBase, final Map<String, String> configuration) {
        this.sourceBase = sourceBase;
        this.configuration = configuration == null ? Map.of() : configuration;
    }

    @Override
    public void run() {
        final var events =
                discover().stream().sorted(Comparator.comparing(YcaEvent::id)).toList();
        if (events.isEmpty()) {
            return;
        }
        final var sb = new StringBuilder();
        for (final var event : events) {
            sb.append("=== `").append(event.id()).append("`\n\n");
            final var description = event.description();
            sb.append(description == null || description.isBlank() ? "No description." : description);
            sb.append("\n\n");
            sb.append("Configuration key: `yca.hooks.").append(event.id()).append("`\n\n");
        }
        writePartial("events.adoc", sb.toString());
    }

    private List<YcaEvent> discover() {
        final var result = new ArrayList<YcaEvent>();
        final var loader = YcaEvent.class.getClassLoader();
        try {
            for (final var url : Collections.list(loader.getResources(RESOURCE))) {
                if ("file".equals(url.getProtocol())) {
                    final var dir = Path.of(url.toURI());
                    if (Files.isDirectory(dir)) {
                        try (final var files = Files.list(dir)) {
                            files.filter(f -> f.getFileName().toString().endsWith(".class"))
                                    .forEach(f -> {
                                        final var className = PACKAGE
                                                + '.'
                                                + f.getFileName()
                                                        .toString()
                                                        .substring(
                                                                0,
                                                                f.getFileName()
                                                                                .toString()
                                                                                .length()
                                                                        - 6);
                                        addIfEvent(result, loader, className);
                                    });
                        }
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    // a packaged core jar: enumerate the package entries, not just a directory
                    final var jar = url.openConnection();
                    if (jar instanceof JarURLConnection juc) {
                        try (final var entries = juc.getJarFile().stream()) {
                            entries.filter(e -> !e.isDirectory()
                                            && e.getName().startsWith(RESOURCE + '/')
                                            && e.getName().endsWith(".class"))
                                    .forEach(e -> {
                                        final var fileName = e.getName()
                                                .substring(
                                                        RESOURCE.length() + 1,
                                                        e.getName().length() - 6);
                                        addIfEvent(result, loader, PACKAGE + '.' + fileName);
                                    });
                        }
                    }
                }
            }
        } catch (final Exception e) {
            throw new IllegalStateException("cannot scan events package", e);
        }
        return result;
    }

    private void addIfEvent(final List<YcaEvent> result, final ClassLoader loader, final String className) {
        try {
            final var clazz = Class.forName(className, false, loader);
            if (YcaEvent.class.isAssignableFrom(clazz)
                    && !clazz.isInterface()
                    && !clazz.isEnum()
                    && !Modifier.isAbstract(clazz.getModifiers())) {
                result.add(dummyOf(clazz));
            }
        } catch (final Throwable ignored) {
            // not an event or not usable for documentation
        }
    }

    /** Builds an all-null (or zeroed) canonical instance just to read the metadata methods. */
    private YcaEvent dummyOf(final Class<?> clazz) throws Exception {
        final var constructor = clazz.getDeclaredConstructors()[0];
        final var args = new Object[constructor.getParameterCount()];
        final var types = constructor.getParameterTypes();
        for (var i = 0; i < types.length; i++) {
            final var t = types[i];
            if (t == boolean.class) {
                args[i] = false;
            } else if (t == int.class) {
                args[i] = 0;
            } else if (t == long.class) {
                args[i] = 0L;
            } else if (t == double.class) {
                args[i] = 0d;
            } else {
                args[i] = null;
            }
        }
        constructor.setAccessible(true);
        return (YcaEvent) constructor.newInstance(args);
    }

    private void writePartial(final String fileName, final String content) {
        try {
            final var base = sourceBase.resolve(
                    configuration.getOrDefault("output", "content/coding-agent/_partials/generated"));
            final var target = base.resolve(fileName).normalize();
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content.endsWith("\n") ? content : content + "\n");
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
