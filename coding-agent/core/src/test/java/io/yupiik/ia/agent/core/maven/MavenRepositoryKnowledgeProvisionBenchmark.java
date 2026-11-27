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
package io.yupiik.ia.agent.core.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.impl.MapConfigSource;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.core.llm.ChatClient;
import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.content.LocalRepository;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Realtime provisioning benchmark: boots the real yca container (real provider from the home
 * configuration, ie. deepseek-v4-flash), points the project at the hop repository and measures how
 * long a full provisioning of the project dependencies takes against a skill-empty local Maven
 * repository. The generated {@code yca/} skill folders and {@code -skills.md} manifests are wiped
 * from the real repository before and after the run so the repository state is left untouched.
 *
 * <p>Run explicitly with:
 * <pre>mvn -o -pl core test -Dtest=MavenRepositoryKnowledgeProvisionBenchmark
 *   -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition</pre>
 */
@Disabled("benchmark, not a unit test - run explicitly")
class MavenRepositoryKnowledgeProvisionBenchmark {
    private static final Pattern BASE_URL = Pattern.compile("\"baseUrl\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9.]+))");
    private static final Pattern API_KEY = Pattern.compile("\"apiKey\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9.]+))");
    private static final Pattern MODEL = Pattern.compile("\"model\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9.]+))");
    private static final Pattern CONTEXT_WINDOW =
            Pattern.compile("\"contextWindow\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9.]+))");

    private static final String HOP_PROJECT = "/opt/rmannibucau/dev/Github/hop";

