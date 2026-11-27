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

/**
 * Secret redaction: detects local secrets (env files, known secret files, {@code $prefix$body}
 * credential shapes, Maven encrypted values) and replaces them with format-preserving fakes before
 * they go over the LLM wire, restoring only what it emitted on the way back.
 */
@JsonModel
public record SecretRedactionConfiguration(
        @Property(
                defaultValue = "io.yupiik.ia.agent.core.configuration.SecretRedactionMode.REDACT",
                documentation =
                        "Wire behavior: redact (replace secrets with format-preserving fakes outbound, restore only "
                                + "what was emitted inbound), passthrough (send raw values - per-session / debugging "
                                + "escape hatch, also via /secrets), fail (abort the outbound LLM call if any secret "
                                + "would go over the wire). Inbound restore always runs.")
        SecretRedactionMode mode,

        @Property(
                defaultValue = "java.util.List.of(\".env\", \".env.local\", \".env.production\")",
                documentation =
                        "Env/config files (relative to the project or working directory) scanned once at startup for "
                                + "KEY=VALUE entries whose VALUE (and, for secret-looking key names, the value regardless "
                                + "of shape) becomes a harvested secret. Exact harvested values are then matched in every "
                                + "message without re-parsing files.")
        List<String> envFiles,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Explicit secret values to always redact, in addition to harvested and pattern-detected ones.")
        List<String> knownValues,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Extra secret prefixes to redact (e.g. \"mycorp_\", \"int_tok_\"). Any value starting with a "
                                + "listed prefix and at least minValueLength characters is replaced with a same-family "
                                + "fake. Fed to a ConfiguredPrefixSecretMatcher - an easy way to add credential families "
                                + "without writing Java.")
        List<String> prefixes,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Extra arbitrary regex patterns: each entry is scanned in every message and any match is redacted "
                                + "(the matched span is treated as the secret value). Entry format is the regex itself.")
        List<String> patterns,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Exclusion allowlist (regex): content matching any of these is never redacted. Use for fixtures, "
                                + "example keys (sk-test-.*, example.com), generated samples.")
        List<String> exclude,

        @Property(
                defaultValue = "8",
                documentation =
                        "Minimum value length (characters) for a pattern/harvested match to be considered a secret. "
                                + "Short values are ignored to keep detection relevant.")
        int minValueLength,

        @Property(
                defaultValue = "false",
                documentation =
                        "Enable high-entropy value detection (long random-looking strings near a secret-ish variable/key "
                                + "name). Off by default: it is the least precise signal and can false-positive on prose; "
                                + "the name/prefix/known-file detections are precision-first and cover the common cases.")
        boolean entropy,

        @Property(
                defaultValue = "true",
                documentation =
                        "Detect standard credential shapes in arbitrary text (prefix+body tokens for GitHub, GitLab, "
                                + "Gitea, AWS, OpenAI, Anthropic, Google, Stripe, Slack, ...), PEM private keys, connection "
                                + "URIs, JWTs and generic secret-key assignments, even when the value was not pre-harvested "
                                + "from a local file.")
        boolean detectShapes,

        @Property(documentation = "Maven settings decryption / secret harvesting (settings.xml and settings-security).")
        MavenSecretConfiguration maven,

        @Property(documentation = "Process-environment secret harvesting rules (System.getenv() name classification).")
        SecretEnvironmentConfiguration environment,

        @Property(
                documentation =
                        "Known secret HTTP header names, shared across all header-based secret harvesting (Maven settings, YCA config).")
        SecretHttpConfiguration http,

        @Property(
                documentation = "YCA configuration file secret harvesting (global + project-local configuration.json).")
        YcaConfigSecretConfiguration ycaConfig,

        @Property(documentation = "Cloud provider credential-file secret harvesting (AWS/GCP/Azure).")
        CloudCredentialsSecretConfiguration cloudCredentials) {}
