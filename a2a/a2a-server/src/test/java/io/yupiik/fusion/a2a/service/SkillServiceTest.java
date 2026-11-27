/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.service;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.a2a.api.A2ASkills;
import io.yupiik.fusion.a2a.model.AgentSkill;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * How the descriptive annotations are read back out of the bean metadata.
 * <p>
 * The shapes asserted here are the ones the annotation processor really produces - an annotation member arrives as
 * {@code AnnotationValue#toString()}, so quoted, while a plain {@code @BeanMetadata} value arrives bare. That is a javac
 * detail, so it is pinned rather than assumed.
 */
@FusionSupport
class SkillServiceTest {
    @Test
    void annotationValuesAreQuoted() {
        // exactly what @A2ATags("demo,greeting") + @A2AExamples(...) store, observed on a real build
        final var skill = SkillService.describedByMetadata(
                derived(),
                Map.of(
                        "a2a.type", "skill",
                        "a2a.tags", "\"demo,greeting\"",
                        "a2a.examples", "\"Greet Fusion.|Say hello, please.\"",
                        "a2a.outputModes", "\"text/plain\""));

        assertEquals(List.of("demo", "greeting"), skill.tags());
        // the pipe separator is what lets an example hold a comma
        assertEquals(List.of("Greet Fusion.", "Say hello, please."), skill.examples());
        assertEquals(List.of("text/plain"), skill.outputModes());
        assertNull(skill.inputModes(), "an absent key must not blank the value");
    }

    @Test
    void plainMetadataValuesAreBare() {
        // what @BeanMetadata(name = "a2a.tags", value = "demo,greeting") stores, per fusion's own jsonRpcMetadata test
        final var skill = SkillService.describedByMetadata(derived(), Map.of("a2a.tags", "demo,greeting"));

        assertEquals(List.of("demo", "greeting"), skill.tags());
    }

    @Test
    void arrayRenderingIsTolerated() {
        // a String[] member would arrive as {"a", "b"}, which must not leak braces into the card
        final var skill = SkillService.describedByMetadata(derived(), Map.of("a2a.tags", "{\"demo\", \"greeting\"}"));

        assertEquals(List.of("demo", "greeting"), skill.tags());
    }

    @Test
    void escapesAreUndone() {
        final var skill =
                SkillService.describedByMetadata(derived(), Map.of("a2a.examples", "\"Say \\\"hello\\\" to Fusion.\""));

        assertEquals(List.of("Say \"hello\" to Fusion."), skill.examples());
    }

    @Test
    void nameOverridesTheMethodName() {
        final var skill = SkillService.describedByMetadata(derived(), Map.of("a2a.name", "\"Greeter\""));

        assertEquals("Greeter", skill.name());
        assertEquals("demo/greet", skill.id(), "the identifier stays the method name");
    }

    @Test
    void blankAndEmptyValues() {
        final var blank = SkillService.describedByMetadata(derived(), Map.of("a2a.tags", "\"\""));
        assertEquals(List.of(), blank.tags(), "an explicitly empty value clears the tags");

        final var spaced = SkillService.describedByMetadata(derived(), Map.of("a2a.tags", "\"demo , greeting\""));
        assertEquals(List.of("demo", "greeting"), spaced.tags(), "values are trimmed");
    }

    @Test
    void aValueWhichOnlyLooksLikeAnArrayOrAQuotedString() {
        // the unwrapping is a best effort on what the annotation processor may have stored, so a half decoration has to
        // be taken literally rather than half stripped
        assertEquals(
                List.of("{"),
                SkillService.describedByMetadata(derived(), Map.of("a2a.tags", "{"))
                        .tags());
        assertEquals(
                List.of("{demo"),
                SkillService.describedByMetadata(derived(), Map.of("a2a.tags", "{demo"))
                        .tags());
        assertEquals(
                List.of("demo}"),
                SkillService.describedByMetadata(derived(), Map.of("a2a.tags", "demo}"))
                        .tags());
        assertEquals(
                "\"",
                SkillService.describedByMetadata(derived(), Map.of("a2a.name", "\""))
                        .name());
        assertEquals(
                "\"Greeter",
                SkillService.describedByMetadata(derived(), Map.of("a2a.name", "\"Greeter"))
                        .name());
    }

    @Test
    void anAgentWithoutAnyAnnotatedMethod(@Fusion final JsonMapper jsons) {
        // no method is flagged in this module, so nothing the OpenRPC document describes is a skill here
        final var skills = new SkillService(new OpenRpcService(jsons), new JsonRpcRegistry(List.of()), null);

        assertEquals(Set.of(), skills.methodBackedIds());
        assertNull(skills.singleMethodBackedId(), "there is no single skill to route to");
        assertFalse(skills.isMethodBacked("demo/greet"));
        assertEquals(List.of(), skills.skills());
    }

    @Test
    void aSingleFlaggedMethodNeedsNoRouting(@Fusion final JsonMapper jsons) {
        // a method the document describes and the registry flags is a skill; here the A2A document is the one this
        // module
        // generates, so GetTask stands in for an application method
        final var skills = new SkillService(
                new OpenRpcService(jsons), new JsonRpcRegistry(List.of(flagged("GetTask"))), List.of());

        assertEquals(Set.of("GetTask"), skills.methodBackedIds());
        assertTrue(skills.isMethodBacked("GetTask"));
        assertEquals("GetTask", skills.singleMethodBackedId(), "one skill means a client needs not name it");
        // the descriptor comes from the document, so it cannot drift from the method
        assertEquals(1, skills.skills().size());
        assertEquals("GetTask", skills.skills().get(0).id());
        assertEquals("Gets the latest state of a task.", skills.skills().get(0).description());
    }

    @Test
    void aMethodTheRegistryDoesNotFlagIsNotASkill(@Fusion final JsonMapper jsons) {
        final var skills =
                new SkillService(new OpenRpcService(jsons), new JsonRpcRegistry(List.of(plain("GetTask"))), List.of());

        assertEquals(Set.of(), skills.methodBackedIds(), "a plain JSON-RPC method is not an advertised ability");
    }

    @Test
    void contributedSkillsAreEnriched(@Fusion final JsonMapper jsons) {
        final var declared = AgentSkill.of("report/watch", "Watch", "Watches a city.", List.of("report"));
        final var skills = new SkillService(
                new OpenRpcService(jsons),
                new JsonRpcRegistry(List.of()),
                List.of(
                        new A2ASkills() {
                            @Override
                            public List<AgentSkill> skills() {
                                return List.of(declared);
                            }
                        },
                        // an implementation with nothing to say about a skill keeps what the previous produced
                        new A2ASkills() {
                            @Override
                            public AgentSkill describe(final AgentSkill derived) {
                                return null;
                            }
                        },
                        new A2ASkills() {
                            @Override
                            public AgentSkill describe(final AgentSkill derived) {
                                return derived.named("Watcher");
                            }
                        },
                        // and one contributing nothing of its own must not add an entry either
                        new A2ASkills() {
                            @Override
                            public List<AgentSkill> skills() {
                                return null;
                            }
                        }));

        assertEquals(
                List.of("report/watch"),
                skills.skills().stream().map(AgentSkill::id).toList());
        assertEquals("Watcher", skills.skills().get(0).name());
        // it is advertised but not callable by the built-in router: an executor answers it
        assertFalse(skills.isMethodBacked("report/watch"));
    }

    @Test
    void withoutAnyMetadataTheDescriptorIsUntouched() {
        final var derived = derived();

        assertSame(derived, SkillService.describedByMetadata(derived, Map.of()));
        assertSame(derived, SkillService.describedByMetadata(derived, null));
    }

    /**
     * What the OpenRPC document gives before the metadata is applied.
     */
    private AgentSkill derived() {
        return AgentSkill.of("demo/greet", "demo/greet", "Greets someone by name.", List.of());
    }

    /**
     * @param name a JSON-RPC method name.
     * @return that method, carrying the metadata {@code @A2ASkill} stores at build time.
     */
    private static JsonRpcMethod flagged(final String name) {
        return new JsonRpcMethod() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Map<String, String> metadata() {
                return Map.of(SkillService.TYPE_METADATA, SkillService.SKILL);
            }

            @Override
            public CompletionStage<?> invoke(final Context context) {
                return completedFuture(null);
            }
        };
    }

    private static JsonRpcMethod plain(final String name) {
        return new JsonRpcMethod() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public CompletionStage<?> invoke(final Context context) {
                return completedFuture(null);
            }
        };
    }
}
