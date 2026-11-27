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
package io.yupiik.ia.agent.core.tools.builtin.bashoverride;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Faithful {@code head [-n N | -N | -c N] <file>} reimplementation for a single file. */
@ApplicationScoped
public class JavaHead implements JavaCommand {
    @Override
    public String name() {
        return "head";
    }

    @Override
    public boolean supports(final Path cwd, final List<String> args) {
        if (args.isEmpty()) {
            return false;
        }
        final var file = args.get(args.size() - 1);
        if (file.startsWith("-")) {
            return false;
        }
        for (var i = 0; i < args.size() - 1; i++) {
            final var a = args.get(i);
            if ("-n".equals(a) || "--lines".equals(a) || "-c".equals(a) || "--bytes".equals(a)) {
                if (i + 1 >= args.size() - 1) {
                    return false;
                }
                i++;
            } else if (!(a.startsWith("-")
                    && a.length() > 1
                    && BashRegex.NON_NEG_INT.matcher(a.substring(1)).matches())) {
                return false; // unsupported option
            }
        }
        return true;
    }

    @Override
    public boolean run(final Path cwd, final List<String> args, final StringBuilder out) {
        final var file = Paths.resolve(cwd, args.get(args.size() - 1));
        if (!Files.isRegularFile(file)) {
            return true;
        }
        long lines = Long.MAX_VALUE;
        long bytes = -1;
        for (var i = 0; i < args.size() - 1; i++) {
            final var a = args.get(i);
            if (a.startsWith("-")
                    && BashRegex.NON_NEG_INT.matcher(a.substring(1)).matches()) {
                lines = Long.parseLong(a.substring(1));
            } else if ("-c".equals(a) || "--bytes".equals(a)) {
                bytes = Long.parseLong(args.get(++i));
            } else if ("-n".equals(a) || "--lines".equals(a)) {
                lines = Long.parseLong(args.get(++i));
            }
        }
        try {
            if (bytes >= 0) {
                final var data = Files.readAllBytes(file);
                out.append(new String(data, 0, (int) Math.min(bytes, data.length), StandardCharsets.UTF_8));
                return true;
            }
            try (final var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                long count = 0;
                while (count < lines && (line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                    count++;
                }
            }
        } catch (final Exception e) {
            // skip unreadable file
        }
        return true;
    }
}
