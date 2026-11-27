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
import java.util.Map;

@JsonModel
public record HttpClientConfiguration(
        @Property(
                defaultValue = "java.util.Map.of()",
                documentation =
                        "Extra headers, KEY=VALUE, applied to every request sent through the shared HTTP client (webfetch, websearch and MCP over HTTP). A user-agent header set here overrides the default Chrome user-agent.")
        Map<String, String> headers,

        @Property(
                defaultValue = "false",
                documentation =
                        "Disable TLS certificate validation and hostname verification. Only intended for internal/testing endpoints, use with care.")
        boolean disableTlsCheck,

        @Property(
                defaultValue = "false",
                documentation =
                        "Are cookies enabled client side. Some gateway will need it for routing purpose (and cache reuse).")
        boolean enableCookies,

        @Property(defaultValue = "\"HTTP/1.1\"", documentation = "HTTP version to use: HTTP/1.1 or HTTP_2.")
        String version,

        @Property(defaultValue = "30", documentation = "Connect timeout in seconds.")
        int connectTimeoutSeconds,

        @Property(
                defaultValue = "false",
                documentation =
                        "Log every HTTP exchange (method, URL, headers and payload) as a curl-style trace. Only for debugging; the authorization header is redacted.")
        boolean debug,

        @Property(
                documentation =
                        "HTTP proxy settings of the shared client. When host is blank the JDK default proxy selection is kept (http.proxyHost system property, HTTPS_PROXY environment variable, ...). Applies to every request sent through the shared HTTP client: LLM calls, webfetch, websearch, MCP over HTTP and maven repository knowledge lookups.")
        HttpClientProxyConfiguration proxy,

        @Property(
                documentation =
                        "TLS trust settings of the shared client: optional import of the JVM global trust store plus explicit PEM certificates. Ignored when disableTlsCheck is enabled.")
        HttpClientCertificateConfiguration certificates) {}
