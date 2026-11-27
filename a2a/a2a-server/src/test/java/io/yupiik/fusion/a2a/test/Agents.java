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
package io.yupiik.fusion.a2a.test;

import io.yupiik.fusion.a2a.api.A2AAgentCard;
import io.yupiik.fusion.a2a.api.A2AExecutor;
import io.yupiik.fusion.a2a.configuration.A2AConfiguration;
import io.yupiik.fusion.a2a.service.A2AService;
import io.yupiik.fusion.a2a.service.A2ATasks;
import io.yupiik.fusion.a2a.service.AgentCardService;
import io.yupiik.fusion.a2a.service.SkillService;
import io.yupiik.fusion.a2a.signature.CardSigner;
import java.util.List;
import java.util.Optional;

/**
 * Assembles the A2A services by hand, i.e. the same graph the container builds - minus the transport.
 * <p>
 * This is what lets a test assert on a configuration the module cannot have two of: {@code @FusionSupport} starts one
 * container per module, so {@code streaming=false} or a tenant can only be reached this way.
 */
public final class Agents {
    private Agents() {
        // no-op
    }

    /**
     * @param configuration what to configure the agent with.
     * @param executors     what answers the messages, in the order they are visited.
     * @return the assembled agent.
     */
    public static Agent agent(final A2AConfiguration configuration, final A2AExecutor... executors) {
        return agent(configuration, List.of(), executors);
    }

    /**
     * @param configuration   what to configure the agent with.
     * @param cardCustomizers the {@link A2AAgentCard} beans contributing to the card.
     * @param executors       what answers the messages.
     * @return the assembled agent.
     */
    public static Agent agent(
            final A2AConfiguration configuration,
            final List<A2AAgentCard> cardCustomizers,
            final A2AExecutor... executors) {
        final var cards = cards(configuration, cardCustomizers);
        final var push = new RecordingPushNotifier();
        final var tasks = new A2ATasks(push, configuration, Optional.empty());
        return new Agent(
                configuration, tasks, cards, push, new A2AService(tasks, cards, configuration, List.of(executors)));
    }

    /**
     * @param configuration   what to configure the card with.
     * @param cardCustomizers the {@link A2AAgentCard} beans contributing to it.
     * @return the card service, without any skill - a skill needs an annotated method, which only the container sees.
     */
    public static AgentCardService cards(
            final A2AConfiguration configuration, final List<A2AAgentCard> cardCustomizers) {
        // the protected constructor is the no-skill one, and the skills are not what these tests assert on
        return new AgentCardService(
                configuration,
                new SkillService(null, null, null) {},
                cardCustomizers,
                new CardSigner(null, null, configuration));
    }

    /**
     * @param configuration the configuration the agent runs with.
     * @param tasks         the live task store.
     * @param cards         the card service, i.e. what the tenant and the output modes are validated against.
     * @param push          where the events are recorded instead of posted.
     * @param service       the binding independent operations.
     */
    public record Agent(
            A2AConfiguration configuration,
            A2ATasks tasks,
            AgentCardService cards,
            RecordingPushNotifier push,
            A2AService service) {}
}
