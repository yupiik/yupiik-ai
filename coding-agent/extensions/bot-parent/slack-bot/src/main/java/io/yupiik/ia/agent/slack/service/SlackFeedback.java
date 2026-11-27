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

import io.yupiik.ia.agent.bot.feedback.FeedbackMailbox;
import io.yupiik.ia.agent.slack.configuration.SlackConfiguration;
import io.yupiik.ia.agent.slack.configuration.SlackPrompts;
import io.yupiik.ia.agent.slack.model.SlackJson;
import io.yupiik.ia.agent.slack.model.SlackQuestion;
import io.yupiik.ia.agent.slack.model.SlackQuestionRequest;
import io.yupiik.ia.agent.slack.model.SlackTask;
import io.yupiik.ia.agent.slack.model.TaskStatus;
import io.yupiik.ia.agent.slack.store.SlackStore;
import io.yupiik.ia.agent.slack.transport.SlackWebApi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Controller-owned interaction authority. No Slack token or publication authority reaches the mailbox. */
public final class SlackFeedback {
    private static final Pattern OPTION_ID = Pattern.compile("o[0-9]{1,2}");

    private final SlackConfiguration configuration;
    private final SlackPrompts presentation;
    private final SlackStore store;
    private final SlackWebApi api;

    public SlackFeedback(
            final SlackConfiguration configuration,
            final SlackPrompts presentation,
            final SlackStore store,
            final SlackWebApi api) {
        this.configuration = configuration;
        this.presentation = presentation;
        this.store = store;
        this.api = api;
    }

    /** Called only by the admitted worker while retaining its slot and pinned mailbox. */
    public void poll(final String taskId, final FeedbackMailbox mailbox) throws IOException {
        final var request = mailbox.pending();
        if (request == null) {
            return;
        }
        var task = find(taskId);
        if (task.question() != null && task.question().request().nonce().equals(request.nonce())) {
            final var question = task.question();
            if ("answered".equals(question.state())) {
                mailbox.answer(question.request().mailbox(), question.answers(), false);
            } else if ("cancelled".equals(question.state())
                    || System.currentTimeMillis() >= question.request().expires()) {
                mailbox.answer(question.request().mailbox(), Map.of(), true);
            }
            return;
        }
        if (request.expires() <= System.currentTimeMillis()
                || request.expires()
                        > System.currentTimeMillis() + configuration.questionTimeoutSeconds() * 1000L + 1000) {
            mailbox.answer(request, Map.of(), true);
            return;
        }
        final var pending = new SlackQuestion(SlackQuestionRequest.from(request), "publishing", null, null, null);
        final var publish = store.transaction(tasks -> {
            for (int index = 0; index < tasks.size(); index++) {
                final var current = tasks.get(index);
                if (current.id().equals(taskId)) {
                    if (current.status() != TaskStatus.running || current.attempt() != task.attempt()) {
                        return false;
                    }
                    tasks.set(
                            index, current.feedback(pending, TaskStatus.waiting_for_user, System.currentTimeMillis()));
                    return true;
                }
            }
            return false;
        });
        if (!publish) {
            return;
        }
        // Persist intent before sending; an ambiguous remote failure is never retried automatically.
        final var response = api.postMessage(new SlackJson.PostMessage(
                        task.channel(),
                        task.thread(),
                        presentation.questionTitle(),
                        null,
                        null,
                        List.of(
                                Map.of("type", "section", "text", plain(presentation.questionTitle())),
                                Map.of(
                                        "type",
                                        "actions",
                                        "elements",
                                        List.of(
                                                button("answer", request.nonce(), presentation.answerLabel()),
                                                button("cancel", request.nonce(), presentation.cancelLabel()))))))
                .toCompletableFuture()
                .join();
        final var ts = response.ts();
        if (ts == null || !task.channel().equals(response.channel())) {
            throw new IllegalStateException("Invalid Slack question publication result");
        }
        update(taskId, current -> {
            if (current.question() == null
                    || !current.question().request().nonce().equals(request.nonce())) {
                return current;
            }
            final var cancelled = current.status() == TaskStatus.cancel_requested;
            if (!cancelled && current.status() != TaskStatus.waiting_for_user) {
                return current;
            }
            return current.feedback(
                    new SlackQuestion(
                            SlackQuestionRequest.from(request), cancelled ? "cancelled" : "waiting", ts, null, null),
                    current.status(),
                    System.currentTimeMillis());
        });
    }

