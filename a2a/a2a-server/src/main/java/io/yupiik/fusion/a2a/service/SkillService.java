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

import static java.util.stream.Collectors.toCollection;

import io.yupiik.fusion.a2a.api.A2ASkill;
import io.yupiik.fusion.a2a.api.A2ASkills;
import io.yupiik.fusion.a2a.model.AgentSkill;
import io.yupiik.fusion.a2a.model.fusion.OpenRpc;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns the JSON-RPC methods flagged with {@link A2ASkill} into {@link AgentSkill} descriptors.
 * <p>
 * Everything is computed once at startup, and nothing is read reflectively. A descriptor is built in three passes, each
 * one able to override the previous:
 * <ol>
 *     <li>the generated OpenRPC document gives the identifier, the name and the description - so they cannot drift from
 *     the method signature,</li>
 *     <li>the bean metadata the annotation processor stored gives what a signature cannot carry: the flag
 *     ({@code a2a.type}) plus the {@code @A2ATags}, {@code @A2AExamples}, {@code @A2AInputModes},
 *     {@code @A2AOutputModes} and {@code @A2ASkillName} values,</li>
 *     <li>the {@link A2ASkills} beans have the last word, for a descriptor which has to be computed, and may add skills
 *     of their own - a skill an {@link io.yupiik.fusion.a2a.api.A2AExecutor} answers has no method to annotate.</li>
 * </ol>
 */
@ApplicationScoped
public class SkillService {
    /**
     * Bean metadata key {@link A2ASkill} sets, see {@code BeanMetadataAlias}.
     */
    public static final String TYPE_METADATA = "a2a.type";

    /**
     * Value of {@link #TYPE_METADATA} for a skill.
     */
    public static final String SKILL = "skill";

    /**
     * Bean metadata keys the descriptive annotations set.
     */
    public static final String NAME_METADATA = "a2a.name";

    public static final String TAGS_METADATA = "a2a.tags";
    public static final String EXAMPLES_METADATA = "a2a.examples";
    public static final String INPUT_MODES_METADATA = "a2a.inputModes";
    public static final String OUTPUT_MODES_METADATA = "a2a.outputModes";

    /**
     * What separates the examples, see {@code @A2AExamples}: a comma would not do, an example is a sentence.
     */
    public static final char EXAMPLES_SEPARATOR = '|';

    private final List<AgentSkill> skills;
    private final Set<String> methodBackedIds;

    public SkillService(
            final OpenRpcService openRpcService, final JsonRpcRegistry registry, final List<A2ASkills> contributors) {
        if (openRpcService == null) { // generated lazy proxy
            this.skills = List.of();
            this.methodBackedIds = Set.of();
            return;
        }

        final var enrichers = contributors == null ? List.<A2ASkills>of() : contributors;
        final var openrpc = openRpcService.load();

        this.methodBackedIds = openrpc.methods().values().stream()
                // a document can describe a method which is not deployed - a module on the classpath but not used -
                // so ignore the ones the registry does not know
                .map(OpenRpc.JsonRpcMethod::name)
                .filter(name -> registry.methods().containsKey(name))
                .filter(name ->
                        SKILL.equals(registry.methods().get(name).metadata().getOrDefault(TYPE_METADATA, "")))
                .collect(toCollection(LinkedHashSet::new));

        final var methods = openrpc.methods().values().stream()
                .filter(it -> methodBackedIds.contains(it.name()))
                .sorted(Comparator.comparing(OpenRpc.JsonRpcMethod::name))
                .map(SkillService::toSkill)
                .map(skill -> describedByMetadata(
                        skill, registry.methods().get(skill.id()).metadata()))
                .map(skill -> enrich(enrichers, skill));
        final var contributions = enrichers.stream()
                .map(A2ASkills::skills)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(skill -> enrich(enrichers, skill));
        this.skills = Stream.concat(methods, contributions).toList();
    }

    /**
     * @return the skills to advertise in the agent card.
     */
    public List<AgentSkill> skills() {
        return skills;
    }

