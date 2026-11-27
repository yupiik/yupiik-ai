/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxPathsTest {
    @TempDir
    Path root;

    @Nested
    @FusionSupport(modules = ConfigurationTestModule.class)
    class Defaults {
        @Test
        void additionalCredentialDenialsAreExplicitInBothBackendSnapshots(
                @Fusion final CodingAgentConfiguration configuration) throws Exception {
            final var project = Files.createDirectories(root.resolve("project[one]"));
            final var filesystem = configuration.sandbox().filesystem();
            // Project the home rule templates into the fixture, never mutate the real home.
            final var policy = new SandboxPaths(
                    List.of("${projectdir}"),
                    List.of("${projectdir}"),
                    filesystem.denyRead().stream()
                            .map(rule -> rule.replace("~/", "${projectdir}/"))
                            .toList(),
                    filesystem.denyWrite().stream()
                            .map(rule -> rule.replace("~/", "${projectdir}/"))
                            .toList(),
                    project,
                    project,
                    root);
            final var targets = List.of(
                    project.resolve(".m2/settings.xml"),
                    project.resolve(".m2/settings-security.xml"),
                    project.resolve(".yupiik/coding-agent/configuration.json"),
                    project.resolve(".bashrc"));
            for (final var target : targets) {
                // Literal and glob denials also apply before a target exists in Java checks.
                assertTrue(policy.denied(target, false), target.toString());
                assertTrue(policy.denied(target, true), target.toString());
                assertFalse(policy.allowed(target, false), target.toString());
                assertFalse(policy.allowed(target, true), target.toString());
                Files.createDirectories(target.getParent());
                Files.writeString(target, "secret");
            }
            final var snapshot = policy.mountSnapshot();
            for (final var target : targets) {
                for (final var key : List.of("denyRead", "denyWrite")) {
                    assertTrue(snapshot.get(key).stream().anyMatch(target::startsWith), key + " " + target);
                }
            }
            // The settings glob is not an implicit denial of the entire Maven cache.
            assertTrue(policy.allowed(project.resolve(".m2/repository/artifact.jar"), false));
            assertTrue(policy.allowed(project.resolve(".m2/repository/artifact.jar"), true));
            assertTrue(policy.allowed(project.resolve(".m2/settings.txt"), false));
            assertTrue(policy.allowed(project.resolve(".m2/settings.txt"), true));
        }
    }

    @Test
    void explicitGlobsLiteralsAndStableAnchors() throws Exception {
        final var project = Files.createDirectories(root.resolve("project[one]"));
        final var scratch = Files.createDirectories(root.resolve("scratch"));
        final var policy = new SandboxPaths(
                List.of("${projectdir}", "${tempdir}"),
                List.of("${projectdir}"),
                List.of("glob:${projectdir}/.env*", "glob:${projectdir}/**/.env*", "${projectdir}/secret"),
                List.of("${projectdir}/control"),
                root,
                project,
                scratch);
        assertTrue(policy.allowed(project.resolve("src/code.java"), true));
        assertTrue(policy.allowed(scratch.resolve("result"), false));
        assertFalse(policy.allowed(scratch.resolve("result"), true));
        assertTrue(policy.denied(project.resolve(".env.local"), false));
        assertTrue(policy.denied(project.resolve("nested/.env.local/child"), true));
        assertTrue(policy.denied(project.resolve("secret/child"), true));
        assertFalse(policy.denied(project.resolve("control"), false));
        assertTrue(policy.denied(project.resolve("control"), true));
        assertEquals(List.of(project), policy.mountSnapshot().get("write"));
        final var secret = Files.writeString(project.resolve(".env.local"), "secret");
        assertTrue(policy.mountSnapshot().get("denyRead").contains(secret));
    }

    @Test
    void globMountsGrantOnlyCurrentMatchesAndRefreshWithoutFollowingSymlinks() throws Exception {
        final var project = Files.createDirectory(root.resolve("project"));
        final var outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("hidden.txt"), "outside");
        Files.createSymbolicLink(project.resolve("link"), outside);
        final var first = Files.writeString(project.resolve("first.txt"), "first");
        final var other = Files.writeString(project.resolve("other.bin"), "other");
        final var policy = new SandboxPaths(
                List.of("glob:${projectdir}/**.txt"), List.of(), List.of(), List.of(), project, project, root);
        final var initial = policy.mountSnapshot();
        assertEquals(List.of(first), initial.get("read"));
        final var second = Files.writeString(project.resolve("second.txt"), "second");
        assertEquals(List.of(first), initial.get("read"));
        final var refreshed = policy.mountSnapshot().get("read");
        assertEquals(2, refreshed.size());
        assertTrue(refreshed.containsAll(List.of(first, second)));
        assertFalse(refreshed.contains(project));
        assertFalse(refreshed.contains(other));
        assertFalse(refreshed.contains(outside.resolve("hidden.txt")));
    }

    @Test
    void canonicalResolutionRejectsSymlinkDotDotAmbiguity() throws Exception {
        final var project = Files.createDirectory(root.resolve("project"));
        final var outside = Files.createDirectories(root.resolve("outside/child"));
        Files.createSymbolicLink(project.resolve("link"), outside);
        assertThrows(SecurityException.class, () -> SandboxPaths.canonical(project.resolve("link/../secret")));
    }

    @Test
    void rejectsUnknownAnchorsAndMalformedGlob() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxPaths(List.of("${unknown}"), List.of(), List.of(), List.of(), root, root, root));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SandboxPaths(List.of("glob:["), List.of(), List.of(), List.of(), root, root, root));
    }

    @Test
    void followsExistingSymlinkAndNonexistentTail() throws Exception {
        final var project = Files.createDirectory(root.resolve("project"));
        final var outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(project.resolve("link"), outside);
        final var policy = new SandboxPaths(
                List.of("${projectdir}"), List.of("${projectdir}"), List.of(), List.of(), root, project, root);
        assertFalse(policy.allowed(project.resolve("link/new-file"), true));
        assertEquals(outside.resolve("new-file"), SandboxPaths.canonical(project.resolve("link/new-file")));
    }

    @Test
    void domainBoundariesPortsAndPrivateDestinations() throws Exception {
        final var policy = new SandboxDestinations(List.of("repo.maven.apache.org:443", "*.npmjs.org:443"));
        assertTrue(policy.allows(URI.create("https://repo.maven.apache.org/path")));
        assertFalse(policy.allows(URI.create("https://repo.maven.apache.org.evil.example")));
        assertFalse(policy.allows(URI.create("http://repo.maven.apache.org")));
        assertTrue(policy.allows(URI.create("https://registry.npmjs.org")));
        assertFalse(policy.allows(URI.create("https://npmjs.org")));
        assertThrows(
                SecurityException.class,
                () -> new SandboxDestinations(List.of("localhost:443")).resolve(URI.create("https://localhost")));
        for (final var address : List.of(
                "127.0.0.1",
                "10.0.0.1",
                "169.254.169.254",
                "100.64.0.1",
                "192.168.1.1",
                "::1",
                "fc00::1",
                "2002:7f00:1::1")) {
            assertFalse(SandboxDestinations.isPublic(InetAddress.getByName(address)), address);
        }
        assertTrue(SandboxDestinations.isPublic(InetAddress.getByName("1.1.1.1")));
        assertThrows(IllegalArgumentException.class, () -> new SandboxDestinations(List.of("*:443")));
    }
}
