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
 * HTTP proxy settings of the shared HTTP client. When {@code host} is blank (the
 * default) no explicit proxy is configured and the JDK uses its default selection
 * ({{@code http.proxyHost}, {@code https.proxyHost} system properties, {@code HTTPS_PROXY}
 * environment variable, ...).
 */
@JsonModel
public record HttpClientProxyConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Proxy host. Blank (the default) keeps the JDK default proxy selection (http.proxyHost system properties, HTTPS_PROXY environment, ...).")
        String host,

        @Property(
                defaultValue = "8080",
                documentation =
                        "Proxy port. Only used when the proxy host is set; for HTTPS_PROXY style URLs the port default is 8080 when the URL carries none.")
        int port,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional proxy user name for Basic proxy authentication (Proxy-Authorization). Blank means no credentials; when set together with a non-blank proxy host, the JDK challenges the proxy on 407 and retries with Basic credentials. Note the JDK disables Basic authentication for HTTPS CONNECT tunneling by default (jdk.http.auth.tunneling.disabledSchemes=Basic in conf/net.properties): start the JVM with -Djdk.http.auth.tunneling.disabledSchemes= to allow it.")
        String user,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional proxy password for Basic proxy authentication, paired with the proxy user. Blank is ignored.")
        String password,

        @Property(
                defaultValue = "java.util.List.of()",
                documentation =
                        "Hosts that bypass the proxy and are connected to directly, when a proxy host is set. Each entry is matched case-insensitively against the target host name (no port); forms: an exact host (api.internal.corp), a leading-dot domain suffix (.internal.corp matches internal.corp and any sub-domain), or a * wildcard pattern (*.internal.corp; a lone * bypasses every host). Blank or empty keeps today's behavior: every request goes through the proxy.")
        List<String> noProxyHosts) {}
