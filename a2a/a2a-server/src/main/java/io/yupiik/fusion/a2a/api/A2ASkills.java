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

import io.yupiik.fusion.a2a.model.AgentSkill;
import java.util.List;

/**
 * Contributes skill descriptors the annotations cannot express.
 * <p>
 * Most of the time there is nothing to implement here: what a method signature does not carry - the tags, the examples,
 * the media types - is declared on the method itself with {@link A2ATags}, {@link A2AExamples}, {@link A2AInputModes},
 * {@link A2AOutputModes} and {@link A2ASkillName}, at build time. This interface covers the two cases they cannot:
 * <ul>
 *     <li>{@link #skills()} declares a skill with <b>no method to annotate</b>, i.e. one an {@link A2AExecutor}
 *     answers,</li>
 *     <li>{@link #describe(AgentSkill)} <b>computes</b> a descriptor - tags read from configuration, examples pulled from
 *     a catalog - and runs after the annotations, so it has the last word.</li>
 * </ul>
 * <pre>{@code
 * @ApplicationScoped
 * public class MyAgentSkills implements A2ASkills {
 *     @Override
 *     public List<AgentSkill> skills() { // answered by an A2AExecutor, so there is no method to annotate
 *         return List.of(AgentSkill.of("report/watch", "Watch", "Watches a city.", List.of("report")));
 *     }
 * }
 * }</pre>
 * All the implementations are used in bean priority order ({@code @Order}): {@link #skills()} results are concatenated
 * and {@link #describe(AgentSkill)} is chained, each one seeing what the previous returned.
 * <p>
 * The card is built once at startup, so both methods must be cheap and stable.
 */
public interface A2ASkills {
    /**
     * @return skills to advertise which are not backed by a {@link A2ASkill} method - typically because an
     * {@link A2AExecutor} handles them.
     */
    default List<AgentSkill> skills() {
        return List.of();
    }

    /**
     * Refines a descriptor after the OpenRPC document and the annotations were applied.
     *
     * @param derived the descriptor so far: the method signature, completed by the {@link A2ATags} family, or an entry of
     *                {@link #skills()}. It is the result of the previous implementation in the chain.
     * @return the descriptor to advertise, return {@code derived} as is when the skill is not yours.
     */
    default AgentSkill describe(final AgentSkill derived) {
        return derived;
    }
}
