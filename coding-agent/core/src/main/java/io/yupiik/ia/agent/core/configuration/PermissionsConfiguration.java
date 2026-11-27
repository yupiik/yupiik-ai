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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

@JsonModel
public record PermissionsConfiguration(
        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.PermissionMode.ASK",
                documentation =
                        "Permission mode: ask, auto_edit (edits allowed, bash asks), yolo or all (tool approvals bypassed, configured denies and sandbox retained), full_yolo (all tool permissions and sandbox bypassed).")
        PermissionMode mode,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation = "Tool name or bash command prefixes always allowed. Comma separated, eg. `read,grep`."
                        + " For bash commands the patterns are matched against every segment of the command"
                        + " (both bare, eg. `mvn *`, and `bash:` prefixed forms); every segment must match for"
                        + " the command to be allowed.")
        List<String> allow,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Tool name or bash command prefixes always denied. Comma separated, eg. `bash:rm -rf *`."
                                + " For bash commands the patterns are matched against every segment of the command"
                                + " (both bare, eg. `rm *`, and `bash:` prefixed forms).")
        List<String> deny,

        @Property(
                documentation =
                        "Bash command rules: recognized command families (eg. `git status`, `sed` with `-i` excluded)"
                                + " allowed without prompting depending on the rule mode. See"
                                + " `yca.permissions.bash.rules` for the full syntax.")
        BashPermissionsConfiguration bash) {

    public boolean isAllowed(final String name) {
        return allow != null && allow.stream().anyMatch(a -> a != null && matches(a, name));
    }

    public boolean isDenied(final String name) {
        return deny != null && deny.stream().anyMatch(d -> d != null && matches(d, name));
    }

    private static boolean matches(final String pattern, final String name) {
        final var p = pattern.strip();
        return p.equals(name) || (p.endsWith("*") && name.startsWith(p.substring(0, p.length() - 1)));
    }
}
