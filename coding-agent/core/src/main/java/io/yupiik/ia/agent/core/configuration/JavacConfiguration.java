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
 * Configuration of the spawned-JVM Java parser (native image only, when no in-process javac is
 * available): which JDK to fork and which JVM options to pass to that child process.
 */
@JsonModel
public record JavacConfiguration(
        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Optional explicit {@code java} launcher (or JDK home) used to run the spawned-JVM Java parser in the "
                                + "native binary. When set, it is authoritative but still validated against the parser bytecode level; "
                                + "if it cannot load the parser the Java graph is disabled rather than failing per query. Empty falls "
                                + "back to JDK discovery (JAVA_HOME, PATH, Maven toolchains and common install roots).")
        String jdkHome,

        @Property(
                documentation =
                        "Optional numeric Java feature level (e.g. 21 or 25) forcing the project source level (V2) used to pick the "
                                + "JDK for parsing project sources. Overrides {@code .sdkmanrc}/{@code .java-version} and "
                                + "{@code maven-compiler-plugin} detection. The actual spawned JDK is never below the parser bytecode "
                                + "level even when this is lower.")
        Integer jdkFeature,

        @Property(
                defaultValue = "java.util.List.of(\"-XX:+UseSerialGC\", \"-XX:TieredStopAtLevel=1\", \"-Xshare:auto\")",
                documentation =
                        "JVM options passed to the spawned-JVM Java parser child process. Defaults to lightweight startup flags "
                                + "(-XX:+UseSerialGC, -XX:TieredStopAtLevel=1, -Xshare:auto) that cut per-spawn JVM "
                                + "boot cost. A non-empty list replaces the defaults entirely.")
        List<String> javacOptions) {

    public static final List<String> DEFAULT_JAVAC_OPTIONS =
            List.of("-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xshare:auto");

    /** The effective JVM options for the child: the configured list, or the safe defaults when null/empty. */
    public List<String> effectiveJavacOptions() {
        return javacOptions == null || javacOptions.isEmpty() ? DEFAULT_JAVAC_OPTIONS : javacOptions;
    }
}
