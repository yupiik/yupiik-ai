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
package io.yupiik.ia.agent.slack.service;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.context.SlackActionRegistry;
import io.yupiik.ia.agent.slack.model.SlackJson;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.store.SlackStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Authenticated transport is not authorization: exact trusted installation/channel/user policy is mandatory. */
@ApplicationScoped
public class SlackAdmission {
    private static final Logger LOGGER = Logger.getLogger(SlackAdmission.class.getName());
    private static final Pattern EVENT_ID = Pattern.compile("Ev[A-Za-z0-9]+");
    private static final Pattern THREAD_TIMESTAMP = Pattern.compile("[0-9]{1,20}\\.[0-9]{1,20}");

    private final SlackConfiguration configuration;
    private final SlackMentionParser parser;
    private final SlackStore store;
    private final SlackActionRegistry actions;
    private final Clock clock;

    public SlackAdmission(
            final SlackConfiguration configuration,
            final SlackMentionParser parser,
            final SlackStore store,
            final SlackActionRegistry actions,
            final Clock clock) {
        this.configuration = configuration;
        this.parser = parser;
        this.store = store;
        this.actions = actions;
        this.clock = clock;
    }

    public Optional<String> admit(final SlackJson.Payload payload) {
        configuration.validateInstallation();
        if (payload == null) {
            LOGGER.fine("Slack admission ignored: missing_payload");
            return Optional.empty();
        }
        final var eventId = payload.event_id();
        final var event = payload.event();
        if (!"event_callback".equals(payload.type())
                || !configuration.workspace().equals(payload.team_id())) {
            LOGGER.fine("Slack admission ignored: installation_or_callback");
            return Optional.empty();
        }
        if (eventId == null
                || eventId.length() > 128
                || !EVENT_ID.matcher(eventId).matches()
                || event == null
                || !"app_mention".equals(event.type())
                || event.bot_id() != null
                || event.subtype() != null
                || event.channel() == null
                || event.user() == null
                || event.ts() == null) {
            LOGGER.fine("Slack admission ignored: invalid_event");
            return Optional.empty();
        }
        if (!configuration.channels().contains(event.channel())) {
            LOGGER.fine("Slack admission denied: channel_not_approved");
            return Optional.empty();
        }
        if (!configuration.allowsRequester(event.user())) {
            LOGGER.fine("Slack admission denied: requester_not_allowed");
            return Optional.empty();
        }
        if (!configuration.allowSlackConnect()
                && (!Boolean.FALSE.equals(payload.is_ext_shared_channel())
                        || Boolean.TRUE.equals(event.is_ext_shared_channel()))) {
            LOGGER.fine("Slack admission denied: shared_channel_policy");
            return Optional.empty();
        }
        final var channel = event.channel();
        final var user = event.user();
        final var thread = event.thread_ts() != null ? event.thread_ts() : event.ts();
        if (!THREAD_TIMESTAMP.matcher(thread).matches()
                || !THREAD_TIMESTAMP.matcher(event.ts()).matches()
                || new BigDecimal(event.ts()).compareTo(new BigDecimal(thread)) < 0) {
            LOGGER.fine("Slack admission ignored: invalid_thread");
            return Optional.empty();
        }
        final var parsed = parser.parse(event.text(), configuration.botUser(), configuration.maxInputChars());
        if (parsed.isEmpty()) {
            LOGGER.fine("Slack admission ignored: invalid_mention");
            return Optional.empty();
        }
        final var id = SlackStore.identity(configuration.workspace() + ":" + eventId);
        final var conversation =
                SlackStore.identity(configuration.workspace() + ":" + channel + ":" + thread + ":" + user);
        final var outcome = actions.admission(() -> {
            final var now = clock.millis();
            final var committed = store.transaction(tasks -> {
                if (tasks.stream().anyMatch(task -> task.id().equals(id))) {
                    return new Decision("duplicate", null);
                }
                if (tasks.size() >= 10000
                        || tasks.stream().filter(task -> !task.terminal()).count() >= configuration.maxPending()
                        || tasks.stream()
                                        .filter(task -> !task.terminal()
                                                && task.requester().equals(user))
                                        .count()
                                >= configuration.maxPendingPerUser()) {
                    return new Decision("queue_full", null);
                }
                final var admitted = new SlackTask(
                        id,
                        configuration.workspace(),
                        channel,
                        thread,
                        user,
                        parsed.orElseThrow(),
                        conversation,
                        TaskStatus.queued,
                        now,
                        now,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        null);
                tasks.add(admitted);
                return new Decision("accepted", admitted);
            });
            if ("accepted".equals(committed.status())
                    && Boolean.FALSE.equals(payload.is_ext_shared_channel())
                    && !Boolean.TRUE.equals(event.is_ext_shared_channel())) {
                actions.admitted(committed.task(), event.action_token(), event.ts(), now);
            }
            return committed.status();
        });
        // Report acceptance only after the durable transaction has completed successfully.
        if ("accepted".equals(outcome)) {
            LOGGER.info("Slack admission accepted task");
        } else {
            LOGGER.fine("Slack admission: " + outcome);
        }
        return "queue_full".equals(outcome) ? Optional.empty() : Optional.of(id);
    }

    private record Decision(String status, SlackTask task) {}
}
