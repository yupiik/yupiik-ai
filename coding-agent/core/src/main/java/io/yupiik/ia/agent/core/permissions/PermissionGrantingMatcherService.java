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
package io.yupiik.ia.agent.core.permissions;

import java.util.List;
import java.util.Optional;

/**
 * SPI customizing the automatic permission-anchor selection of a bash command
 * when the user allows it. Injected as a {@code List} into
 * {@link PermissionService} and consulted in {@link #order()} ascending order;
 * the FIRST service returning a non-empty result decides the anchor(s)
 * recorded for the approved command (each segmented command contributes one
 * anchor per segment).
 *
 * <p>A service returning {@code Optional.empty()} means "I do not want to
 * decide"; the resolution falls through to the next service, then to the
 * matched global bash-rule prefix ({@code yca.permissions.bash.rules}), then
 * to the command name (first token).</p>
 */
public interface PermissionGrantingMatcherService {

    /**
     * @param fullCommand the whole bash command as tokens (not per segment), e.g.
     *                    {@code ["docker", "run", "nginx"]}
     * @return the anchors to record for this command (e.g. {@code ["docker", "run"]}),
     *         or {@code Optional.empty()} when this service does not decide
     */
    Optional<List<String>> grantedPermissions(List<String> fullCommand);

    /** Consultation order: ascending, the first non-empty result wins. */
    default int order() {
        return 1_000;
    }
}
