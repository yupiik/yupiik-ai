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

/** Host-side actionable notification; successful delivery alone is not approval. */
public final class NotifySendApprovalUi extends BaseExecApprovalUi {
    public NotifySendApprovalUi(final Path executable) {
        super(executable, ResultPolicy.ALLOW_ACTION);
    }

    @Override
    protected List<String> commandLine(final Path executable, final String sanitizedText, final long budgetNanos) {
        final var millis =
                Math.clamp(budgetNanos / 1_000_000L + (budgetNanos % 1_000_000L == 0 ? 0 : 1), 1, Integer.MAX_VALUE);
        return List.of(
                executable.toString(),
                "--wait",
                "--action=allow=Allow",
                "--action=deny=Deny",
                "--expire-time=" + millis,
                "--",
                "Nono approval",
                sanitizedText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
    }
}
