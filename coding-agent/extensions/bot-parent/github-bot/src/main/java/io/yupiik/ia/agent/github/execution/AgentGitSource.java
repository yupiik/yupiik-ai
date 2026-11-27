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
package io.yupiik.ia.agent.github.execution;

import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Stages instructions, never source or credentials. Git and network access run only in the agent. */
public final class AgentGitSource {
    private static final Pattern REPOSITORY =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SHA = Pattern.compile("[A-Fa-f0-9]{40}");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final List<String> RESERVED =
            List.of("GH_TOKEN", "GH_AGENT_TOKEN", "GITHUB_BOT_FORK_TOKEN", "GITHUB_TOKEN");

    private static final BotTemplates TEMPLATES = createTemplates();

    private AgentGitSource() {}

    /**
     * Resolves an operator-managed, read-only source PAT without falling back to publishing credentials.
     * The returned map is immutable and must be transported as secret environment, never logged or written to disk.
     * This class neither mints nor revokes tokens.
     */
    public static Map<String, String> credentials(
            final WorkerOptions.Source source,
            final GithubBotConfiguration.Auth auth,
            final Map<String, String> environment) {
        if (source == null || source.tokenEnv() == null || source.tokenEnv().isEmpty()) {
            return Map.of();
        }
        final var name = source.tokenEnv();
        final var reserved = new HashSet<>(RESERVED);
        if (auth != null) {
            if (auth.tokenEnv() != null) {
                reserved.add(auth.tokenEnv());
            }
            if (auth.agentTokenEnv() != null) {
                reserved.add(auth.agentTokenEnv());
            }
        }
        if (!ENVIRONMENT_NAME.matcher(name).matches() || reserved.contains(name)) {
            throw new IllegalArgumentException("Source PAT requires a dedicated, valid environment variable");
        }
        final var token = environment.get(name);
        if (token == null || token.isBlank() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Configured source PAT is missing, blank or invalid");
        }
        for (final var other : reserved) {
            if (token.equals(environment.get(other))) {
                throw new IllegalArgumentException("Source PAT must differ from host, agent and fork credentials");
            }
        }
        return Map.of("GITHUB_SOURCE_TOKEN", token);
    }

    /**
     * Writes two secret-free POSIX shell scripts to {@code attempt/workspace} without executing Git.
     * Run {@code sh /attempt/workspace/github-source-git.sh init [directories...]} in the agent;
     * the default cone includes root files only. Subsequent operations are {@code expand directories...},
     * {@code deepen N} (additional generations) and {@code tags}. The checkout lives in workspace/repository.
     * Limits apply to helper operations, not arbitrary agent commands or tool-triggered lazy fetches.
     * The pre/post du check is a soft aggregate allocated-disk limit, not a hard quota: an operation can
     * temporarily exceed it and a rejected operation is not rolled back. Git, timeout and du are required.
     */
    public static void stage(
            final Path attempt, final String repository, final String headSha, final WorkerOptions.Source source)
            throws IOException {
        stage(attempt, repository, headSha, source, Path.of("/bin/sh"));
    }

