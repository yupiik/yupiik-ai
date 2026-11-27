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
package io.yupiik.ia.agent.core.agent;

import io.yupiik.ia.agent.core.llm.ChatSettings;
import io.yupiik.ia.agent.core.wiring.ProviderManager;

/**
 * The authoritative (provider, model) choice for an agent run, resolved by
 * {@link AgentModelResolver}: the concrete routed provider, the {@link ChatSettings} carrying the
 * effective provider id/model and inherited tuning, the bare effective model name, and the routed
 * {@code $provider/model} string the provider layer sees.
 */
public record EffectiveAgentModel(
        ProviderManager.ResolvedModel resolved, ChatSettings settings, String reference, String routedReference) {}
