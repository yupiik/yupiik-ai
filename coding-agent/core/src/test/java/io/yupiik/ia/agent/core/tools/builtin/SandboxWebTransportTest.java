/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.tools.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import io.yupiik.ia.agent.core.mcp.WebSearchManager;
import io.yupiik.ia.agent.core.permissions.PermissionService;
import io.yupiik.ia.agent.core.sandbox.Sandbox;
import io.yupiik.ia.agent.core.tools.ToolContext;
import io.yupiik.ia.agent.core.tools.ToolResult;
import io.yupiik.ia.agent.test.TestJsonMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(TestJsonMapper.class)
class SandboxWebTransportTest {
    private CodingAgentConfiguration configuration;

    @BeforeEach
    void configuration(@Fusion final CodingAgentConfiguration configuration) {
        this.configuration = configuration;
        root = Path.of(configuration.storageHome());
    }

    private Path root;

    @Nested
    @FusionSupport(modules = WebFetchPolicyDenialDoesNotUseAmbientClientScenarioModule.class)
    class WebFetchPolicyDenialDoesNotUseAmbientClientScenario {
        @TempDir
        static Path directory;

        @Test
        void webFetchPolicyDenialDoesNotUseAmbientClient() {
            final var sandbox = new Policy(root, false, new ArrayList<>());
            final var result = new WebFetchTool(null, null)
                    .execute(Map.of("url", "http://127.0.0.1/"), ToolContext.empty(root, sandbox));
            assertFalse(result.success());
            assertTrue(result.output().contains("Sandbox hard deny"), result.output());
            assertEquals(List.of(URI.create("http://127.0.0.1/")), sandbox.checks());
        }
    }

    @Nested
    @FusionSupport(modules = WebFetchGrantCannotOverridePublicAddressRestrictionScenarioModule.class)
    class WebFetchGrantCannotOverridePublicAddressRestrictionScenario {
        @TempDir
        static Path directory;

        @Test
        void webFetchGrantCannotOverridePublicAddressRestriction() {
            final var sandbox = new Policy(root, true, new ArrayList<>());
            final var result = new WebFetchTool(null, null)
                    .execute(Map.of("url", "http://169.254.169.254/"), ToolContext.empty(root, sandbox));
            assertFalse(result.success());
            assertTrue(result.output().contains("non-public"), result.output());
            assertEquals(List.of(URI.create("http://169.254.169.254/")), sandbox.checks());
        }
    }

    @Nested
    @FusionSupport(modules = WebSearchChecksProviderPolicyAndNeverUsesAmbientManagerScenarioModule.class)
    class WebSearchChecksProviderPolicyAndNeverUsesAmbientManagerScenario {
        @TempDir
        static Path directory;

        @Test
        void webSearchChecksProviderPolicyAndNeverUsesAmbientManager(final JsonMapper mapper) {
            final var sandbox = new Policy(root, false, new ArrayList<>());
            final var result = search(mapper, sandbox);
            assertFalse(result.success());
            assertTrue(result.output().contains("Sandbox hard deny"), result.output());
            assertEquals(List.of(URI.create("http://127.0.0.1/mcp")), sandbox.checks());
        }
    }

    @Nested
    @FusionSupport(modules = WebSearchGrantCannotOverridePublicAddressRestrictionScenarioModule.class)
    class WebSearchGrantCannotOverridePublicAddressRestrictionScenario {
        @TempDir
        static Path directory;

        @Test
        void webSearchGrantCannotOverridePublicAddressRestriction(final JsonMapper mapper) {
            final var sandbox = new Policy(root, true, new ArrayList<>());
            final var result = search(mapper, sandbox);
            assertFalse(result.success());
            assertTrue(result.output().contains("non-public"), result.output());
            assertEquals(List.of(URI.create("http://127.0.0.1/mcp")), sandbox.checks());
        }
    }

    private ToolResult search(final JsonMapper mapper, final Policy sandbox) {
        final var ambient = new WebSearchManager() {
            @Override
            public boolean hasEnabledProviders() {
                return true;
            }

            @Override
            public List<WebSearchResult> search(final String query, final int count) {
                throw new AssertionError("Sandboxed tools must never use an ambient provider client");
            }
        };
        return new WebSearchTool(ambient, configuration, mapper)
                .execute(Map.of("query", "query"), ToolContext.empty(root, sandbox));
    }

    private record Policy(Path tempDir, boolean granted, List<URI> checks) implements Sandbox {
        @Override
        public Sandbox approvalView(final PermissionService permissions) {
            return this;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public void requireRead(final Path path) {
            throw new AssertionError();
        }

        @Override
        public void requireWrite(final Path path) {
            throw new AssertionError();
        }

        @Override
        public void requireNetwork(final URI uri) {
            checks.add(uri);
            if (!granted) {
                throw new SecurityException("Sandbox hard deny");
            }
        }

        @Override
        public void request(final String kind, final String target, final boolean write) {
            throw new AssertionError("No automatic approvals and no hard-policy override");
        }

        @Override
        public Command launch(final ProcessBuilder builder) {
            throw new AssertionError();
        }

        @Override
        public void revoke() {
            /* no resources */
        }

        @Override
        public void close() {
            /* no resources */
        }
    }

    public static class WebFetchPolicyDenialDoesNotUseAmbientClientScenarioModule extends ConfigurationTestModule {
        public WebFetchPolicyDenialDoesNotUseAmbientClientScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    WebFetchPolicyDenialDoesNotUseAmbientClientScenario.directory.toString(),
                    "yca.tools.websearch.providers.length",
                    "1",
                    "yca.tools.websearch.providers.0.provider",
                    "PARALLEL",
                    "yca.tools.websearch.providers.0.url",
                    "http://127.0.0.1/mcp"));
        }
    }

    public static class WebFetchGrantCannotOverridePublicAddressRestrictionScenarioModule
            extends ConfigurationTestModule {
        public WebFetchGrantCannotOverridePublicAddressRestrictionScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    WebFetchGrantCannotOverridePublicAddressRestrictionScenario.directory.toString(),
                    "yca.tools.websearch.providers.length",
                    "1",
                    "yca.tools.websearch.providers.0.provider",
                    "PARALLEL",
                    "yca.tools.websearch.providers.0.url",
                    "http://127.0.0.1/mcp"));
        }
    }

    public static class WebSearchChecksProviderPolicyAndNeverUsesAmbientManagerScenarioModule
            extends ConfigurationTestModule {
        public WebSearchChecksProviderPolicyAndNeverUsesAmbientManagerScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    WebSearchChecksProviderPolicyAndNeverUsesAmbientManagerScenario.directory.toString(),
                    "yca.tools.websearch.providers.length",
                    "1",
                    "yca.tools.websearch.providers.0.provider",
                    "PARALLEL",
                    "yca.tools.websearch.providers.0.url",
                    "http://127.0.0.1/mcp"));
        }
    }

    public static class WebSearchGrantCannotOverridePublicAddressRestrictionScenarioModule
            extends ConfigurationTestModule {
        public WebSearchGrantCannotOverridePublicAddressRestrictionScenarioModule() {
            super(Map.of(
                    "yca.storageHome",
                    WebSearchGrantCannotOverridePublicAddressRestrictionScenario.directory.toString(),
                    "yca.tools.websearch.providers.length",
                    "1",
                    "yca.tools.websearch.providers.0.provider",
                    "PARALLEL",
                    "yca.tools.websearch.providers.0.url",
                    "http://127.0.0.1/mcp"));
        }
    }
}
