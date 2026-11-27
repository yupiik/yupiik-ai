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
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Stream;

@ApplicationScoped
public class PrettierOutputProcessor implements OutputProcessor {
    private static final int MAX_FILES = 20;
    private final CommandOutputPostProcessor commandOutput;

    public PrettierOutputProcessor(final CommandOutputPostProcessor commandOutput) {
        this.commandOutput = commandOutput;
    }

    public PrettierOutputProcessor() {
        this(new CommandOutputPostProcessor());
    }

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHead(command, "prettier");
    }

    @Override
    public int order() {
        return 15;
    }

    @Override
    public Set<String> names() {
        return Set.of("prettier");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var all = lines.map(commandOutput::stripAnsi).toList();
        final var check = commandOutput.containsFlag(command, "--check", "-c");
        final var paths = all.stream()
                .filter(l -> !l.isBlank() && !l.contains("error") && !l.contains("Error") && !l.startsWith("["))
                .map(String::strip)
                .toList();
        if (all.stream().anyMatch(l -> l.contains("error") || l.contains("Error"))) {
            return all.stream();
        }
        if (paths.isEmpty()) {
            return Stream.of(check ? "all files already formatted" : "ok");
        }
        final var out = new ArrayList<String>();
        out.add((check ? paths.size() + " file(s) need formatting:" : "Formatted " + paths.size() + " file(s):"));
        final var cap = Math.min(paths.size(), MAX_FILES);
        for (var i = 0; i < cap; i++) {
            out.add("  " + paths.get(i));
        }
        if (paths.size() > cap) {
            out.add("  ... +" + (paths.size() - cap) + " more");
        }
        return out.stream();
    }
}
