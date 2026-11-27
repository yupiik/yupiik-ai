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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Faithful {@code cat <files>...} (no options) reimplementation: prints each file's bytes verbatim. */
@ApplicationScoped
public class JavaCat implements JavaCommand {
    @Override
    public String name() {
        return "cat";
    }

    @Override
    public boolean supports(final Path cwd, final List<String> args) {
        return !args.isEmpty() && args.stream().allMatch(a -> !a.startsWith("-"));
    }

    @Override
    public boolean run(final Path cwd, final List<String> args, final StringBuilder out) {
        for (final var arg : args) {
            final var p = Paths.resolve(cwd, arg);
            if (!Files.isRegularFile(p)) {
                continue; // matches default cat (no error report, survey-style)
            }
            try {
                out.append(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            } catch (final IOException e) {
                // skip unreadable file
            }
        }
        return true;
    }
}
