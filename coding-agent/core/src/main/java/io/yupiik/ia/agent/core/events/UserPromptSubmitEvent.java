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
package io.yupiik.ia.agent.core.events;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Fires when a user prompt is about to enter the conversation (the initial prompt of a turn), before it is appended
 * to the history and sent to the model. Config key: {@code yca.hooks.UserPromptSubmit}.
 *
 * <p>The wire input is {@link Payload} (see {@link #payload()}); a callback may print a {@code HookResponse} to
 * block the prompt ({@code decision:"forbid"}) — the prompt is then skipped and the turn does not run — or to let
 * it proceed ({@code decision:"allow"}); a {@code context} is appended for the model.</p>
 */
public record UserPromptSubmitEvent(EventContext eventContext, String prompt, String source, HookResult result)
        implements YcaEvent {

    @Override
    public Object payload() {
        return new Payload(eventContext, prompt, source);
    }

    @Override
    public HookResult result() {
        return result;
    }

    @Override
    public String description() {
        return """
                Fires when a user prompt is about to enter the conversation (the initial prompt of a turn), before it \
                is appended to the history and sent to the model. Config key: `yca.hooks.UserPromptSubmit`.

                Input payload (`UserPromptSubmitEvent.Payload`, on stdin as camelCase JSON):
                - `eventContext`: shared session/agent/cwd/mode/model context
                - `prompt`: the prompt text
                - `source`: where the prompt came from (`user`, `initial`, `cli`, ...)

                Output (`HookResponse`, on stdout): `{"decision":"forbid","reason":"..."}` blocks the prompt (the \
                turn does not run, reason surfaces), `{"decision":"allow"}` lets it proceed, `{"context":"..."}` \
                appends free text for the model. Only empty/valid JSON are ok; invalid JSON, non-zero exit or \
                timeout raise an error and do not block the prompt.""";
    }

    /** The serializable input payload of {@link UserPromptSubmitEvent}: the prompt text and its source. */
    @JsonModel
    public record Payload(EventContext eventContext, String prompt, String source) {}
}