    @Test
    void provisionHopProjectWithRealModel(@TempDir final Path storageHome) throws Exception {
        final var m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        final var hop = Path.of(HOP_PROJECT);
        assertTrue(Files.isDirectory(hop), "hop project missing: " + hop);
        assertTrue(Files.isDirectory(m2), "local repository missing: " + m2);

        // the real local repository is a parameter of the map configuration source below, so no
        // system property is mutated here: fusion resolves a registered source before the
        // system properties, hence the source always wins.
        final var wipedBefore = cleanMarkers(m2);
        System.out.println("[bench] skill-empty precondition: wiped " + wipedBefore + " pre-existing marker(s)");

        try (final var container = container(hop, m2, storageHome)) {
            final var chatClient = container.lookup(ChatClient.class).instance();
            assertFalse(
                    chatClient.model() == null || chatClient.model().isBlank(),
                    "no provider/model configured - refusing to benchmark the LLM-less fallback");

            final var llm = container.lookup(YcaKnowledgeLlm.class).instance();
            final long cohortStart = System.nanoTime();
            final var cohort = llm.resolveProjectDeps();
            System.out.printf(
                    "[bench] cohort: %d locally-resolvable project dependency(ies) in %d ms%n",
                    cohort.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cohortStart));
            assertFalse(cohort.isEmpty(), "empty project dependency cohort - mvn dependency:list failed?");

            final var service =
                    container.lookup(MavenRepositoryKnowledgeService.class).instance();
            // only artifacts that actually resolve to a jar in the local repository are indexable
            final var repo = new LocalRepository(m2);
            final var present = cohort.stream()
                    .filter(g -> {
                        final var r = repo.resolve(g);
                        final var m = r.mainJar();
                        return r.present() && m != null && Files.isRegularFile(m);
                    })
                    .toList();
            System.out.println("[bench] locally indexable project dependencies: " + present.size());
            assertFalse(present.isEmpty(), "no locally indexable project dependency");

            // time-bounded sampling: 10, 100, then 200 artifacts; stop early if the budget elapses
            final long[] budgetsMs = {90_000L * 6, 90_000L * 8, 90_000L * 3}; // generous per-size caps
            final int[] sizes = {10, 100, 200};
            final var grandStart = System.nanoTime();
            long skillsBefore = countSkills(m2);
            for (int i = 0; i < sizes.length; i++) {
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - grandStart) > 9 * 60_000L) {
                    System.out.println("[bench] overall 10-min budget reached - stopping further samples");
                    break;
                }
                final long size = sizes[i];
                final var slice =
                        present.stream().limit(size).map(Gav::coordinate).toList();
                final var done = new CountDownLatch(1);
                final var start = System.nanoTime();
                final var ack = service.startIndex(
                        hop,
                        slice,
                        false,
                        progress -> {
                            long at = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                            if (at % 20_000 < 20_000 && at > 30_000) {
                                System.out.printf("[bench] %6d ms | %s [slice %d]%n", at, progress, size);
                            }
                        },
                        summary -> {
                            System.out.printf(
                                    "[bench] %6d ms | slice %d summary:%n",
                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), size);
                            summary.lines().forEach(line -> System.out.println("[bench]   " + line));
                            done.countDown();
                        },
                        null);
                ack.forEach(line -> System.out.println("[bench] slice " + size + " ack: " + line));
                if (!done.await(budgetsMs[i], TimeUnit.MILLISECONDS)) {
                    System.out.println("[bench] slice " + size + " did not finish within " + (budgetsMs[i] / 1000)
                            + "s - stopping sampling early");
                    break;
                }
                final long sliceMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                final long skillsAfter = countSkills(m2);
                System.out.printf(
                        "[bench] slice %-3d: %5d ms | +%d SKILL.md | %.2f ms/artifact%n",
                        size, sliceMs, skillsAfter - skillsBefore, (double) sliceMs / size);
                skillsBefore = skillsAfter;
            }
            final long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - grandStart);
            System.out.println();
            System.out.println(
                    "=== hop provisioning benchmark (real model: " + chatClient.model() + ", -Xmx from argLine) ===");
            System.out.printf("  total sampling time       : %d ms (%.1f s)%n", totalMs, totalMs / 1000.0);
            System.out.printf("  generated SKILL.md total  : %d%n", countSkills(m2));
            System.out.println();
        } finally {
            final var wipedAfter = cleanMarkers(m2);
            System.out.println("[bench] cleanup: wiped " + wipedAfter + " generated marker(s)");
        }
    }

    private RuntimeContainer container(final Path projectDir, final Path localRepository, final Path storageHome) {
        final var provider = readHomeProvider();
        final var config = new LinkedHashMap<String, String>();
        config.put("yca.storageHome", storageHome.toString());
        // YcaConfigurationProducer looks this file up before falling back to the resolved
        // "yca.storage.home" (via the fusion configuration, not a system property): pointing it at
        // a file that does not exist keeps the user home configuration out of the benchmark, the
        // provider settings being injected explicitly above (readHomeProvider). Without it, the home
        // file would be registered first and its keys would win over this map.
        config.put(
                "yca.configuration.json",
                storageHome.resolve("configuration.json").toString());
        config.put("yca.projectDir", projectDir.toString());
        // real provider settings, read at runtime from the user home configuration (never committed)
        provider.forEach((key, value) -> config.put("yca.provider." + key, value));
        config.put("yca.mavenRepositoryKnowledge.enabled", "true");
        config.put("yca.mavenRepositoryKnowledge.localRepository", localRepository.toString());
        config.put("yca.mavenRepositoryKnowledge.concurrency", "4");
        config.put("yca.mavenRepositoryKnowledge.batchSize", "256");
        config.put("yca.mavenRepositoryKnowledge.maxBatchChars", "-1");
        config.put("yca.mavenRepositoryKnowledge.skillsAtSessionStart", "false");
        config.put("yca.mcp.enabled", "false");
        config.put("yca.structuralCodeGraph.enabled", "false");
        return ConfiguringContainer.of()
                .register(new ProvidedInstanceBean<>(
                        DefaultScoped.class, ArgsConfigSource.class, () -> new ArgsConfigSource(List.of())))
                .register(new FusionListener<ConfigurationRegistration>() {
                    @Override
                    public Type eventType() {
                        return ConfigurationRegistration.class;
                    }

                    @Override
                    public int priority() {
                        // before YcaConfigurationProducer (Integer.MIN_VALUE + 1), which reads
                        // yca.configuration.json while the configuration is being built
                        return Integer.MIN_VALUE;
                    }

                    @Override
                    public void onEvent(
                            final RuntimeContainer container, final ConfigurationRegistration registration) {
                        registration.addSource().accept(new MapConfigSource(config));
                    }
                })
                .start();
    }

    /**
     * Extracts {@code yca.provider} scalar fields (baseUrl, apiKey, model, contextWindow) from the
     * user home yca configuration without pulling a full JSON stack into the benchmark.
     */
    private Map<String, String> readHomeProvider() {
        final var home = Path.of(System.getProperty("user.home"), ".yupiik", "coding-agent", "configuration.json");
        final String json;
        try {
            json = Files.readString(home);
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read " + home, e);
        }
        final var provider = objectBlock(json, "provider");
        final var out = new LinkedHashMap<String, String>();
        scalar(provider, BASE_URL).ifPresent(v -> out.put("baseUrl", v));
        scalar(provider, API_KEY).ifPresent(v -> out.put("apiKey", v));
        scalar(provider, MODEL).ifPresent(v -> out.put("model", v));
        scalar(provider, CONTEXT_WINDOW).ifPresent(v -> out.put("contextWindow", v));
        if (out.size() < 3) {
            throw new IllegalStateException("provider baseUrl/apiKey/model not found in " + home);
        }
        return out;
    }

    /** Brace-matched JSON object value for the given key. */
    private String objectBlock(final String json, final String key) {
        final var keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) {
            throw new IllegalStateException("key not found: " + key);
        }
        final var open = json.indexOf('{', keyIdx);
        int depth = 0;
        for (var i = open; i < json.length(); i++) {
            final var c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(open, i + 1);
                }
            }
        }
        throw new IllegalStateException("unbalanced object for key: " + key);
    }

    private Optional<String> scalar(final String block, final Pattern field) {
        final var matcher = field.matcher(block);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }

    /**
     * Removes every marker the feature generates under the local repository: {@code yca/} skill
     * folders (guarded: only when a SKILL.md exists beneath) and {@code *-skills.md} manifests.
     *
     * @return the number of removed files/directories
     */
    private long cleanMarkers(final Path m2) {
        final var toDelete = new ArrayList<Path>();
        try (final var stream = Files.walk(m2)) {
            stream.forEach(path -> {
                final var name = path.getFileName().toString();
                if (Files.isRegularFile(path) && name.endsWith("-skills.md")) {
                    toDelete.add(path);
                } else if (Files.isDirectory(path)
                        && (name.equals("yca") || name.equals(".yca"))
                        && containsSkill(path)) {
                    toDelete.add(path);
                }
            });
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        // deepest first so yca/ children are gone before the folder itself is removed
        toDelete.sort(
                Comparator.comparingInt((final Path p) -> p.getNameCount()).reversed());
        long removed = 0;
        for (final var path : toDelete) {
            if (!Files.exists(path)) {
                continue;
            }
            try (final var walk = Files.walk(path)) {
                final var children = walk.sorted(Comparator.reverseOrder()).toList();
                for (final var child : children) {
                    Files.deleteIfExists(child);
                }
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            removed++;
        }
        return removed;
    }

    private boolean containsSkill(final Path dir) {
        try (final var stream = Files.walk(dir)) {
            return stream.anyMatch(
                    p -> Files.isRegularFile(p) && p.getFileName().toString().equals("SKILL.md"));
        } catch (final IOException e) {
            return false;
        }
    }

    private long countSkills(final Path m2) {
        try (final var stream = Files.walk(m2)) {
            try (Stream<Path> filtered = stream.filter(p -> {
                if (!Files.isRegularFile(p)
                        || !p.getFileName().toString().equals("SKILL.md")
                        || p.getParent() == null) {
                    return false;
                }
                final var grandParent = p.getParent().getParent();
                if (grandParent == null) {
                    return false;
                }
                final var markerDir = grandParent.getFileName().toString();
                return markerDir.equals("yca") || markerDir.equals(".yca");
            })) {
                return filtered.count();
            }
        } catch (final IOException e) {
            return -1;
        }
    }
}