    /** Executes only short durable authorization/submission work on the Socket Mode delivery path. */
    public Optional<SlackJson.Reply> interaction(final SlackJson.Payload payload) {
        if (payload == null || payload.team() == null || payload.user() == null) {
            return Optional.empty();
        }
        final var user = payload.user().id();
        if (!configuration.workspace().equals(payload.team().id()) || !configuration.allowsRequester(user)) {
            return Optional.empty();
        }
        if ("block_actions".equals(payload.type())) {
            final var actions = payload.actions();
            if (actions == null || actions.size() != 1 || actions.getFirst() == null) {
                return Optional.empty();
            }
            final var action = actions.getFirst();
            final var nonce = action.value();
            final var task = active(nonce, user);
            if (task == null
                    || task.question().messageTs() == null
                    || payload.channel() == null
                    || payload.message() == null
                    || !task.channel().equals(payload.channel().id())
                    || !task.question().messageTs().equals(payload.message().ts())) {
                return Optional.empty();
            }
            if ("cancel".equals(action.action_id())
                    && List.of("waiting", "opening", "open")
                            .contains(task.question().state())) {
                update(
                        task.id(),
                        current -> current.question() != null
                                        && current.question()
                                                .request()
                                                .nonce()
                                                .equals(task.question()
                                                        .request()
                                                        .nonce())
                                        && List.of("waiting", "opening", "open")
                                                .contains(current.question().state())
                                ? current.feedback(
                                        new SlackQuestion(
                                                current.question().request(),
                                                "cancelled",
                                                current.question().messageTs(),
                                                current.question().viewId(),
                                                Map.of()),
                                        TaskStatus.cancel_requested,
                                        System.currentTimeMillis())
                                : current);
            } else if ("answer".equals(action.action_id())
                    && payload.trigger_id() != null
                    && payload.trigger_id().length() < 256) {
                // Discovery/publication scheduling is independent of this priority call. No synchronous
                // network wait
                // before ACK.
                final var claimed = store.transaction(tasks -> {
                    for (int i = 0; i < tasks.size(); i++) {
                        final var current = tasks.get(i);
                        if (current.id().equals(task.id())
                                && "waiting".equals(current.question().state())) {
                            tasks.set(
                                    i,
                                    current.feedback(
                                            new SlackQuestion(
                                                    current.question().request(),
                                                    "opening",
                                                    current.question().messageTs(),
                                                    null,
                                                    null),
                                            TaskStatus.waiting_for_user,
                                            System.currentTimeMillis()));
                            return true;
                        }
                    }
                    return false;
                });
                if (claimed) {
                    api.openView(new SlackJson.OpenView(payload.trigger_id(), modal(task)))
                            .whenComplete((result, failure) -> {
                                if (failure == null
                                        && result.view() != null
                                        && result.view().id() != null) {
                                    final var viewId = result.view().id();
                                    update(
                                            task.id(),
                                            current -> current.status() == TaskStatus.waiting_for_user
                                                            && "opening"
                                                                    .equals(current.question()
                                                                            .state())
                                                    ? current.feedback(
                                                            new SlackQuestion(
                                                                    current.question()
                                                                            .request(),
                                                                    "open",
                                                                    current.question()
                                                                            .messageTs(),
                                                                    viewId,
                                                                    null),
                                                            TaskStatus.waiting_for_user,
                                                            System.currentTimeMillis())
                                                    : current);
                                } else {
                                    // Unknown open outcome cannot safely reuse a trigger or bind an unknown modal.
                                    update(
                                            task.id(),
                                            current -> current.question() != null
                                                            && current.question()
                                                                    .request()
                                                                    .nonce()
                                                                    .equals(task.question()
                                                                            .request()
                                                                            .nonce())
                                                            && current.status() == TaskStatus.waiting_for_user
                                                            && "opening"
                                                                    .equals(current.question()
                                                                            .state())
                                                    ? current.transition(
                                                            TaskStatus.recovery_blocked, System.currentTimeMillis())
                                                    : current);
                                }
                            });
                }
            }
        } else if ("view_submission".equals(payload.type()) || "view_closed".equals(payload.type())) {
            final var view = payload.view();
            if (view == null || !"yca_feedback".equals(view.callback_id())) {
                return Optional.empty();
            }
            final var task = active(view.private_metadata(), user);
            if (task == null
                    || task.question().viewId() == null
                    || !task.question().viewId().equals(view.id())) {
                return Optional.of(
                        new SlackJson.Reply(null, "errors", Map.of("q0", "This question is no longer active.")));
            }
            if ("view_closed".equals(payload.type())) {
                update(
                        task.id(),
                        current -> current.question() != null
                                        && current.question()
                                                .request()
                                                .nonce()
                                                .equals(task.question()
                                                        .request()
                                                        .nonce())
                                        && List.of("waiting", "opening", "open")
                                                .contains(current.question().state())
                                ? current.feedback(
                                        new SlackQuestion(
                                                current.question().request(),
                                                "cancelled",
                                                current.question().messageTs(),
                                                current.question().viewId(),
                                                Map.of()),
                                        TaskStatus.cancel_requested,
                                        System.currentTimeMillis())
                                : current);
                return Optional.empty();
            }
            try {
                final var answers = answers(
                        task.question().request().mailbox(),
                        view.state() == null || view.state().values() == null
                                ? Map.of()
                                : view.state().values());
                FeedbackMailbox.validateAnswers(task.question().request().mailbox(), answers);
                update(task.id(), current -> {
                    if ("answered".equals(current.question().state())) {
                        return current;
                    }
                    if (!"open".equals(current.question().state())) {
                        throw new IllegalStateException("Question is no longer active");
                    }
                    return current.feedback(
                            new SlackQuestion(
                                    current.question().request(),
                                    "answered",
                                    current.question().messageTs(),
                                    current.question().viewId(),
                                    answers),
                            TaskStatus.running,
                            System.currentTimeMillis());
                });
            } catch (final IllegalArgumentException | IllegalStateException invalid) {
                return Optional.of(
                        new SlackJson.Reply(null, "errors", Map.of("q0", "Choose valid answers for every question.")));
            }
        }
        return Optional.empty();
    }

