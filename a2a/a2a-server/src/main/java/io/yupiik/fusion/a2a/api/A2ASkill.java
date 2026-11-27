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
package io.yupiik.fusion.a2a.api;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import io.yupiik.fusion.framework.build.api.metadata.BeanMetadataAlias;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Exposes a JSON-RPC method as an A2A skill, i.e. as one of the abilities the agent advertises in its
 * {@link io.yupiik.fusion.a2a.model.AgentCard}:
 * <pre>{@code
 * @ApplicationScoped
 * public class MySkills {
 *     @A2ASkill
 *     @JsonRpc(value = "report/build", documentation = "Builds a report about a city.")
 *     public Artifact build(@JsonRpcParam(required = true, documentation = "The city to report on.") final String city) {
 *         return Artifact.text("report", "all good in " + city);
 *     }
 * }
 * }</pre>
 * The JSON-RPC method name becomes the skill {@code id} and its {@code documentation} the skill
 * {@code description} - both come from the OpenRPC document the Fusion annotation processor generates, so there is
 * nothing to declare twice and no reflection at runtime. Implement {@link A2ASkills} to add the descriptive
 * attributes a method signature cannot carry: {@code tags}, {@code examples} and the media types.
 * <p>
 * A skill is <em>not</em> called by name the way a MCP tool is: A2A clients send a {@link
 * io.yupiik.fusion.a2a.model.Message} and the agent decides what to do with it. The built-in router picks the skill
 * from the {@code skillId} message metadata, or the only declared skill when there is exactly one - implement
 * {@link A2AExecutor} to route differently or to take full control of the task lifecycle.
 * <p>
 * The method can accept:
 * <ul>
 *     <li>a {@code Message} parameter named {@code message} to get the incoming message as is,</li>
 *     <li>any {@code @JsonRpcParam} bound from the structured arguments, i.e. the {@code data} part of the message
 *     merged with its {@code arguments} metadata,</li>
 *     <li>a {@code io.yupiik.fusion.http.server.api.Request} to reach the HTTP layer.</li>
 * </ul>
 * and return - directly or as a {@code CompletionStage} - an {@code Artifact}, a {@code List<Artifact>}, a
 * {@code Message} to answer without producing any output, a {@code Task} to control the whole state, any
 * {@code @JsonModel} to get it wrapped in a structured artifact, or {@code void} to just complete the task.
 * <p>
 * IMPORTANT: this is a marker, the flag is stored in the bean metadata ({@code a2a.type=skill}) at build time so it
 * does not need to be kept at runtime.
 *
 * @see A2AExecutor to implement an agent which is not a set of skills.
 * @see A2ASkills to enrich the generated descriptors.
 */
@Target(METHOD)
@Retention(SOURCE)
@BeanMetadataAlias(name = "a2a.type", value = "skill")
public @interface A2ASkill {}
