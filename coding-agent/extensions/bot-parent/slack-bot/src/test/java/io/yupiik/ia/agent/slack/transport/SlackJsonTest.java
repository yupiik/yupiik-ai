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
package io.yupiik.ia.agent.slack.transport;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.ia.agent.slack.model.SlackJson;
import io.yupiik.ia.agent.slack.support.SlackJsonMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlackJsonTest {
    @Test
    void decodesThreadRootRepliesEditsAndPaginationWithoutRequiringChannelOrTeam() {
        try (final var json = SlackJsonMapper.create()) {
            final var response = json.fromString(SlackJson.ThreadResponse.class, """
                    {"ok":true,"has_more":true,"response_metadata":{"next_cursor":"next+/="},
                     "messages":[
                       {"type":"message","ts":"120.000001","user":"U1","text":"root","reply_count":2},
                       {"type":"message","ts":"121.000002","thread_ts":"120.000001","bot_id":"B1",
                        "text":"edited reply","subtype":"bot_message","channel":"C1","team":"T1",
                        "edited":{"user":"U2","ts":"122.000003"},"hidden":false}]}
                    """);
            assertEquals(Boolean.TRUE, response.ok());
            assertNull(response.error());
            assertNull(response.channel());
            assertNull(response.team());
            assertEquals(Boolean.TRUE, response.has_more());
            assertEquals("next+/=", response.response_metadata().next_cursor());
            final var root = response.messages().getFirst();
            assertEquals("message", root.type());
            assertEquals("120.000001", root.ts());
            assertNull(root.thread_ts());
            assertEquals("U1", root.user());
            assertEquals("root", root.text());
            assertNull(root.bot_id());
            assertNull(root.edited());
            assertNull(root.hidden());
            assertNull(root.channel());
            assertNull(root.team());
            final var reply = response.messages().get(1);
            assertEquals("121.000002", reply.ts());
            assertEquals("120.000001", reply.thread_ts());
            assertEquals("B1", reply.bot_id());
            assertNull(reply.user());
            assertEquals("edited reply", reply.text());
            assertEquals("bot_message", reply.subtype());
            assertEquals("C1", reply.channel());
            assertEquals("T1", reply.team());
            assertEquals(new SlackJson.ThreadEdited("U2", "122.000003"), reply.edited());
            assertEquals(Boolean.FALSE, reply.hidden());
            assertEquals("ThreadMessage[redacted]", reply.toString());
            assertEquals("ThreadResponse[redacted]", response.toString());
        }
    }

    @Test
    void preservesThreadOptionalMetadataAndMissingSuccessForConsumerValidation() {
        try (final var json = SlackJsonMapper.create()) {
            final var missing = json.fromString(SlackJson.ThreadResponse.class, "{}");
            assertNull(missing.ok());
            assertNull(missing.messages());
            assertNull(missing.has_more());
            assertNull(missing.response_metadata());
            final var response = json.fromString(SlackJson.ThreadResponse.class, """
                    {"ok":true,"channel":"C1","team":"T1","has_more":false,"messages":[],
                     "response_metadata":{"next_cursor":""}}
                    """);
            assertEquals("C1", response.channel());
            assertEquals("T1", response.team());
            assertEquals(List.of(), response.messages());
            assertEquals(Boolean.FALSE, response.has_more());
            assertEquals("", response.response_metadata().next_cursor());
            assertEquals(
                    "ThreadRequest[redacted]",
                    new SlackJson.ThreadRequest("C1", "120.000001", null, null, null, null, "secret").toString());
        }
    }

    @Test
    void decodesEventsApiEnvelopeWithoutLosingAuthorizationOrThreadFields() {
        try (final var json = SlackJsonMapper.create()) {
            final var envelope = json.fromString(SlackJson.Envelope.class, """
                    {"type":"events_api","envelope_id":"E1","accepts_response_payload":false,
                     "payload":{"type":"event_callback","team_id":"T1","event_id":"Ev1",
                       "is_ext_shared_channel":false,"event":{"type":"app_mention",
                         "channel":"C1","user":"U1","ts":"123.456","thread_ts":"120.001",
                         "text":"<@UBOT> explain this","is_ext_shared_channel":true}}}
                    """);
            assertEquals("events_api", envelope.type());
            assertEquals("E1", envelope.envelope_id());
            assertEquals(Boolean.FALSE, envelope.accepts_response_payload());
            assertNull(envelope.reason());
            final var payload = envelope.payload();
            assertEquals("event_callback", payload.type());
            assertEquals("T1", payload.team_id());
            assertEquals("Ev1", payload.event_id());
            assertEquals(Boolean.FALSE, payload.is_ext_shared_channel());
            assertEquals(
                    new SlackJson.Mention(
                            "app_mention",
                            null,
                            null,
                            "C1",
                            "U1",
                            "123.456",
                            "120.001",
                            "<@UBOT> explain this",
                            true,
                            null),
                    payload.event());
            assertNull(payload.team());
            assertNull(payload.actions());
        }
    }

    @Test
    void decodesInteractiveEnvelopeWithExactActionAndMessageBinding() {
        try (final var json = SlackJsonMapper.create()) {
            final var envelope = json.fromString(SlackJson.Envelope.class, """
                    {"type":"interactive","envelope_id":"E2","accepts_response_payload":true,
                     "payload":{"type":"block_actions","team":{"id":"T1"},"user":{"id":"U1"},
                       "channel":{"id":"C1"},"message":{"ts":"123.456"},"trigger_id":"trigger",
                       "actions":[{"action_id":"answer","value":"nonce"}]}}
                    """);
            assertEquals("interactive", envelope.type());
            assertEquals("E2", envelope.envelope_id());
            assertEquals(Boolean.TRUE, envelope.accepts_response_payload());
            final var payload = envelope.payload();
            assertEquals("block_actions", payload.type());
            assertEquals(new SlackJson.Identity("T1"), payload.team());
            assertEquals(new SlackJson.Identity("U1"), payload.user());
            assertEquals(new SlackJson.Identity("C1"), payload.channel());
            assertEquals(new SlackJson.Message("123.456"), payload.message());
            assertEquals("trigger", payload.trigger_id());
            assertEquals(List.of(new SlackJson.Action("answer", "nonce")), payload.actions());
            assertNull(payload.event());
        }
    }

    @Test
    void preservesAbsentAndNullFieldsForConsumerValidationAndDecodesControlEnvelopes() {
        try (final var json = SlackJsonMapper.create()) {
            final var missing = new SlackJson.Envelope(null, null, null, null, null);
            assertEquals(missing, json.fromString(SlackJson.Envelope.class, "{}"));
            assertEquals(missing, json.fromString(SlackJson.Envelope.class, """
                    {"type":null,"envelope_id":null,"accepts_response_payload":null,"reason":null,"payload":null}
                    """));
            assertEquals(
                    new SlackJson.Envelope("disconnect", null, null, "refresh_requested", null),
                    json.fromString(SlackJson.Envelope.class, """
                            {"type":"disconnect","reason":"refresh_requested"}
                            """));
            assertEquals(
                    new SlackJson.Envelope("hello", null, null, null, null),
                    json.fromString(SlackJson.Envelope.class, "{\"type\":\"hello\"}"));
        }
    }

    @Test
    void decodesTypedApiAndDiscoveryResponses() {
        try (final var json = SlackJsonMapper.create()) {
            assertEquals(
                    new SlackJson.ApiResponse(true, null, "C1", "123.456", new SlackJson.ViewIdentity("V1")),
                    json.fromString(SlackJson.ApiResponse.class, """
                            {"ok":true,"channel":"C1","ts":"123.456","view":{"id":"V1"}}
                            """));
            assertEquals(
                    new SlackJson.ConnectionResponse(true, null, "wss://example.invalid/socket"),
                    json.fromString(SlackJson.ConnectionResponse.class, """
                            {"ok":true,"url":"wss://example.invalid/socket"}
                            """));
            assertNull(json.fromString(SlackJson.ApiResponse.class, "{}").ok());
            assertNull(json.fromString(SlackJson.ConnectionResponse.class, "{}").ok());
        }
    }

    @Test
    void preservesNullOptionalBoundariesAndHeterogeneousDynamicLeaves() {
        try (final var json = SlackJsonMapper.create()) {
            final var payload = json.fromString(SlackJson.Payload.class, """
                    {"team":null,"event":null,"actions":[{}],"view":{"state":{"values":{
                      "null":null,"boolean":false,"array":[1,"text",null,{"future":true}],"object":{}}}}}
                    """);
            assertNull(payload.team());
            assertNull(payload.event());
            assertEquals(List.of(new SlackJson.Action(null, null)), payload.actions());
            final var values = payload.view().state().values();
            assertTrue(values.containsKey("null"));
            assertNull(values.get("null"));
            assertEquals(Boolean.FALSE, values.get("boolean"));
            assertEquals(Map.of(), values.get("object"));
            assertEquals(json.fromString(Object.class, "[1,\"text\",null,{\"future\":true}]"), values.get("array"));
        }
    }

    @Test
    void ignoresFutureFieldsAndKeepsOnlyJustifiedDynamicInputLeaves() {
        try (final var json = SlackJsonMapper.create()) {
            final var envelope = json.fromString(SlackJson.Envelope.class, """
                {"type":"interactive","envelope_id":"E1","future":{"anything":true},
                 "payload":{"type":"view_submission","team":{"id":"T1","domain":"ignored"},
                   "user":{"id":"U1"},"view":{"id":"V1","callback_id":"yca_feedback",
                     "private_metadata":"nonce","state":{"values":{
                       "q0":{"value":{"type":"future_input","value":"answer"}}}}}}}
                """);
            assertEquals("E1", envelope.envelope_id());
            assertEquals("view_submission", envelope.payload().type());
            assertEquals("T1", envelope.payload().team().id());
            assertEquals("U1", envelope.payload().user().id());
            assertEquals("V1", envelope.payload().view().id());
            assertEquals("yca_feedback", envelope.payload().view().callback_id());
            assertEquals("nonce", envelope.payload().view().private_metadata());
            assertEquals(
                    Map.of("q0", Map.of("value", Map.of("type", "future_input", "value", "answer"))),
                    envelope.payload().view().state().values());
            assertEquals(
                    Map.of("envelope_id", "E1"),
                    json.fromString(Object.class, json.toString(new SlackJson.Ack("E1", null))));
            assertEquals(
                    Map.of("response_action", "errors", "errors", Map.of("q0", "Invalid answer")),
                    json.fromString(
                            Object.class,
                            json.toString(new SlackJson.Reply(null, "errors", Map.of("q0", "Invalid answer")))));
        }
    }
}
