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
/**
 * What an application uses to become an A2A agent:
 * <ul>
 *     <li>{@link io.yupiik.fusion.a2a.api.A2ASkill} flags a {@code @JsonRpc} method as a skill - a build time only
 *     marker - and the built-in router invokes it,</li>
 *     <li>{@link io.yupiik.fusion.a2a.api.A2ATags}, {@link io.yupiik.fusion.a2a.api.A2AExamples},
 *     {@link io.yupiik.fusion.a2a.api.A2AInputModes}, {@link io.yupiik.fusion.a2a.api.A2AOutputModes} and
 *     {@link io.yupiik.fusion.a2a.api.A2ASkillName} declare, on the method, what its signature cannot carry - also
 *     build time only markers,</li>
 *     <li>{@link io.yupiik.fusion.a2a.api.A2AExecutor} takes full control of a message and of the task lifecycle,
 *     with {@link io.yupiik.fusion.a2a.api.AgentRequest} as input and
 *     {@link io.yupiik.fusion.a2a.api.TaskUpdater} as output,</li>
 *     <li>{@link io.yupiik.fusion.a2a.api.A2ASkills} contributes the descriptors the annotations cannot express: a
 *     skill with no method to annotate, or one which has to be computed,</li>
 *     <li>{@link io.yupiik.fusion.a2a.api.A2AAgentCard} customizes the published agent card.</li>
 * </ul>
 * To push updates on a task from outside an executor - a scheduler, an incoming webhook - get its updater with
 * {@link io.yupiik.fusion.a2a.service.A2ATasks#updater(java.lang.String)}.
 */
package io.yupiik.fusion.a2a.api;