    /** The interpreter must be executable inside the selected runtime, including a private Nono toolchain. */
    public static void stage(
            final Path attempt,
            final String repository,
            final String headSha,
            final WorkerOptions.Source source,
            final Path interpreter)
            throws IOException {
        final var shell = interpreter.toString();
        if (!interpreter.isAbsolute()
                || shell.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException(
                    "Source askpass requires an absolute shell interpreter path without whitespace");
        }
        if (repository == null
                || !REPOSITORY.matcher(repository).matches()
                || repository.endsWith("/.")
                || repository.endsWith("/..")
                || repository.endsWith("/.git")) {
            throw new IllegalArgumentException("Source repository must be a GitHub owner/repository name");
        }
        if (headSha == null || !SHA.matcher(headSha).matches()) {
            throw new IllegalArgumentException("Source checkout requires an exact 40-hex commit SHA");
        }
        if (source == null || source.maxDepth() < 1 || source.maxBytes() < 1 || source.commandTimeoutSeconds() < 1) {
            throw new IllegalArgumentException("Source depth, byte limit and command timeout must be positive");
        }
        final var workspace = attempt.toAbsolutePath().normalize().resolve("workspace");
        Path current = workspace.getRoot();
        for (final var part : workspace) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Source workspace must not contain symbolic links");
            }
        }
        Files.createDirectories(workspace);
        write(workspace.resolve("github-source-askpass.sh"), TEMPLATES.render("askpass", Map.of("shell", shell)));
        write(
                workspace.resolve("github-source-git.sh"),
                TEMPLATES.render(
                        "git",
                        Map.of(
                                "repository", repository,
                                "sha", headSha.toLowerCase(Locale.ROOT),
                                "depth", source.maxDepth(),
                                "bytes", source.maxBytes(),
                                "timeout", source.commandTimeoutSeconds())));
    }

    private static BotTemplates createTemplates() {
        return new BotTemplates(Map.of("askpass", """
                #!{{{shell}}}
                set +x
                set -eu
                # Git supplies the prompt; never print the token except as the askpass password response.
                [ -n "${GITHUB_SOURCE_TOKEN:-}" ] || exit 1
                case "${1:-}" in
                    "Username for 'https://github.com':"*) printf '%s\\n' x-access-token ;;
                    "Password for 'https://x-access-token@github.com':"*) printf '%s\\n' "$GITHUB_SOURCE_TOKEN" ;;
                    *) exit 1 ;;
                esac
                """, "git", """
                #!/bin/sh
                set +x
                set -eu
                umask 077
                # All interpolated constants are validated by the host; no credential values are staged.
                remote='https://github.com/{{{repository}}}.git'
                pinned='{{{sha}}}'
                max_depth={{{depth}}}
                max_bytes={{{bytes}}}
                seconds={{{timeout}}}
                workspace=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
                repo="$workspace/repository"
                askpass="$workspace/github-source-askpass.sh"
                fail() { printf '%s\\n' "$1" >&2; exit 1; }
                # Do not inherit injected Git configuration, paths, tracing or credential diagnostics.
                unset GIT_CONFIG GIT_CONFIG_PARAMETERS GIT_CONFIG_COUNT GIT_DIR GIT_WORK_TREE GIT_COMMON_DIR
                unset GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_INDEX_FILE GIT_SHALLOW_FILE
                unset GIT_TRACE GIT_TRACE_PACKET GIT_TRACE_PACK_ACCESS GIT_TRACE_SETUP GIT_TRACE_SHALLOW
                unset GIT_TRACE_PERFORMANCE GIT_TRACE_CURL GIT_CURL_VERBOSE GIT_TRACE2 GIT_TRACE2_EVENT GIT_TRACE2_PERF
                export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_SYSTEM=/dev/null GIT_CONFIG_GLOBAL=/dev/null
                export GIT_TERMINAL_PROMPT=0 GIT_ATTR_NOSYSTEM=1 GIT_LFS_SKIP_SMUDGE=1 LC_ALL=C
                export GIT_ASKPASS="$askpass"
                g() {
                    timeout "$seconds" git -c credential.helper= -c core.hooksPath=/dev/null \\
                        -c core.askPass="$askpass" -c http.followRedirects=false \\
                        -c protocol.allow=never -c protocol.https.allow=always "$@"
                }
                check_size() {
                    if [ -d "$repo" ]; then
                        usage=$(timeout "$seconds" du -sk "$repo")
                        usage=${usage%%[[:space:]]*}
                        [ "$usage" -le "$((max_bytes / 1024))" ] || fail 'Source soft disk limit exceeded'
                    fi
                }
                paths() {
                    for path in "$@"; do
                        case "$path" in
                            ''|-*|/*|*/|*//*|.|..|./*|../*|*/./*|*/../*|*/.|*/..|.git|.git/*|*/.git|*/.git/*)
                                fail 'Sparse paths must be relative repository directories' ;;
                            *'
                '*|*'\r'*) fail 'Sparse paths must not contain line breaks' ;;
                        esac
                    done
                }
                verify() {
                    actual=$(g -C "$repo" rev-parse --verify FETCH_HEAD)
                    [ "$actual" = "$pinned" ] || fail 'Fetched commit does not match pinned SHA'
                }
                initialized() {
                    [ -d "$repo/.git" ] && [ -f "$repo/.git/github-source-depth" ] || fail 'Run init first'
                    [ "$(g -C "$repo" config --get remote.origin.url)" = "$remote" ] || fail 'Source origin changed'
                    depth=$(cat "$repo/.git/github-source-depth")
                    case "$depth" in ''|*[!0-9]*) fail 'Invalid source depth state' ;; esac
                    [ "$depth" -ge 1 ] && [ "$depth" -le "$max_depth" ] || fail 'Invalid source depth state'
                }
                command=${1:-}
                [ "$#" -gt 0 ] || fail 'Expected init, expand, deepen or tags'
                shift
                check_size
                case "$command" in
                    init)
                        paths "$@"
                        [ ! -e "$repo" ] && [ ! -L "$repo" ] || fail 'Source repository already exists'
                        g init --template= -- "$repo"
                        g -C "$repo" remote add origin "$remote"
                        # Persist only non-secret safety/partial-clone settings, including askpass for build lazy fetches.
                        g -C "$repo" config core.hooksPath /dev/null
                        g -C "$repo" config core.askPass "$askpass"
                        g -C "$repo" config credential.helper ''
                        g -C "$repo" config credential.useHttpPath false
                        g -C "$repo" config http.followRedirects false
                        g -C "$repo" config protocol.allow never
                        g -C "$repo" config protocol.https.allow always
                        g -C "$repo" config remote.origin.promisor true
                        g -C "$repo" config remote.origin.partialclonefilter blob:none
                        g -C "$repo" fetch --no-tags --filter=blob:none --depth=1 origin "$pinned"
                        verify
                        g -C "$repo" sparse-checkout init --cone
                        g -C "$repo" sparse-checkout set -- "$@"
                        g -C "$repo" -c advice.detachedHead=false checkout --detach "$pinned"
                        [ "$(g -C "$repo" rev-parse HEAD)" = "$pinned" ] || fail 'Checkout does not match pinned SHA'
                        printf '%s\\n' 1 > "$repo/.git/github-source-depth"
                        ;;
                    expand)
                        [ "$#" -gt 0 ] || fail 'Expected sparse directories'
                        paths "$@"
                        initialized
                        g -C "$repo" sparse-checkout add -- "$@"
                        ;;
                    deepen)
                        [ "$#" -eq 1 ] || fail 'Expected one positive deepen count'
                        case "$1" in ''|*[!0-9]*|0* ) fail 'Expected a positive deepen count' ;; esac
                        # Length check also prevents shell arithmetic overflow on untrusted input.
                        [ "${#1}" -le 10 ] && [ "$1" -le "$max_depth" ] || fail 'Source depth limit exceeded'
                        initialized
                        [ "$1" -le "$((max_depth - depth))" ] || fail 'Source depth limit exceeded'
                        next=$((depth + $1))
                        # Absolute depth also bounds retries after an interrupted or partially successful fetch.
                        g -C "$repo" fetch --no-tags --filter=blob:none --depth="$next" origin "$pinned"
                        verify
                        printf '%s\\n' "$next" > "$repo/.git/github-source-depth"
                        ;;
                    tags)
                        [ "$#" -eq 0 ] || fail 'Tags takes no arguments'
                        initialized
                        g -C "$repo" fetch --tags --filter=blob:none --depth="$depth" origin "$pinned"
                        verify
                        ;;
                    *) fail 'Expected init, expand, deepen or tags' ;;
                esac
                check_size
                """));
    }

    private static void write(final Path target, final String script) throws IOException {
        Files.writeString(target, script, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
    }
}
