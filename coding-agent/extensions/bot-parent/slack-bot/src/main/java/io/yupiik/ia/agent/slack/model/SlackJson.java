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
package io.yupiik.ia.agent.slack.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;
import java.util.Map;

/** Slack wire projections. Nullable fields preserve absence for consumer authorization/validation. */
public final class SlackJson {
    private SlackJson() {}

    public interface Result {
        Boolean ok();

        String error();
    }

    @JsonModel
    public record ApiResponse(Boolean ok, String error, String channel, String ts, ViewIdentity view)
            implements Result {}

    @JsonModel
    public record ConnectionResponse(Boolean ok, String error, String url) implements Result {}

    @JsonModel
    public record ViewIdentity(String id) {}

    @JsonModel
    public record Envelope(
            String type, String envelope_id, Boolean accepts_response_payload, String reason, Payload payload) {}

    /** Events API and interaction payload projections share only their discriminator. */
    @JsonModel
    public record Payload(
            String type,
            String team_id,
            String event_id,
            Boolean is_ext_shared_channel,
            Mention event,
            Identity team,
            Identity user,
            Identity channel,
            Message message,
            List<Action> actions,
            String trigger_id,
            View view) {}

    @JsonModel
    public record Mention(
            String type,
            String bot_id,
            String subtype,
            String channel,
            String user,
            String ts,
            String thread_ts,
            String text,
            Boolean is_ext_shared_channel,
            String action_token) {
        @Override
        public String toString() {
            return "Mention[redacted]";
        }
    }

    @JsonModel
    public record UserInfoResponse(Boolean ok, String error, Member user) implements Result {}

    @JsonModel
    public record Member(
            String id,
            String team_id,
            String name,
            String real_name,
            Boolean deleted,
            Boolean is_bot,
            Profile profile) {}

    @JsonModel
    public record Profile(String display_name, String real_name) {}

    /** Single thread page; timestamps remain exact Slack strings and null options are omitted on the wire. */
    @JsonModel
    public record ThreadRequest(
            String channel, String ts, String oldest, String latest, Boolean inclusive, Integer limit, String cursor) {
        @Override
        public String toString() {
            return "ThreadRequest[redacted]";
        }
    }

    /** Channel/team metadata is optional in Slack replies; consumers validate it only when provided. */
    @JsonModel
    public record ThreadResponse(
            Boolean ok,
            String error,
            String channel,
            String team,
            List<ThreadMessage> messages,
            Boolean has_more,
            ResponseMetadata response_metadata)
            implements Result {
        @Override
        public String toString() {
            return "ThreadResponse[redacted]";
        }
    }

    @JsonModel
    public record ThreadMessage(
            String type,
            String ts,
            String thread_ts,
            String user,
            String bot_id,
            String text,
            String subtype,
            String channel,
            String team,
            ThreadEdited edited,
            Boolean hidden) {
        @Override
        public String toString() {
            return "ThreadMessage[redacted]";
        }
    }

    @JsonModel
    public record ThreadEdited(String user, String ts) {}

    @JsonModel
    public record SearchRequest(
            String query,
            String action_token,
            List<String> channel_types,
            List<String> content_types,
            String context_channel_id,
            Boolean include_context_messages,
            Boolean include_message_blocks,
            Boolean include_bots,
            Integer limit,
            String cursor,
            Long after,
            Long before,
            String sort,
            String sort_dir,
            Boolean disable_semantic_search) {
        @Override
        public String toString() {
            return "SearchRequest[redacted]";
        }
    }

    @JsonModel
    public record SearchResponse(Boolean ok, String error, SearchResults results, ResponseMetadata response_metadata)
            implements Result {}

    @JsonModel
    public record SearchResults(List<SearchMessage> messages) {}

    @JsonModel
    public record SearchMessage(
            String author_user_id,
            String team_id,
            String channel_id,
            String message_ts,
            String content,
            String permalink) {}

    @JsonModel
    public record ResponseMetadata(String next_cursor) {}

    @JsonModel
    public record Identity(String id) {}

    @JsonModel
    public record Message(String ts) {}

    @JsonModel
    public record Action(String action_id, String value) {}

    @JsonModel
    public record View(String id, String callback_id, String private_metadata, InputState state) {}

    /** Block/action IDs and input shapes are extensible Slack Block Kit leaves, validated against the pinned question. */
    @JsonModel
    public record InputState(Map<String, Object> values) {}

    @JsonModel
    public record Reply(String text, String response_action, Map<String, String> errors) {}

    @JsonModel
    public record Ack(String envelope_id, Reply payload) {}

    /** Block Kit is an extensible discriminated tree, not an authorization envelope. */
    @JsonModel
    public record PostMessage(
            String channel,
            String thread_ts,
            String text,
            Boolean unfurl_links,
            Boolean unfurl_media,
            List<Map<String, Object>> blocks) {}

    @JsonModel
    public record OpenView(String trigger_id, Modal view) {}

    @JsonModel
    public record Text(String type, String text) {}

    @JsonModel
    public record Modal(
            String type,
            Boolean notify_on_close,
            String callback_id,
            String private_metadata,
            Text title,
            Text submit,
            Text close,
            List<Map<String, Object>> blocks) {}
}
