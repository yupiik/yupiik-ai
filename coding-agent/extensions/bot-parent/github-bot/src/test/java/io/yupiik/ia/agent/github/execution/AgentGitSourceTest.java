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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.github.configuration.GithubBotConfiguration;
import io.yupiik.ia.agent.github.configuration.WorkerOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentGitSourceTest {
    private static final String REMOTE = "https://github.com/acme/repo.git";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final GithubBotConfiguration.Auth AUTH =
            new GithubBotConfiguration.Auth(GithubBotConfiguration.AuthType.pat, "HOST_PAT", "", 0, "", "AGENT_PAT");

    @TempDir
    Path temporary;

    @Test
    void credentialsAreExplicitDedicatedImmutableAndNeverFallBack() {
        assertEquals(Map.of(), AgentGitSource.credentials(options(""), AUTH, Map.of("HOST_PAT", "host")));
        final var credentials = AgentGitSource.credentials(
                options("READ_ONLY_PAT"), AUTH, Map.of("READ_ONLY_PAT", "source", "HOST_PAT", "host"));
        assertEquals(Map.of("GITHUB_SOURCE_TOKEN", "source"), credentials);
        assertThrows(UnsupportedOperationException.class, () -> credentials.put("other", "value"));
        for (final var name : List.of(
                "HOST_PAT",
                "AGENT_PAT",
                "GH_TOKEN",
                "GH_AGENT_TOKEN",
                "GITHUB_BOT_FORK_TOKEN",
                "GITHUB_TOKEN",
                "BAD-NAME",
                "9BAD",
                "BAD\nNAME",
                " ")) {
            final var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> AgentGitSource.credentials(options(name), AUTH, Map.of(name, "never-print-me")));
            assertFalse(error.getMessage().contains("never-print-me"));
            assertEquals("Source PAT requires a dedicated, valid environment variable", error.getMessage());
        }
        for (final var token : List.of("", " ", "\t", "secret\rvalue", "secret\nvalue")) {
            final var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> AgentGitSource.credentials(
                            options("READ_ONLY_PAT"), AUTH, Map.of("READ_ONLY_PAT", token, "HOST_PAT", "host")));
            assertEquals("Configured source PAT is missing, blank or invalid", error.getMessage());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentGitSource.credentials(options("READ_ONLY_PAT"), AUTH, Map.of("GH_TOKEN", "no-fallback")));
        for (final var other : List.of(
                "HOST_PAT", "AGENT_PAT", "GH_TOKEN", "GH_AGENT_TOKEN", "GITHUB_BOT_FORK_TOKEN", "GITHUB_TOKEN")) {
            final var error = assertThrows(
                    IllegalArgumentException.class,
                    () -> AgentGitSource.credentials(
                            options("READ_ONLY_PAT"),
                            AUTH,
                            Map.of("READ_ONLY_PAT", "same-secret", other, "same-secret")));
            assertFalse(error.getMessage().contains("same-secret"));
        }
    }

    @Test
    void stagingOnlyWritesSecretFreeScriptsAndValidatesUntrustedIdentifiers() throws Exception {
        final var attempt = temporary.resolve("attempt");
        AgentGitSource.stage(attempt, "acme/repo", SHA.toUpperCase(), options("READ_ONLY_PAT"));
        assertFalse(Files.exists(attempt.resolve("workspace/repository")));
        final var helper = Files.readString(attempt.resolve("workspace/github-source-git.sh"));
        assertTrue(helper.contains("remote='" + REMOTE + "'"));
        assertTrue(helper.contains("pinned='" + SHA + "'"));
        assertTrue(helper.contains("max_depth=3\nmax_bytes=536870912\nseconds=10\n"));
        assertTrue(helper.contains("usage=${usage%%[[:space:]]*}"));
        assertTrue(helper.contains("command=${1:-}"));
        assertTrue(helper.contains("-c core.askPass=\"$askpass\""));
        assertFalse(helper.contains("READ_ONLY_PAT"));
        assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(attempt.resolve("workspace/github-source-askpass.sh")));
        for (final var repository : List.of(
                REMOTE,
                "acme/repo?token=secret",
                "acme/../repo",
                "acme/.",
                "acme/..",
                "-acme/repo",
                "acme/repo\n",
                "acme/repo'",
                "acme/repo;id",
                "acme/repo/other",
                "acme/.git",
                "acme-/repo",
                "a--b/repo")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AgentGitSource.stage(temporary.resolve("invalid"), repository, SHA, options("")));
        }
        for (final var sha : List.of("main", SHA + "0", SHA.substring(1), SHA + "\n", "g".repeat(40))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AgentGitSource.stage(temporary.resolve("invalid"), "acme/repo", sha, options("")));
        }
        assertFalse(Files.exists(temporary.resolve("invalid")));
        final var outside = Files.createDirectory(temporary.resolve("outside"));
        final var link = Files.createSymbolicLink(temporary.resolve("link"), outside);
        assertThrows(IOException.class, () -> AgentGitSource.stage(link, "acme/repo", SHA, options("")));
        assertThrows(IOException.class, () -> AgentGitSource.stage(attempt, "acme/repo", SHA, options("")));
        for (final var repository : List.of("acme/.github", "acme/-repo", "acme/_repo")) {
            final var named = temporary.resolve("valid-" + repository.substring(repository.indexOf('/') + 1));
            AgentGitSource.stage(named, repository, SHA, options(""));
            assertTrue(Files.readString(named.resolve("workspace/github-source-git.sh"))
                    .contains("https://github.com/" + repository + ".git"));
        }
    }

    @Test
    void templateValuesAreNotEscapedOrRenderedAgain() throws Exception {
        final var attempt = temporary.resolve("literal-template-values");
        final var shell = Path.of("/runtime/{{literal}}&'sh");
        AgentGitSource.stage(attempt, "acme/repo", SHA, options("READ_ONLY_PAT"), shell);
        final var askpass = Files.readString(attempt.resolve("workspace/github-source-askpass.sh"));
        assertTrue(askpass.startsWith("#!" + shell + "\n"));
        assertTrue(askpass.contains("[ -n \"${GITHUB_SOURCE_TOKEN:-}\" ] || exit 1"));
        assertTrue(askpass.contains("printf '%s\\n' \"$GITHUB_SOURCE_TOKEN\""));
    }

    @Test
    void askpassUsesOnlyEnvironmentAndOnlyForCanonicalGithubPrompts() throws Exception {
        final var attempt = temporary.resolve("askpass");
        AgentGitSource.stage(attempt, "acme/repo", SHA, options("READ_ONLY_PAT"));
        final var askpass = attempt.resolve("workspace/github-source-askpass.sh");
        final var env = Map.of("GITHUB_SOURCE_TOKEN", "dedicated-secret");
        assertEquals(
                "x-access-token",
                success(temporary, env, "sh", askpass.toString(), "Username for 'https://github.com':")
                        .trim());
        assertEquals(
                "dedicated-secret",
                success(temporary, env, "sh", askpass.toString(), "Password for 'https://x-access-token@github.com':")
                        .trim());
        final var unknown = run(temporary, env, "sh", askpass.toString(), "Password for 'https://evil.example':");
        assertNotEquals(0, unknown.exit());
        assertFalse(unknown.output().contains("dedicated-secret"));
        assertNotEquals(
                0,
                run(temporary, Map.of(), "sh", askpass.toString(), "Username for 'https://github.com':")
                        .exit());
        final var traced = run(temporary, env, "sh", "-x", askpass.toString(), "Password for 'https://evil.example':");
        assertFalse(traced.output().contains("dedicated-secret"));
        assertFalse(Files.readString(askpass).contains("dedicated-secret"));
    }

    @Test
    void gitInvokesAskpassSafelyWithSpacesApostrophesAndShellMetacharacters() throws Exception {
        final var fixture = fixture();
        final var attempt = temporary.resolve("space apostrophe' $(touch injected)");
        AgentGitSource.stage(attempt, "acme/repo", fixture.sha(), options("READ_ONLY_PAT"));
        final var environment = new HashMap<>(fixture.environment());
        environment.put("GITHUB_SOURCE_TOKEN", "dedicated-secret");
        helper(attempt, environment, "init");
        final var repo = attempt.resolve("workspace/repository");
        final var command = git(repo, "config", "--get", "core.askPass").strip();
        for (final var throughEnvironment : List.of(false, true)) {
            if (throughEnvironment) {
                environment.put("GIT_ASKPASS", command);
            }
            final var filled = success(
                    repo,
                    environment,
                    "sh",
                    "-c",
                    "printf 'protocol=https\\nhost=github.com\\n\\n' | git -c credential.helper= credential fill");
            assertTrue(filled.contains("username=x-access-token"));
            assertTrue(filled.contains("password=" + environment.get("GITHUB_SOURCE_TOKEN")));
        }
        assertFalse(Files.exists(repo.resolve("injected")));
        assertFalse(Files.exists(temporary.resolve("injected")));
        assertFalse(Files.readString(repo.resolve(".git/config")).contains("dedicated-secret"));
    }

    @Test
    void supportsTrustedRuntimeShellWithoutGrantingHostShellAccess() throws Exception {
        final var shell = temporary.resolve("runtime-sh");
        Files.writeString(shell, "#!/bin/sh\nexec /bin/sh \"$@\"\n");
        Files.setPosixFilePermissions(shell, PosixFilePermissions.fromString("rwx------"));
        final var attempt = temporary.resolve("runtime-shell");
        AgentGitSource.stage(attempt, "acme/repo", SHA, options("READ_ONLY_PAT"), shell);
        final var askpass = attempt.resolve("workspace/github-source-askpass.sh");
        assertTrue(Files.readString(askpass).startsWith("#!" + shell + "\n"));
        assertEquals(
                "dedicated-secret",
                success(
                                temporary,
                                Map.of("GITHUB_SOURCE_TOKEN", "dedicated-secret"),
                                askpass.toString(),
                                "Password for 'https://x-access-token@github.com':")
                        .strip());
        for (final var invalid :
                List.of(Path.of("relative/sh"), Path.of("/runtime space/sh"), Path.of("/runtime\n/sh"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AgentGitSource.stage(
                            temporary.resolve("invalid-shell"), "acme/repo", SHA, options(""), invalid));
        }
    }

    @Test
    void realPartialShallowPinnedSparseRepositorySupportsExpansionHistoryTagsAndBuildMetadata() throws Exception {
        final var fixture = fixture();
        final var attempt = temporary.resolve("attempt");
        AgentGitSource.stage(attempt, "acme/repo", fixture.sha(), options(""));
        final var repo = attempt.resolve("workspace/repository");
        final var environment = fixture.environment();
        helper(attempt, environment, "init");
        assertEquals(fixture.sha(), git(repo, "rev-parse", "HEAD").trim());
        assertNotEquals(
                0, run(repo, Map.of(), "git", "symbolic-ref", "-q", "HEAD").exit());
        assertEquals("true", git(repo, "rev-parse", "--is-shallow-repository").trim());
        assertEquals("1", git(repo, "rev-list", "--count", "HEAD").trim());
        assertEquals(REMOTE, git(repo, "remote", "get-url", "origin").trim());
        assertEquals("true", git(repo, "config", "remote.origin.promisor").trim());
        assertEquals(
                "blob:none",
                git(repo, "config", "remote.origin.partialclonefilter").trim());
        assertTrue(Files.exists(repo.resolve("pom.xml")));
        assertFalse(Files.exists(repo.resolve("module-a")));
        assertFalse(Files.exists(repo.resolve("module-b")));
        assertTrue(
                git(repo, "rev-list", "--objects", "--all", "--missing=print")
                        .lines()
                        .anyMatch(line -> line.startsWith("?")),
                "Partial clone must leave some blobs unfetched");
        assertEquals("", git(repo, "tag").trim());

        helper(attempt, environment, "expand", "module-a");
        assertEquals("module-a", Files.readString(repo.resolve("module-a/source.txt")));
        assertFalse(Files.exists(repo.resolve("module-b")));
        // A build tool's ordinary Git invocation must be able to lazy-fetch without a helper wrapper command.
        assertEquals("module-b", success(repo, environment, "sh", "-c", "git show HEAD:module-b/source.txt"));
        helper(attempt, environment, "deepen", "2");
        assertEquals("3", git(repo, "rev-list", "--count", "HEAD").trim());
        assertTrue(Files.readString(temporary.resolve("git-calls"))
                .contains("<fetch><--no-tags><--filter=blob:none><--depth=3><origin><" + fixture.sha() + ">"));
        assertNotEquals(0, helperResult(attempt, environment, "deepen", "1").exit());
        assertEquals(
                "3", Files.readString(repo.resolve(".git/github-source-depth")).trim());
        helper(attempt, environment, "tags");
        assertEquals("release", git(repo, "tag").trim());
        // Regression: real .git metadata is available to git-based build version plugins (unlike an export).
        assertTrue(Files.isDirectory(repo.resolve(".git")));
        assertEquals(
                "release",
                git(repo, "describe", "--tags", "--exact-match", "HEAD").trim());
        assertEquals(fixture.sha(), git(repo, "rev-parse", "HEAD").trim());
        final var config = Files.readString(repo.resolve(".git/config"));
        assertFalse(config.contains("file:"));
        assertFalse(config.contains("GITHUB_SOURCE_TOKEN"));
        assertFalse(Files.exists(repo.resolve(".git/hooks/post-checkout")));

        final var calls = Files.readAllLines(temporary.resolve("git-calls"));
        for (final var line : calls) {
            if (line.startsWith("<show>")) {
                continue;
            }
            assertTrue(line.contains("<credential.helper=>"), line);
            assertTrue(line.contains("<core.hooksPath=/dev/null>"), line);
            assertTrue(line.contains("<http.followRedirects=false>"), line);
            assertTrue(line.contains("<protocol.allow=never>"), line);
            assertTrue(line.contains("<protocol.https.allow=always>"), line);
        }
        assertTrue(calls.stream()
                .anyMatch(line -> line.contains("<--filter=blob:none><--depth=1><origin><" + fixture.sha() + ">")));
        assertFalse(calls.stream().anyMatch(line -> line.contains("--unshallow")));
        assertFalse(calls.stream().anyMatch(line -> line.contains("<clone>")));
    }

    @Test
    void rejectsUnsafeSparsePathsUnboundedHistoryAndSoftDiskLimit() throws Exception {
        final var fixture = fixture();
        final var attempt = temporary.resolve("bounded");
        final var source = new WorkerOptions.Source(WorkerOptions.SourceMode.git, "", 3, 1024 * 1024, 10);
        AgentGitSource.stage(attempt, "acme/repo", fixture.sha(), source);
        for (final var path : List.of("../escape", "/absolute", "-option", "a/../b", ".git", "a/.git", "a\nb")) {
            assertNotEquals(
                    0,
                    helperResult(attempt, fixture.environment(), "init", path).exit(),
                    path);
        }
        assertFalse(Files.exists(attempt.resolve("workspace/repository")));
        helper(attempt, fixture.environment(), "init", "module-a");
        assertTrue(Files.exists(attempt.resolve("workspace/repository/module-a/source.txt")));
        for (final var count : List.of("0", "-1", "4", "--unshallow", "999999999999999999999999999999", "1;id")) {
            assertNotEquals(
                    0,
                    helperResult(attempt, fixture.environment(), "deepen", count)
                            .exit());
        }
        final var before = Files.readString(temporary.resolve("git-calls"));
        Files.write(attempt.resolve("workspace/repository/large-untracked"), new byte[2 * 1024 * 1024]);
        final var rejected = helperResult(attempt, fixture.environment(), "expand", "module-b");
        assertNotEquals(0, rejected.exit());
        assertTrue(rejected.output().contains("Source soft disk limit exceeded"));
        assertEquals(before, Files.readString(temporary.resolve("git-calls")), "Precheck must run before Git");

        final var large = new byte[2 * 1024 * 1024];
        new Random(0).nextBytes(large);
        Files.write(fixture.source().resolve("large-root"), large);
        git(fixture.source(), "add", ".");
        git(fixture.source(), "commit", "-qm", "large root file");
        final var postAttempt = temporary.resolve("post-limit");
        AgentGitSource.stage(
                postAttempt,
                "acme/repo",
                git(fixture.source(), "rev-parse", "HEAD").trim(),
                source);
        final var post = helperResult(postAttempt, fixture.environment(), "init");
        assertNotEquals(0, post.exit());
        assertTrue(post.output().contains("Source soft disk limit exceeded"));
    }

    @Test
    void rejectsMismatchedFetchAndTimesOutGit() throws Exception {
        final var fixture = fixture();
        final var attempt = temporary.resolve("mismatch");
        AgentGitSource.stage(attempt, "acme/repo", fixture.sha(), options(""));
        final var wrapper = fixture.bin().resolve("git");
        final var original = Files.readString(wrapper);
        Files.writeString(
                wrapper,
                original.replace(
                        "exec ",
                        "case \" $* \" in *' rev-parse --verify FETCH_HEAD '*) printf '%s\\n' " + SHA
                                + "; exit 0 ;; esac\nexec "));
        final var mismatch = helperResult(attempt, fixture.environment(), "init");
        assertNotEquals(0, mismatch.exit());
        assertTrue(mismatch.output().contains("Fetched commit does not match pinned SHA"));
        assertFalse(Files.exists(attempt.resolve("workspace/repository/pom.xml")));

        Files.writeString(wrapper, "#!/bin/sh\nexec sleep 10\n");
        final var timed = temporary.resolve("timeout");
        AgentGitSource.stage(
                timed,
                "acme/repo",
                fixture.sha(),
                new WorkerOptions.Source(WorkerOptions.SourceMode.git, "", 3, 1024 * 1024, 1));
        assertEquals(124, helperResult(timed, fixture.environment(), "init").exit());
    }

    private WorkerOptions.Source options(final String tokenEnv) {
        return new WorkerOptions.Source(WorkerOptions.SourceMode.git, tokenEnv, 3, 536870912, 10);
    }

    private Fixture fixture() throws Exception {
        final var source = Files.createDirectory(temporary.resolve("source"));
        git(source, "init", "--template=");
        git(source, "config", "user.name", "Fixture");
        git(source, "config", "user.email", "fixture@example.invalid");
        git(source, "config", "uploadpack.allowFilter", "true");
        git(source, "config", "uploadpack.allowAnySHA1InWant", "true");
        Files.writeString(source.resolve("pom.xml"), "<project/>\n");
        for (final var module : List.of("module-a", "module-b")) {
            Files.createDirectory(source.resolve(module));
            Files.writeString(source.resolve(module + "/source.txt"), module);
        }
        for (int i = 0; i < 4; i++) {
            Files.writeString(source.resolve("revision"), Integer.toString(i));
            git(source, "add", ".");
            git(source, "commit", "-qm", "revision " + i);
        }
        final var sha = git(source, "rev-parse", "HEAD").trim();
        git(source, "tag", "release");
        Files.writeString(source.resolve("revision"), "moving branch must not be checked out");
        git(source, "commit", "-qam", "move branch beyond pinned commit");
        final var realGit =
                success(temporary, Map.of(), "sh", "-c", "command -v git").trim();
        final var bin = Files.createDirectory(temporary.resolve("bin"));
        final var wrapper = bin.resolve("git");
        // Test-only transport substitution: exercise real upload-pack filtering/shallow/lazy fetching while
        // retaining the canonical HTTPS origin on disk. No production URL or transport override is exposed.
        Files.writeString(wrapper, """
                #!/bin/sh
                set -eu
                for arg in "$@"; do printf '<%s>' "$arg"; done >> @LOG@
                printf '\\n' >> @LOG@
                test "$GIT_CONFIG_NOSYSTEM" = 1
                test "$GIT_CONFIG_GLOBAL" = /dev/null
                test "$GIT_CONFIG_SYSTEM" = /dev/null
                test "$GIT_TERMINAL_PROMPT" = 0
                exec @GIT@ -c protocol.file.allow=always -c @REWRITE@ "$@"
                """.replace(
                        "@LOG@", quote(temporary.resolve("git-calls").toString()))
                .replace("@GIT@", quote(realGit))
                .replace("@REWRITE@", quote("url." + source.toUri() + ".insteadOf=" + REMOTE)));
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
        return new Fixture(source, sha, bin);
    }

    private void helper(final Path attempt, final Map<String, String> environment, final String... args)
            throws Exception {
        final var result = helperResult(attempt, environment, args);
        assertEquals(0, result.exit(), result.output());
    }

    private Result helperResult(final Path attempt, final Map<String, String> environment, final String... args)
            throws Exception {
        final var command = new ArrayList<>(
                List.of("sh", attempt.resolve("workspace/github-source-git.sh").toString()));
        command.addAll(List.of(args));
        return run(temporary, environment, command.toArray(String[]::new));
    }

    private String git(final Path directory, final String... args) throws Exception {
        final var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        return success(directory, Map.of(), command.toArray(String[]::new));
    }

    private String success(final Path directory, final Map<String, String> environment, final String... command)
            throws Exception {
        final var result = run(directory, environment, command);
        assertEquals(0, result.exit(), result.output());
        return result.output();
    }

    private Result run(final Path directory, final Map<String, String> environment, final String... command)
            throws Exception {
        final var output = Files.createTempFile(temporary, "process-", ".log");
        final var builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().clear();
        builder.environment()
                .putAll(Map.of(
                        "PATH",
                        "/usr/bin:/bin",
                        "HOME",
                        temporary.toString(),
                        "LC_ALL",
                        "C",
                        "GIT_CONFIG_NOSYSTEM",
                        "1",
                        "GIT_CONFIG_GLOBAL",
                        "/dev/null",
                        "GIT_CONFIG_SYSTEM",
                        "/dev/null",
                        "GIT_TERMINAL_PROMPT",
                        "0"));
        builder.environment().putAll(environment);
        final var process = startFixture(builder);
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Fixture command timed out");
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static Process startFixture(final ProcessBuilder builder) throws IOException, InterruptedException {
        // Concurrent native process creation can briefly inherit a just-written script descriptor.
        // Retry only the kernel's ETXTBSY admission failure, never a command result or assertion.
        for (int attempt = 0; ; attempt++) {
            try {
                return builder.start();
            } catch (final IOException failure) {
                if (attempt >= 20 || !failure.getMessage().contains("error: 26")) {
                    throw failure;
                }
                Thread.sleep(10);
            }
        }
    }

    private static String quote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private record Fixture(Path source, String sha, Path bin) {
        private Map<String, String> environment() {
            return Map.of("PATH", bin + ":/usr/bin:/bin");
        }
    }

    private record Result(int exit, String output) {}
}
