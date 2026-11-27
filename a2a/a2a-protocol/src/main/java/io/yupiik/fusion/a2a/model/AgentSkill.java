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
package io.yupiik.fusion.a2a.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

/**
 * Something the agent can do.
 * <p>
 * Skills are <em>descriptive</em>: a client does not call a skill, it sends a message and the agent decides. They are
 * what another agent reads to know this agent is worth delegating to.
 *
 * @param id                   the skill identifier, it is the JSON-RPC method name for a {@code @A2ASkill} method.
 * @param name                 a human readable name.
 * @param description          a detailed description, it comes from the {@code @JsonRpc} documentation.
 * @param tags                 keywords describing the skill, the specification requires at least one.
 * @param examples             example prompts the skill handles.
 * @param inputModes           the media types this skill accepts, it overrides
 *                             {@link AgentCard#defaultInputModes()}.
 * @param outputModes          the media types this skill produces, it overrides
 *                             {@link AgentCard#defaultOutputModes()}.
 * @param securityRequirements the schemes needed for this specific skill.
 */
@JsonModel
public record AgentSkill(
        @Property(documentation = "The skill identifier, it is the JSON-RPC method name for a @A2ASkill method.")
        String id,

        @Property(documentation = "A human readable name.") String name,

        @Property(documentation = "A detailed description, it comes from the @JsonRpc documentation.")
        String description,

        @Property(documentation = "Keywords describing the skill, the specification requires at least one.")
        List<String> tags,

        @Property(documentation = "Example prompts the skill handles.")
        List<String> examples,

        @Property(documentation = "The media types this skill accepts, it overrides AgentCard#defaultInputModes().")
        List<String> inputModes,

        @Property(documentation = "The media types this skill produces, it overrides AgentCard#defaultOutputModes().")
        List<String> outputModes,

        @Property(documentation = "The schemes needed for this specific skill.")
        List<SecurityRequirement> securityRequirements) {
    public static AgentSkill of(final String id, final String name, final String description, final List<String> tags) {
        return new AgentSkill(id, name, description, tags, null, null, null, null);
    }

    /**
     * @param tags        the keywords to expose.
     * @param examples    the example prompts to expose, it can be {@code null}.
     * @param inputModes  the accepted media types, it can be {@code null}.
     * @param outputModes the produced media types, it can be {@code null}.
     * @return a copy of this skill with the given descriptive attributes, which is how the {@code @A2ATags} family of
     * annotations and {@link io.yupiik.fusion.a2a.api.A2ASkills} complete a skill derived from a method signature.
     */
    public AgentSkill describedBy(
            final List<String> tags,
            final List<String> examples,
            final List<String> inputModes,
            final List<String> outputModes) {
        return new AgentSkill(id, name, description, tags, examples, inputModes, outputModes, securityRequirements);
    }

    /**
     * @param name the human readable name to display.
     * @return a copy of this skill named {@code name}, its identifier is unchanged.
     */
    public AgentSkill named(final String name) {
        return new AgentSkill(id, name, description, tags, examples, inputModes, outputModes, securityRequirements);
    }
}
