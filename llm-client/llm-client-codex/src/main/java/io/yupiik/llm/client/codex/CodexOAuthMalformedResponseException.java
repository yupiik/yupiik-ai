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
package io.yupiik.llm.client.codex;

/**
 * Thrown when a Codex OAuth endpoint returns a payload that does not parse as the expected JSON
 * object (eg. an HTML error page, an empty body or truncated JSON). Carries a bounded excerpt of
 * the offending body so the failure is diagnosable instead of degrading to an empty map and a
 * confusing later error (eg. "access_token is missing").
 */
public class CodexOAuthMalformedResponseException extends IllegalStateException {

    private final String bodyExcerpt;

    public CodexOAuthMalformedResponseException(final String body, final Throwable cause) {
        super("Codex OAuth response is not a JSON object" + describe(body), cause);
        this.bodyExcerpt = excerptOf(body);
    }

    /** A bounded single-line excerpt of the offending body (at most 400 chars). */
    public String bodyExcerpt() {
        return bodyExcerpt;
    }

    private static String describe(final String body) {
        final var excerpt = excerptOf(body);
        return excerpt.isEmpty() ? "" : ": " + excerpt;
    }

    private static String excerptOf(final String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        final var singleLine = body.replace("\r", " ").replace("\n", " ").strip();
        return singleLine.length() <= 400 ? singleLine : singleLine.substring(0, 400) + "...";
    }
}
