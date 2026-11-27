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
public class NpmOutputProcessor implements OutputProcessor {
    private final CommandOutputPostProcessor commandOutput;

    public NpmOutputProcessor(final CommandOutputPostProcessor commandOutput) {
        this.commandOutput = commandOutput;
    }

    public NpmOutputProcessor() {
        this(new CommandOutputPostProcessor());
    }

    @Override
    public boolean supports(final String command) {
        return commandOutput.matchesHead(command, "npm", "pnpm", "yarn", "bun");
    }

    @Override
    public int order() {
        return 14;
    }

    @Override
    public Set<String> names() {
        return Set.of("npm", "pnpm", "yarn", "bun");
    }

    @Override
    public Stream<String> process(final String command, final Stream<String> lines) {
        final var out = new ArrayList<String>();
        for (final var raw : lines.toList()) {
            final var line = commandOutput.stripAnsi(raw).strip();
            if (line.isEmpty() || line.startsWith("> ")) {
                continue;
            }
            if (line.startsWith("npm warn")
                    || line.startsWith("npm notice")
                    || line.startsWith("npm WARN")
                    || line.startsWith("pnpm warn")
                    || line.startsWith("pnpm notice")) {
                continue;
            }
            out.add(line);
        }
        if (out.isEmpty()) {
            return Stream.of("ok");
        }
        return out.stream();
    }
}
