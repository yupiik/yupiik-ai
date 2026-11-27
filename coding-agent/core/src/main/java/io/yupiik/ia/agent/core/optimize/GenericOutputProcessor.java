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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class GenericOutputProcessor implements OutputProcessor {
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]|\u001B\\][^\u0007]*\u0007");

    private final CodingAgentConfiguration configuration;

    public GenericOutputProcessor(final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
    }

    protected GenericOutputProcessor() {
        this.configuration = null;
    }

    @Override
    public boolean supports(final String command) {
        return true;
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public Set<String> names() {
        return Set.of("generic");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var all =
                compact(lines.map(ANSI::matcher).map(m -> m.replaceAll("")).toList());
        final var max = Math.max(10, configuration.optimize().genericMaxLines());
        if (all.size() <= max) {
            return all.stream();
        }
        final var head = (int) (max * 0.6);
        final var tail = max - head;
        final var out = new ArrayList<String>(max + 1);
        out.addAll(all.subList(0, head));
        out.add("[... " + (all.size() - head - tail) + " lines truncated ...]");
        out.addAll(all.subList(all.size() - tail, all.size()));
        return out.stream();
    }

    /**
     * Trims trailing whitespace of each line, drops leading and trailing blank lines
     * and collapses runs of blank lines into a single blank line.
     */
    private List<String> compact(final List<String> lines) {
        final var out = new ArrayList<String>(lines.size());
        var pendingBlank = false;
        for (final var raw : lines) {
            final var line = stripTrailingWhitespace(raw);
            if (line.isBlank()) {
                if (!out.isEmpty()) {
                    pendingBlank = true;
                }
                continue;
            }
            if (pendingBlank) {
                out.add("");
                pendingBlank = false;
            }
            out.add(line);
        }
        return out;
    }

    private String stripTrailingWhitespace(final String line) {
        var end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return end == line.length() ? line : line.substring(0, end);
    }
}
