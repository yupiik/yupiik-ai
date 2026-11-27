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
package io.yupiik.ia.agent.core.optimize;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import io.yupiik.ia.agent.core.configuration.ConfigurationTestModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the log-noise filter: proves the {@code @Property(defaultValue=...)}
 * defaults (materialized through normal Fusion injection and
 * matched via {@link FastRegex}) drop the expected framework log lines and never drop
 * warnings/errors. Covers the yupiik-logging-jul, Spring Boot, Tomcat/Jetty, Gradle and Maven
 * formats.
 */
@FusionSupport(modules = ConfigurationTestModule.class)
class LogNoiseOutputProcessorTest {

    private LogNoiseOutputProcessor processor;

    @BeforeEach
    void init(@Fusion final LogNoiseOutputProcessor processor) {
        this.processor = processor;
    }

    @Test
    void dropsYupiikJulTimestampedInfoLogs() {
        assertTrue(
                processor.drop("2026-10-09T14:33:02.123 INFO [main] o.a.catalina.startup - Starting service [Tomcat]"));
        assertTrue(processor.drop("2026-10-09T14:33:09.777) DEBUG [Test worker] c.Foo - downloading..."));
        assertTrue(processor.drop("2026-10-09T14:33:02.123 FINE [main] io.yupiik - cache hit"));
        assertTrue(processor.drop("2026-10-09T14:33:02.123 CONFIG [main] io.yupiik - started"));
    }

    @Test
    void dropsSpringRunnerLogFormat() {
        assertTrue(processor.drop(
                "2026-10-10 12:00:00.123  INFO 1234 --- [main] c.e.MyService : Started MyService in 1.2s"));
        assertTrue(processor.drop(
                "2026-10-10 12:00:00.123 DEBUG 1234 --- [http-nio-8080] i.y.Filter  : request filtered"));
        assertTrue(processor.drop(
                "2026-10-10 12:00:00.123  INFO 1234 --- [main] o.s.boot.SpringApplication : Using Java 21"));
    }

    @Test
    void dropsSpringBootBanner() {
        assertTrue(processor.drop(" :: Spring Boot ::        (v3.4.0)"));
        assertTrue(processor.drop("  .   ____          _            __ _ _"));
        assertTrue(processor.drop(" /\\ / ___'_ __ _ _(_)_ __  __ _ \\ \\ \\ \\"));
        assertTrue(processor.drop("( ( )\\___ | '_ | '_| | '_ \\/ _` | \\ \\ \\ \\"));
        assertTrue(processor.drop(" \\/  ___)| |_)| | | | | || (_| |  ) ) ) )"));
        assertTrue(processor.drop(" =========|_|==============|___/=/_/_/_/"));
    }

    @Test
    void dropsEmbeddedServerStartupOneLiners() {
        assertTrue(processor.drop("Starting Servlet engine: [Apache Tomcat/10.1.31]"));
        assertTrue(processor.drop("Tomcat started on port 8080 (http)"));
        assertTrue(processor.drop("Completed initialization in 1 ms"));
    }

    @Test
    void dropsGradleDaemonBanner() {
        assertTrue(processor.drop("Starting a Gradle Daemon, 1 incompatible Daemon could not be reused"));
        assertTrue(processor.drop("Welcome to Gradle 8.10!"));
    }

    @Test
    void neverDropsWarningsOrErrorsEvenWhenShapedLikeALog() {
        assertFalse(processor.drop("2026-10-09T14:33:02.123 ERROR [main] o.Foo - boom"));
        assertFalse(processor.drop("2026-10-10 12:00:00.123  WARN 1234 --- [main] c.My : risky"));
        assertFalse(processor.drop("BUILD FAILED"));
        assertFalse(processor.drop("ERROR: could not resolve dependencies"));
        assertFalse(processor.drop("Some random line about the weather"));
    }

    @Test
    void keepsMeaningfulOutputLines() {
        assertFalse(processor.drop("Tests run: 12, Failures: 0, Errors: 0, Skipped: 0"));
        assertFalse(processor.drop("BUILD SUCCESSFUL"));
        assertFalse(processor.drop("[ERROR] COMPILATION ERROR"));
    }
}
