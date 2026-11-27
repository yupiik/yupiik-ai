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
package io.yupiik.fusion.a2a.demo;

import io.yupiik.fusion.a2a.api.A2ASkills;
import io.yupiik.fusion.a2a.model.AgentSkill;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.List;

/**
 * Declares {@code demo/countdown}, which {@link DemoCountdownExecutor} answers.
 * <p>
 * The three skills of {@link DemoSkills} need nothing here: they are methods, so their descriptors are declared right on
 * them with {@code @A2ATags} and friends, at build time. This one has no method to annotate, which is exactly what
 * {@link A2ASkills#skills()} is for - and {@link A2ASkills#describe} would be for a descriptor which has to be computed.
 */
@ApplicationScoped
public class DemoSkillDescriptors implements A2ASkills {
    @Override
    public List<AgentSkill> skills() {
        return List.of(new AgentSkill(
                DemoCountdownExecutor.SKILL_ID,
                "Countdown",
                "Counts down from a number, reporting the progress as it goes.",
                List.of("demo", "streaming", "progress"),
                List.of("Count down from 3."),
                null,
                List.of("text/plain"),
                null));
    }
}
