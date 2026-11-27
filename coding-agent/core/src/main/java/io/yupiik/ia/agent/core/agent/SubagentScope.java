/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.agent;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Immutable delegation ancestry and route ceiling, captured before scheduling a child. */
public record SubagentScope(
        int depth,
        String providerId,
        Predicate<EffectiveAgentModel> routes,
        Predicate<EffectiveAgentModel> authorizedRoutes,
        BooleanSupplier parentCancelled) {
    public boolean allows(final EffectiveAgentModel model) {
        return providerId.equals(model.settings().providerId()) && routes.test(model);
    }

    public boolean authorized(final EffectiveAgentModel model) {
        return allows(model) && authorizedRoutes.test(model);
    }
}