    /**
     * @param id a skill identifier.
     * @return {@code true} when {@code id} is a JSON-RPC method the built-in router can invoke - a skill contributed
     * by {@link A2ASkills#skills()} is advertised but not callable that way.
     */
    public boolean isMethodBacked(final String id) {
        return methodBackedIds.contains(id);
    }

    /**
     * @return the identifiers of the {@link A2ASkill} methods, in declaration order.
     */
    public Set<String> methodBackedIds() {
        return methodBackedIds;
    }

    /**
     * @return the only {@link A2ASkill} method identifier, {@code null} when there is none or more than one. It is what
     * makes a single skill agent work without the client having to name the skill.
     */
    public String singleMethodBackedId() {
        return methodBackedIds.size() == 1 ? methodBackedIds.iterator().next() : null;
    }

    private static AgentSkill toSkill(final OpenRpc.JsonRpcMethod method) {
        return AgentSkill.of(method.name(), method.name(), method.description(), List.of());
    }

    /**
     * Applies what the descriptive annotations stored in the bean metadata of the method.
     *
     * @param skill    the descriptor derived from the OpenRPC document.
     * @param metadata the metadata of the JSON-RPC method backing it.
     * @return the completed descriptor.
     */
    // visible for testing
    static AgentSkill describedByMetadata(final AgentSkill skill, final Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return skill;
        }
        final var described = skill.describedBy(
                values(metadata, TAGS_METADATA, ',', skill.tags()),
                values(metadata, EXAMPLES_METADATA, EXAMPLES_SEPARATOR, skill.examples()),
                values(metadata, INPUT_MODES_METADATA, ',', skill.inputModes()),
                values(metadata, OUTPUT_MODES_METADATA, ',', skill.outputModes()));
        final var name = value(metadata, NAME_METADATA);
        return name == null ? described : described.named(name);
    }

    /**
     * @param metadata the bean metadata.
     * @param key      the key to read.
     * @param separator what separates the values.
     * @param fallback what to answer when the key is absent, i.e. when no annotation was set.
     * @return the parsed values.
     */
    private static List<String> values(
            final Map<String, String> metadata, final String key, final char separator, final List<String> fallback) {
        final var raw = metadata.get(key);
        if (raw == null) {
            return fallback;
        }
        // the whole value is unwrapped first - an array rendering has to lose its braces before being split, else the
        // quotes of its elements end up inside them
        return Stream.of(unwrapList(raw).split(Pattern.quote(String.valueOf(separator))))
                .map(SkillService::unquote)
                .filter(it -> !it.isEmpty())
                .toList();
    }

    private static String value(final Map<String, String> metadata, final String key) {
        final var raw = metadata.get(key);
        return raw == null ? null : unquote(raw);
    }

    /**
     * Prepares a list value for splitting: an array rendering loses its braces and keeps its per element quotes, a plain
     * string loses its own.
     */
    private static String unwrapList(final String value) {
        final var current = value.strip();
        if (current.length() > 1 && current.startsWith("{") && current.endsWith("}")) {
            return current.substring(1, current.length() - 1);
        }
        return unquote(current);
    }

    /**
     * Undoes what the annotation processor did to one value.
     * <p>
     * A metadata value coming from an annotation member is stored as {@code AnnotationValue#toString()}, which renders a
     * string <em>with</em> its quotes, while a plain {@code @BeanMetadata} value arrives bare. Both spellings must read
     * the same, so the decoration is stripped rather than assumed away.
     */
    private static String unquote(final String value) {
        var current = value.strip();
        if (current.length() > 1 && current.charAt(0) == '"' && current.charAt(current.length() - 1) == '"') {
            current = current.substring(1, current.length() - 1);
        }
        return current.replace("\\\"", "\"").replace("\\\\", "\\").strip();
    }

    private static AgentSkill enrich(final List<A2ASkills> enrichers, final AgentSkill skill) {
        var current = skill;
        for (final var enricher : enrichers) {
            final var described = enricher.describe(current);
            if (described != null) {
                current = described;
            }
        }
        return current;
    }
}