    private SlackJson.Modal modal(final SlackTask task) {
        final var blocks = new ArrayList<Map<String, Object>>();
        final var questions = task.question().request().questions();
        for (int index = 0; index < questions.size(); index++) {
            final var question = questions.get(index);
            final Map<String, Object> element;
            if (question.options().isEmpty()) {
                element =
                        Map.of("type", "plain_text_input", "action_id", "value", "multiline", true, "max_length", 3000);
            } else {
                final var options = new ArrayList<Map<String, Object>>();
                for (int option = 0; option < question.options().size(); option++) {
                    options.add(Map.of("text", plain(question.options().get(option)), "value", "o" + option));
                }
                element = Map.of(
                        "type",
                        question.multiple() ? "multi_static_select" : "static_select",
                        "action_id",
                        "value",
                        "options",
                        options);
            }
            blocks.add(Map.of(
                    "type",
                    "input",
                    "block_id",
                    "q" + index,
                    "label",
                    plain(question.prompt()
                            .substring(0, Math.min(200, question.prompt().length()))),
                    "element",
                    element));
        }
        return new SlackJson.Modal(
                "modal",
                true,
                "yca_feedback",
                task.question().request().nonce(),
                plain(presentation.questionTitle()),
                plain(presentation.answerLabel()),
                plain(presentation.cancelLabel()),
                blocks);
    }

    private Map<String, Object> answers(final FeedbackMailbox.Request request, final Map<?, ?> values) {
        if (values.size() != request.questions().size()) {
            throw new IllegalArgumentException("Unexpected question blocks");
        }
        final var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < request.questions().size(); index++) {
            final var question = request.questions().get(index);
            final var value = object(object(values.get("q" + index)).get("value"));
            if (question.options().isEmpty()) {
                result.put(question.id(), value.get("value"));
            } else if (question.multiple()) {
                if (!(value.get("selected_options") instanceof List<?> selected)) {
                    throw new IllegalArgumentException("Missing choices");
                }
                result.put(
                        question.id(),
                        selected.stream()
                                .map(option -> option(question, object(option).get("value")))
                                .toList());
            } else {
                result.put(
                        question.id(),
                        option(question, object(value.get("selected_option")).get("value")));
            }
        }
        return result;
    }

    private String option(final FeedbackMailbox.Item question, final Object value) {
        if (!(value instanceof String id) || !OPTION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid opaque choice");
        }
        final var index = Integer.parseInt(id.substring(1));
        if (index >= question.options().size()) {
            throw new IllegalArgumentException("Invalid choice index");
        }
        return question.options().get(index);
    }

    private SlackTask active(final String nonce, final String actor) {
        return store.list().stream()
                .filter(task -> !task.terminal()
                        && configuration.workspace().equals(task.workspace())
                        && configuration.channels().contains(task.channel())
                        && task.status() != TaskStatus.recovery_blocked
                        && task.requester().equals(actor)
                        && task.question() != null
                        && List.of("waiting", "opening", "open", "answered")
                                .contains(task.question().state())
                        && task.question().request().nonce().equals(nonce)
                        && task.question().request().expires() > System.currentTimeMillis())
                .findFirst()
                .orElse(null);
    }

    private SlackTask find(final String id) {
        return store.list().stream()
                .filter(task -> task.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void update(final String id, final UnaryOperator<SlackTask> change) {
        store.transaction(tasks -> {
            for (int i = 0; i < tasks.size(); i++) {
                if (tasks.get(i).id().equals(id)) {
                    tasks.set(i, change.apply(tasks.get(i)));
                    return null;
                }
            }
            throw new IllegalStateException("Unknown Slack task");
        });
    }

    private Map<?, ?> object(final Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private SlackJson.Text plain(final String text) {
        return new SlackJson.Text("plain_text", text);
    }

    private Map<String, Object> button(final String action, final String nonce, final String text) {
        return Map.of("type", "button", "action_id", action, "value", nonce, "text", plain(text));
    }
}
