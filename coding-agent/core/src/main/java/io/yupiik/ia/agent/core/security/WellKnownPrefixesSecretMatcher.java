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
package io.yupiik.ia.agent.core.security;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The built-in {@code $prefix$body} credential matrix (Git, cloud/AI providers and misc token
 * families). One {@link SecretMatcher} hosting a delegate list of {@link PrefixSecretMatcher}s, so
 * it contributes a single bean to the injected {@code List<SecretMatcher>} while covering the whole
 * matrix. Matches the most-specific (longest) prefix first on overlap.
 */
@ApplicationScoped
public class WellKnownPrefixesSecretMatcher implements SecretMatcher {

    /** Alphabet shared by most token bodies. */
    private static final String B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final List<PrefixEntry> entries;

    public WellKnownPrefixesSecretMatcher() {
        this.entries = build().stream()
                .sorted(Comparator.comparingInt(PrefixSecretMatcher::order).reversed())
                .map(PrefixEntry::new)
                .toList();
    }

    @Override
    public boolean matches(final String value) {
        for (final var e : entries) {
            if (e.matcher.matches(value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String replace(final String value) {
        for (final var e : entries) {
            if (e.matcher.matches(value)) {
                return e.matcher.replace(value);
            }
        }
        return value;
    }

    @Override
    public int order() {
        return 10_000; // built-in prefix matrix beats generic single-prefix matchers
    }

    @Override
    public String label() {
        return "well-known-prefixes";
    }

    private static List<PrefixSecretMatcher> build() {
        final var out = new ArrayList<PrefixSecretMatcher>();
        // --- GitHub (longer prefixes first) ---
        out.add(p("github_pat_", "[A-Za-z0-9_]{30,120}", B64URL));
        out.add(p("ghp_", "[A-Za-z0-9]{20,50}"));
        out.add(p("gho_", "[A-Za-z0-9]{20,50}"));
        out.add(p("ghu_", "[A-Za-z0-9]{20,50}"));
        out.add(p("ghs_", "[A-Za-z0-9]{20,50}"));
        out.add(p("ghr_", "[A-Za-z0-9]{20,50}"));
        // --- GitLab / Gitea ---
        out.add(p("glpat-", "[A-Za-z0-9_-]{15,40}", B64URL));
        out.add(p("gitea_", "[A-Za-z0-9_-]{15,60}", B64URL));
        // --- OpenAI / OpenRouter / freeinference ---
        out.add(p("sk-proj-", "[A-Za-z0-9_-]{20,120}", B64URL));
        out.add(p("sk-ant-api03-", "[A-Za-z0-9_-]{20,100}", B64URL));
        out.add(p("sk-or-v1-", "[A-Za-z0-9_-]{20,100}", B64URL));
        out.add(p("sk-", "[A-Za-z0-9]{24,70}"));
        // --- Anthropic ---
        out.add(p("sk-ant-", "[A-Za-z0-9_-]{20,100}", B64URL));
        // --- AWS access key (AKIA/ASIA...) ---
        out.add(p("AKIA", "[A-Z0-9]{16}", ALNUM));
        out.add(p("ASIA", "[A-Z0-9]{16}", ALNUM));
        out.add(p("AIDA", "[A-Z0-9]{16}", ALNUM));
        out.add(p("AIPA", "[A-Z0-9]{16}", ALNUM));
        out.add(p("ANPA", "[A-Z0-9]{16}", ALNUM));
        out.add(p("ANVA", "[A-Z0-9]{16}", ALNUM));
        out.add(p("AROA", "[A-Z0-9]{16}", ALNUM));
        // --- Google ---
        out.add(p("AIza", "[A-Za-z0-9_-]{25,60}", B64URL));
        out.add(p("GOCSPX-", "[A-Za-z0-9_-]{20,60}", B64URL));
        out.add(p("AAAA", "[A-Za-z0-9_-]{30,120}:APA91b[A-Za-z0-9_-]{30,}", B64URL)); // Firebase legacy
        // --- DigitalOcean / Pulumi / Sentry / New Relic / Linear / Resend / Doppler ---
        out.add(p("dop_v1_", "[A-Za-z0-9_-]{20,80}", B64URL));
        out.add(p("doo_v1_", "[A-Za-z0-9_-]{20,80}", B64URL));
        out.add(p("dor_v1_", "[A-Za-z0-9_-]{20,80}", B64URL));
        out.add(p("pul-", "[A-Za-z0-9_-]{20,80}", B64URL));
        out.add(p("sntrys_", "[A-Za-z0-9_-]{20,80}", B64URL));
        out.add(p("sntryu_", "[A-Za-z0-9_-]{20,80}", B64URL));
        out.add(p("NRAK-", "[A-Za-z0-9]{20,60}"));
        out.add(p("NRII-", "[A-Za-z0-9]{20,60}"));
        out.add(p("lin_api_", "[A-Za-z0-9_-]{20,80}", B64URL));
        out.add(p("re_", "[A-Za-z0-9_-]{14,80}", B64URL));
        out.add(p("dp.st.", "[A-Za-z0-9._-]{20,80}"));
        // --- Shopify / Figma / Notion / Mailgun / Brevo / Square / Alibaba ---
        out.add(p("shpat_", "[A-Za-z0-9]{20,60}"));
        out.add(p("shpca_", "[A-Za-z0-9]{20,60}"));
        out.add(p("shpss_", "[A-Za-z0-9]{20,60}"));
        out.add(p("shpcu_", "[A-Za-z0-9]{20,60}"));
        out.add(p("figd_", "[A-Za-z0-9_-]{20,60}", B64URL));
        out.add(p("ntn_", "[A-Za-z0-9_-]{30,80}", B64URL));
        out.add(p("secret_", "[A-Za-z0-9_-]{30,80}", B64URL));
        out.add(p("key-", "[A-Za-z0-9]{30,80}"));
        out.add(p("xkeysib-", "[A-Za-z0-9_-]{50,120}", B64URL));
        out.add(p("sq0atp-", "[A-Za-z0-9_-]{20,60}", B64URL));
        out.add(p("sq0csp-", "[A-Za-z0-9_-]{20,60}", B64URL));
        out.add(p("LTAI", "[A-Za-z0-9]{16,20}", ALNUM));
        // --- Stripe / Slack / misc ---
        out.add(p("sk_live_", "[A-Za-z0-9]{20,60}"));
        out.add(p("sk_test_", "[A-Za-z0-9]{20,60}"));
        out.add(p("pk_live_", "[A-Za-z0-9]{20,60}"));
        out.add(p("pk_test_", "[A-Za-z0-9]{20,60}"));
        out.add(p("xoxb-", "[A-Za-z0-9-]{20,80}"));
        out.add(p("xoxp-", "[A-Za-z0-9-]{20,80}"));
        out.add(p("xoxa-", "[A-Za-z0-9-]{20,80}"));
        out.add(p("xoxr-", "[A-Za-z0-9-]{20,80}"));
        out.add(p("hf_", "[A-Za-z0-9]{20,60}"));
        out.add(p("vercel_", "[A-Za-z0-9]{20,60}"));
        out.add(p("ddapi_", "[A-Za-z0-9]{20,80}"));
        out.add(p("sbp_", "[A-Za-z0-9]{20,80}"));
        out.add(p("glc_", "[A-Za-z0-9_-]{20,80}", B64URL));
        out.add(p("SG.", "[A-Za-z0-9._-]{20,80}"));
        out.add(p("vercel_", "[A-Za-z0-9_-]{20,60}", B64URL));
        // --- Cloudflare / Databricks / Terraform / digitalocean style ---
        out.add(p("dapi", "[A-Za-z0-9_-]{30,80}", B64URL));
        out.add(p("ATAT", "[A-Za-z0-9_-]{20,60}", B64URL));
        out.add(p("ATAX", "[A-Za-z0-9_-]{20,60}", B64URL));
        out.add(p("zi_", "[A-Za-z0-9_-]{20,60}", B64URL));
        out.add(p("cf_", "[A-Za-z0-9_-]{20,60}", B64URL));
        return List.copyOf(out);
    }

    private static PrefixSecretMatcher p(final String prefix, final String bodyRegex) {
        return p(prefix, bodyRegex, ALNUM);
    }

    private static PrefixSecretMatcher p(final String prefix, final String bodyRegex, final String alphabet) {
        return new PrefixSecretMatcher(prefix, bodyRegex, alphabet, 8) {
            @Override
            public int order() {
                return prefix.length();
            }
        };
    }

    private record PrefixEntry(PrefixSecretMatcher matcher) {}
}
