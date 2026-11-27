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
import java.util.Optional;
import java.util.regex.Pattern;

/** Slack IDs are protocol literals, never regex source or display names. */
@ApplicationScoped
public class SlackMentionParser {
    private static final Pattern BOT_USER_ID = Pattern.compile("[UW][A-Z0-9]{1,63}");

    public Optional<String> parse(final String text, final String botUser, final int maximum) {
        if (text == null
                || text.length() > maximum
                || botUser == null
                || botUser.length() > 64
                || !BOT_USER_ID.matcher(botUser).matches()) {
            return Optional.empty();
        }
        final var marker = "<@" + botUser + ">";
        final var index = text.indexOf(marker);
        if (index < 0) {
            return Optional.empty();
        }
        final var request = (text.substring(0, index) + text.substring(index + marker.length())).strip();
        return request.isEmpty() ? Optional.empty() : Optional.of(request);
    }
}
