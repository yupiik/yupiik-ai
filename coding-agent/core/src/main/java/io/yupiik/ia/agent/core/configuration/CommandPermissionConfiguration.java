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
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.Arrays;
import java.util.List;

/**
 * A single {@code yca.permissions.bash.rules} entry: how to recognize a bash command family
 * and which permission mode applies to it in plan and build modes.
 *
 * <p>Example: allow {@code docker run} in build mode while keeping plan mode asking for it:</p>
 * <pre>{@code
 * { "matcher": { "commandStart": { "options": ["docker", "run"] } }, "plan": "ask", "default": "all" }
 * }</pre>
 */
@JsonModel
public record CommandPermissionConfiguration(
        @Property(
                documentation =
                        "How to recognize the command: leading tokens (`commandStart`) and options rejected even when they match (`excludeOptions`).")
        CommandStartMatcherConfiguration matcher,

        @Property(
                documentation =
                        "Permission mode applied in plan mode when the matcher matches (ask/auto_edit/yolo, `all` = yolo)."
                                + " Absent = same as `default`.")
        PermissionMode plan,

        @JsonProperty("default")
        @Property(
                value = "default",
                documentation =
                        "Permission mode applied in build mode when the matcher matches (ask/auto_edit/yolo, `all` = yolo)."
                                + " Absent (null) = inherit `plan`.")
        PermissionMode defaultMode) {

    /**
     * Built-in read-only command families: recognized and allowed without prompting in both plan and build modes.
     *
     * State-changing forms are carved out with {@code excludeOptions} where the command itself is commonly
     * used for both inspection and mutation (eg. {@code sed -i}, {@code find -delete}, {@code git branch -d},
     * {@code git remote add}, {@code sort -o}, {@code diff -o}, {@code date -s}).
     */
    public static List<CommandPermissionConfiguration> defaultRules() {
        return List.of(
                // -----------------------------------------------------------------
                // Git: inspection / read-only operations
                // -----------------------------------------------------------------
                command("git status"),
                command("git diff"),
                command("git log"),
                command("git show"),
                command("git blame"),
                command("git rev-parse"),
                command("git merge-base"),
                command("git ls-files"),
                command("git cat-file"),
                command("git ls-tree"),
                command("git for-each-ref"),
                command("git name-rev"),
                command("git describe"),
                command("git stash list"),
                command("git submodule status"),
                command("git reflog"),
                command("git shortlog"),
                command("git count-objects"),
                command("git fsck"),
                command("git verify-commit"),
                command("git verify-tag"),
                command("git rev-list"),
                command("git cherry"),
                command("git range-diff"),
                command("git diff-tree"),
                command("git diff-files"),
                command("git diff-index"),
                command("git patch-id"),
                command("git check-ignore"),
                command("git check-attr"),
                command("git check-ref-format"),
                command("git hash-object"),
                command("git ls-remote"),

                // Git command families with mutating forms explicitly excluded.
                command("git branch", "-d", "-D", "--delete", "-m", "-M", "--move", "-c", "-C", "--copy"),
                command("git remote", "add", "remove", "set-url", "rename", "prune"),
                command("git stash", "push", "pop", "apply", "drop", "clear", "branch"),
                command("git tag", "-d", "--delete", "-f", "--force"),
                command("git submodule", "add", "update", "init", "deinit", "set-url", "sync"),

                // -----------------------------------------------------------------
                // Archives: read-only inspection (listing / metadata / extract-to-stdout)
                // -----------------------------------------------------------------
                // Only the listing prefixes below are allowlisted: extraction, creation
                // and update forms (`unzip app.zip`, `jar xf`, `tar xzf`, ...) match no
                // prefix and stay gated. Bundled flags beyond these (eg. `tar -tf`,
                // `jar tzvf`) are not covered by the defaults - add a rule in
                // yca.permissions.bash.rules if a workspace needs them.
                command("unzip -l"),
                command("unzip -p"),
                command("unzip -Z"),
                command("zipinfo"),
                command("jar t"),
                command("jar tf"),
                command("jar tvf"),
                command("tar t"),
                command("tar tf"),
                command("tar tvf"),
                command("tar tzf"),

                // -----------------------------------------------------------------
                // Filesystem inspection
                // -----------------------------------------------------------------
                command("ls"),
                command("pwd"),
                command("cat"),
                command("tac"),
                command("head"),
                command("tail"),
                command("nl"),
                command("wc"),
                command("stat"),
                command("file"),
                command("readlink"),
                command("realpath"),
                command("basename"),
                command("dirname"),
                command("du"),
                command("df"),
                command("tree"),
                command("lsattr"),
                command("getfacl"),

                // find is read-only unless one of these actions is requested.
                command(
                        "find",
                        "-delete",
                        "-exec",
                        "-execdir",
                        "-ok",
                        "-okdir",
                        "-fls",
                        "-fprint",
                        "-fprint0",
                        "-fprintf"),

                // -----------------------------------------------------------------
                // Text processing / filtering
                // -----------------------------------------------------------------
                command("awk"),
                command("cut"),
                command("tr"),
                command("grep"),
                command("egrep"),
                command("fgrep"),
                command("rg"),
                command("uniq"),
                command("comm"),
                command("join"),
                command("paste"),
                command("column"),
                command("fold"),
                command("fmt"),
                command("expand"),
                command("unexpand"),
                command("od"),
                command("xxd"),
                command("strings"),
                command("shuf"),
                command("rev"),

                // jq is a pure JSON filter: stdin/files to stdout, it has no in-place or
                // output-file option (writing needs a shell redirect, already gated).
                command("jq"),

                // sed is read-only except for in-place editing.
                command("sed", "-i", "--in-place"),

                // sort is read-only except when an output file is explicitly requested.
                command("sort", "-o", "--output"),

                // diff is read-only except for output-file options.
                command("diff", "-o", "--output"),
                command("diff3"),
                command("sdiff"),
                command("cmp"),

                // -----------------------------------------------------------------
                // Search / predicates
                // -----------------------------------------------------------------
                command("which"),
                command("whereis"),
                command("locate"),
                command("test"),
                command("["),
                command("[["),

                // -----------------------------------------------------------------
                // Shell-safe output / argument utilities
                // -----------------------------------------------------------------
                command("echo"),
                command("printf"),
                command("seq"),
                command("yes"),

                // -----------------------------------------------------------------
                // Status / no-op utilities: exit 0 / exit 1 predicates with no side effects
                // -----------------------------------------------------------------
                command("true"),
                command("false"),

                // -----------------------------------------------------------------
                // Environment / identity / system inspection
                // -----------------------------------------------------------------
                command("env"),
                command("printenv"),
                command("id"),
                command("groups"),
                command("whoami"),
                command("who"),
                command("w"),
                command("users"),
                command("uname"),
                command("hostname"),
                command("uptime"),
                command("ps"),
                command("pgrep"),
                command("lsof"),
                command("free"),
                command("vmstat"),
                command("iostat"),
                command("mpstat"),
                command("lsblk"),
                command("lsusb"),
                command("lspci"),
                command("mountpoint"),
                command("dmesg"),

                // -----------------------------------------------------------------
                // Checksums / encoding / numeric utilities
                // -----------------------------------------------------------------
                command("cksum"),
                command("sum"),
                command("md5sum"),
                command("sha1sum"),
                command("sha224sum"),
                command("sha256sum"),
                command("sha384sum"),
                command("sha512sum"),
                command("b2sum"),
                command("base32"),
                command("base64"),
                command("bc"),
                command("expr"),
                command("factor"),
                command("numfmt"),
                command("cal"),

                // date is read-only unless setting the system clock.
                command("date", "-s", "--set"),

                // -----------------------------------------------------------------
                // GitHub CLI: read-only inspection families
                // -----------------------------------------------------------------
                command("gh pr view"),
                command("gh pr checks"),
                command("gh pr diff"),
                command("gh pr list"),
                command("gh issue view"),
                command("gh issue list"),
                command("gh run list"),
                command("gh run view"),
                command("gh run watch"),
                command("gh api"),

                // -----------------------------------------------------------------
                // Kubernetes / Docker: read-only inspection families
                // -----------------------------------------------------------------
                command("kubectl get"),
                command("kubectl describe"),
                command("kubectl logs"),
                command("kubectl explain"),
                command("kubectl version"),
                command("kubectl api-resources"),
                command("kubectl top"),
                command("docker ps"),
                command("docker images"),
                command("docker logs"),
                command("docker inspect"),
                command("docker version"),
                command("docker compose ps"),

                // -----------------------------------------------------------------
                // Java
                // -----------------------------------------------------------------
                command("java -version"),
                command("java -help"));
    }

    private static CommandPermissionConfiguration command(final String commandStart, final String... excludedOptions) {

        final var excludes = Arrays.stream(excludedOptions)
                .map(option -> new CommandOptionsConfiguration(List.of(option)))
                .toList();

        return new CommandPermissionConfiguration(
                new CommandStartMatcherConfiguration(new CommandOptionsConfiguration(List.of(commandStart)), excludes),
                PermissionMode.YOLO,
                null);
    }
}
