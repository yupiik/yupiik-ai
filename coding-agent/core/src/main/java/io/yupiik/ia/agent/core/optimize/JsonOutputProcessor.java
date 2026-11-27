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
package io.yupiik.ia.agent.core.optimize;

import static java.util.stream.Collectors.joining;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * JSON-aware compaction for tools that emit structured payloads: {@code kubectl ... -o json},
 * {@code docker inspect}, {@code gh api ... --jq|--template} and {@code aws ... --output json}.
 * Each command family keeps only the fields an agent needs (kubectl pods: name/status/restarts,
 * docker inspect: image/state/ports, gh api: the jq/template projection or compact rows, aws: the
 * payload condensed to a few columns). Parsing uses the fusion {@link JsonMapper}; any non-JSON
 * output is returned unchanged so the generic fallback processor handles it.
 */
@ApplicationScoped
public class JsonOutputProcessor implements OutputProcessor {
    private final CodingAgentConfiguration configuration;
    private final JsonMapper mapper;
    private final CommandOutputPostProcessor commandOutput;

    public JsonOutputProcessor(
            final CodingAgentConfiguration configuration,
            final JsonMapper mapper,
            final CommandOutputPostProcessor commandOutput) {
        this.configuration = configuration;
        this.mapper = mapper;
        this.commandOutput = commandOutput;
    }

    public JsonOutputProcessor(final CodingAgentConfiguration configuration, final JsonMapper mapper) {
        this(configuration, mapper, new CommandOutputPostProcessor());
    }

    protected JsonOutputProcessor() {
        this.configuration = null;
        this.mapper = null;
        this.commandOutput = new CommandOutputPostProcessor();
    }

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHeadToken(command, "kubectl", "docker", "gh", "aws");
    }

    @Override
    public int order() {
        return 30; // before the column-aware docker/kubectl (order 18) and generic (1000)
    }

    @Override
    public Set<String> names() {
        return Set.of("json");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var stripped = lines.map(commandOutput::stripAnsi).toList();
        final var text = String.join("\n", stripped).strip();
        if (text.isEmpty() || !text.startsWith("[") && !text.startsWith("{")) {
            return stripped.stream(); // not JSON: preserve original lines so the generic processor can act
        }
        try {
            final var parsed = mapper.fromString(Object.class, text);
            return Stream.of(render(parsed, headOf(command)));
        } catch (final RuntimeException e) {
            return stripped.stream(); // malformed JSON: leave it to the generic processor
        }
    }

    private String render(final Object parsed, final String head) {
        if (parsed instanceof final Collection<?> items) {
            final var out = new StringBuilder();
            var count = 0;
            for (final var item : items) {
                if (item instanceof Map<?, ?> map) {
                    final var row = projectRow(head, map);
                    if (!row.isBlank()) {
                        if (count++ > 0) {
                            out.append('\n');
                        }
                        out.append(row);
                    }
                } else {
                    if (count++ > 0) {
                        out.append('\n');
                    }
                    out.append(item);
                }
            }
            return out.toString().isBlank() ? "[]" : compactLines(out.toString());
        }
        if (parsed instanceof final Map<?, ?> map) {
            final var row = projectRow(head, map);
            if (!row.isBlank()) {
                return row;
            }
            // fall back to a shallow key=value dump
            final var out = new StringBuilder();
            map.forEach(
                    (k, v) -> out.append(k).append('=').append(compactValue(v)).append('\n'));
            return compactLines(out.toString());
        }
        return String.valueOf(parsed);
    }

    private static String projectRow(final String head, final Map<?, ?> map) {
        final var out = new StringBuilder();
        final var fields = fieldsFor(head);
        for (final var f : fields) {
            final var value = dig(map, f);
            if (value != null) {
                if (!out.isEmpty()) {
                    out.append("  ");
                }
                out.append(f).append('=').append(compactValue(value));
            }
        }
        // a table-mode row that carries a status/phase gets a marker on degraded states
        if (map.containsKey("status") && map.get("status") instanceof final Map<?, ?> status) {
            final var phase = dig(status, "phase");
            if (phase != null && !phase.equals("Running") && !phase.equals("Succeeded") && !phase.equals("Completed")) {
                out.append("  ⚠ ").append(phase);
            }
        }
        return out.toString();
    }

    private static Object dig(final Map<?, ?> map, final String path) {
        Object current = map;
        for (final var part : path.split("\\.")) {
            if (!(current instanceof final Map<?, ?> m)) {
                return null;
            }
            final Object value = m.get(part);
            if (value == null && !"metadata".equals(part)) {
                // tolerate missing k8s nesting but not missing keys
                return null;
            }
            current = value;
        }
        return current;
    }

    private static String compactValue(final Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof final Map<?, ?> map) {
            final var out = new StringBuilder("{");
            map.forEach(
                    (k, v) -> out.append(k).append(':').append(compactValue(v)).append(' '));
            return out.toString().stripTrailing() + "}";
        }
        if (value instanceof final Collection<?> c) {
            final var out = new StringBuilder("[");
            for (final var item : c) {
                out.append(compactValue(item)).append(' ');
            }
            return out.toString().stripTrailing() + "]";
        }
        return String.valueOf(value);
    }

    /** Which fields each command family projects; kubectl is by resource (second token after `get`). */
    private static String[] fieldsFor(final String head) {
        return switch (head) {
            case "kubectl", "k" -> new String[] {"metadata.name", "status.phase", "metadata.namespace"};
            case "docker" -> new String[] {"Id", "Image", "State.Status", "NetworkSettings.Ports"};
            case "gh" -> new String[] {"title", "state", "number", "headRefName", "url", "login", "body"};
            case "aws" -> new String[] {"name", "value", "type", "KeyName", "State.Name"};
            default -> new String[] {"name", "status", "image", "id"};
        };
    }

    private String headOf(final String command) {
        final var head = commandOutput.headToken(command);
        return head == null ? "" : head;
    }

    private static String compactLines(final String s) {
        return s.lines().filter(l -> !l.isBlank()).map(String::stripTrailing).collect(joining("\n"));
    }
}
