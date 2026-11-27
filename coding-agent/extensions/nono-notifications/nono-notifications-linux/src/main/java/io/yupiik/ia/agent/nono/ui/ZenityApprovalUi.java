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
package io.yupiik.ia.agent.nono.ui;

import java.nio.file.Path;
import java.util.List;

/** Host-side Zenity dialog; only successful confirmation grants approval. */
public final class ZenityApprovalUi extends BaseExecApprovalUi {
    public ZenityApprovalUi(final Path executable) {
        super(executable, ResultPolicy.EXIT_ZERO);
    }

    @Override
    protected List<String> commandLine(final Path executable, final String sanitizedText, final long budgetNanos) {
        final var seconds = Math.max(1, budgetNanos / 1_000_000_000L + (budgetNanos % 1_000_000_000L == 0 ? 0 : 1));
        return List.of(
                executable.toString(),
                "--question",
                "--no-markup",
                "--title=Nono approval",
                "--ok-label=Allow",
                "--cancel-label=Deny",
                "--text=" + sanitizedText,
                "--timeout=" + seconds);
    }
}
