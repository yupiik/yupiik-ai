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
 * Process-environment secret harvesting: the rules deciding which environment-variable NAME (from
 * {@link System#getenv()}) marks its value as a secret. All keyword/qualifier constants that used to
 * live in {@code SecretNames} are now configurable, keeping the same defaults, and a {@code
 * knownSafeValues} allowlist lets well-known harmless env names (the shell's {@code PWD}/{@code
 * OLDPWD} working-directory vars) never be harvested even though they would match a keyword.
 */
@JsonModel
public record SecretEnvironmentConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Also harvest the process environment (System.getenv()) at startup, treating as secrets the values "
                                + "whose variable NAME is classified as secret-ish by the keyword/qualifier rules below "
                                + "(and not on the knownSafeValues allowlist). Values are matched as exact harvested values "
                                + "from then on.")
        boolean harvestEnvironment,

        @Property(
                defaultValue =
                        "java.util.List.of(\"PASSWORD\", \"PASSWD\", \"PWD\", \"SECRET\", \"TOKEN\", \"APIKEY\", "
                                + "\"CREDENTIAL\", \"PRIVATEKEY\", \"AUTHORIZATION\")",
                documentation =
                        "Secret-holding keywords for environment variable names: an upper-cased, separator-stripped name "
                                + "containing any of these is treated as secret-ish (e.g. DB_PASSWORD contains PASSWORD; "
                                + "HTTP_AUTHORIZATION contains AUTHORIZATION). Each keyword is also combined with every "
                                + "configured qualifier to catch composite markers such as CLIENT_SECRET, ACCESS_TOKEN, "
                                + "DB_PASSWORD and AUTH_TOKEN.")
        List<String> keywords,

        @Property(
                defaultValue =
                        "java.util.List.of(\"API\", \"CLIENT\", \"ACCESS\", \"REFRESH\", \"PRIVATE\", \"AUTH\", \"DB\")",
                documentation =
                        "Semantic qualifiers that turn a generic keyword into a credential marker (API_KEY, DB_PASSWORD, "
                                + "ACCESS_TOKEN, CLIENT_SECRET, ...).")
        List<String> qualifiers,

        @Property(
                defaultValue = "java.util.List.of(\"PWD\", \"OLDPWD\", \"SDKMAN_OLD_PWD\")",
                documentation =
                        "Environment variable NAMES that are never harvested as secrets, whatever the keyword/qualifier "
                                + "rules say. Matched exactly (case-sensitive). Defaults to the shell working-directory "
                                + "variables (PWD and OLDPWD), whose value is the current/previous folder path, not a "
                                + "credential - harvesting it used to redact the whole working-directory path out of every "
                                + "message. Only applies to the process-environment harvest (`System.getenv()`), not to "
                                + "env/config files.")
        List<String> knownSafeValues) {}
