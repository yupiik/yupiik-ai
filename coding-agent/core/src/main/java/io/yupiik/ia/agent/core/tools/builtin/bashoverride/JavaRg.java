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
import java.nio.file.Path;
import java.util.List;

/** {@code rg} is treated as an alias of {@code grep} (same reimplementation, different command name). */
@ApplicationScoped
public class JavaRg implements JavaCommand {
    private final JavaGrep delegate = new JavaGrep();

    @Override
    public String name() {
        return "rg";
    }

    @Override
    public boolean supports(final Path cwd, final List<String> args) {
        return delegate.supports(cwd, args);
    }

    @Override
    public boolean run(final Path cwd, final List<String> args, final StringBuilder out) {
        return delegate.run(cwd, args, out);
    }
}
