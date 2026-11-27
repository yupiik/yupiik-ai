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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@FusionSupport(modules = ConfigurationTestModule.class)
class GradleOutputProcessorTest {
    private GradleOutputProcessor processor;

    @BeforeEach
    void init(@Fusion final GradleOutputProcessor processor) {
        this.processor = processor;
    }

    @Test
    void supportsGradleOnly() {
        assertTrue(processor.supports("gradle build"));
        assertTrue(processor.supports("./gradlew test"));
        assertFalse(processor.supports("mvn test"));
    }

    @Test
    void ignoresPlainWordWarningInOutput() {
        // a normal line containing the word "warning" must not be collected
        final var out = processor
                .process(
                        "gradle build",
                        List.of("BUILD SUCCESSFUL", "note: this is fine", "some warning about the weather").stream())
                .toList();
        assertTrue(out.stream().anyMatch(l -> l.startsWith("BUILD SUCCESSFUL")));
        assertFalse(out.stream().anyMatch(l -> l.startsWith("Warnings")));
    }

    @Test
    void collectsRealWarningMarkers() {
        final var out = processor
                .process(
                        "gradle build",
                        List.of(
                                "w: /src/Main.java:3:1: [deprecation] Foo.bar() in Foo has been deprecated",
                                "BUILD SUCCESSFUL")
                                .stream())
                .toList();
        assertTrue(out.stream().anyMatch(l -> l.startsWith("Warnings")));
        assertTrue(out.stream().anyMatch(l -> l.contains("deprecation")));
    }

    @Test
    void reportsPassingTasks() {
        final var out = processor
                .process("gradle build", List.of("> Task :compileJava", "> Task :test", "BUILD SUCCESSFUL").stream())
                .toList();
        assertTrue(out.stream()
                .anyMatch(l -> l.contains("Passing tasks (2):") && l.contains(":compileJava") && l.contains(":test")));
    }

    @Test
    void listsFailures() {
        final var out = processor
                .process(
                        "gradle build",
                        List.of(
                                "> Task :test FAILED",
                                "    SomeTest > shouldFail FAILED",
                                "        org.junit.ComparisonFailure: expected:<a> but was:<b>",
                                "BUILD FAILED")
                                .stream())
                .toList();
        assertTrue(out.stream().anyMatch(l -> l.contains("FAILED")));
        assertTrue(out.stream().anyMatch(l -> l.contains("ComparisonFailure")));
    }
}
