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
package io.yupiik.ia.agent.slack.command;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.slack.store.SlackStore;
import java.util.logging.Logger;

@DefaultScoped
@Command(
        name = {"slack", "bot", "list"},
        description = "List private durable Slack tasks.")
public class ListCommand implements Runnable {
    private final SlackStore store;
    private final JsonMapper json;

    @io.yupiik.fusion.framework.build.api.configuration.RootConfiguration("-")
    public record Options() {}

    public ListCommand(final Options options, final SlackStore store, final JsonMapper json) {
        this.store = store;
        this.json = json;
    }

    @Override
    public void run() {
        Logger.getLogger(getClass().getName()).info(json.toString(store.list()));
    }
}
