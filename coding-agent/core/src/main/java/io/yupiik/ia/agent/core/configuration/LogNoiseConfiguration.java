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
 * Framework/test-runner runtime log noise filter tuning. The defaults are the {@code defaultValue}
 * java expressions below (the only authoritative source, surfaced in the generated configuration and
 * applied at startup), so an absent {@code yca.optimize.logNoise} block still drops the standard
 * INFO/DEBUG framework log lines while never dropping WARNING/SEVERE/ERROR.
 *
 * <p>Matching is: a line is <em>kept</em> when it matches an include (even if an exclude also
 * matches); otherwise dropped when it matches an exclude. Default dropped levels are
 * {@code INFO/FINE/FINER/FINEST/CONFIG/DEBUG}; {@code WARNING/SEVERE/ERROR} are never dropped.</p>
 */
@JsonModel
public record LogNoiseConfiguration(
        @Property(
                documentation = "Never drop lines carrying a warning/error (kept even when an exclude matches).",
                defaultValue = "java.util.List.of(new LogNoisePatternConfiguration(\n"
                        + "                    \"\\\\b(WARNING|WARN|SEVERE|ERROR|FATAL)\\\\b\",\n"
                        + "                    \"Never drop warnings/errors from the transcript.\"))")
        List<LogNoisePatternConfiguration> includes,

        @Property(
                documentation = "Framework/test-runner log lines to drop (INFO/DEBUG/FINE level lines, "
                        + "Spring Boot banner, embedded-server startup one-liners, Gradle daemon banner).",
                defaultValue = "java.util.List.of(\n"
                        + "                    new LogNoisePatternConfiguration(\n"
                        + "                            \"^\\\\d{4}-\\\\d{2}-\\\\d{2}T\\\\d{2}:\\\\d{2}:\\\\d{2}.*(?:"
                        + "(\\\\[|\\\\s)(INFO|FINE|FINER|FINEST|CONFIG|DEBUG)(\\\\]|\\\\s|:))\",\n"
                        + "                            \"yupiik-logging-jul / timestamped INFO/DEBUG/FINE logs.\"),\n"
                        + "                    new LogNoisePatternConfiguration(\n"
                        + "                            \".*\\\\b(INFO|FINE|FINER|FINEST|CONFIG|DEBUG)\\\\b.*--- "
                        + "\\\\[[^\\\\]]*\\\\]\",\n"
                        + "                            \"Spring Boot / Tomcat / Jetty thread-runner logs.\"),\n"
                        + "                    new LogNoisePatternConfiguration(\n"
                        + "                            \"^(Spring Boot\\\\s.*|\\\\s*\\\\.\\\\s*____.*|\\\\s*\\\\/\\\\\\\\ \\\\/ ___'_ __ _.*|\\\\s*\\\\([^)]*\\\\)\\\\s*\\\\\\\\___.*|\\\\s*\\\\\\\\\\\\/\\\\s*___\\\\).*|\\\\s*'.*\\\\|____.*|\\\\s*=+.*\\\\|_\\\\|.*|\\\\s*:: Spring Boot ::.*)\",\n"
                        + "                            \"Spring Boot banner.\"),\n"
                        + "                    new LogNoisePatternConfiguration(\n"
                        + "                            \"^(Starting Servlet engine|Initializing Spring|Completed "
                        + "initialization|Initializing Reactor Netty|Tomcat started on port|Jetty started on port) .*\",\n"
                        + "                            \"Embedded server startup one-liners.\"),\n"
                        + "                    new LogNoisePatternConfiguration(\n"
                        + "                            \"^(Starting a Gradle Daemon|Welcome to Gradle|To honour the "
                        + "JVM settings|To honour the JVM-compiler settings) .*\",\n"
                        + "                            \"Gradle daemon banner.\"))")
        List<LogNoisePatternConfiguration> excludes) {

    public LogNoiseConfiguration {
        includes = includes == null ? List.of() : includes;
        excludes = excludes == null ? List.of() : excludes;
    }
}
