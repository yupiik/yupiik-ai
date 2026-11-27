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
/**
 * The A2A wire model, mapped from the normative
 * <a href="https://github.com/a2aproject/A2A/blob/v1.0.1/specification/a2a.proto">v1.0.1 {@code a2a.proto}</a>.
 * <p>
 * The specification is Protocol Buffer first and its JSON bindings use
 * <a href="https://protobuf.dev/programming-guides/json/">ProtoJSON</a>, so:
 * <ul>
 *     <li>a {@code snake_case} proto field becomes a {@code camelCase} JSON attribute - which is what a Java record
 *     component name gives for free,</li>
 *     <li>an enum is serialized with its proto name, i.e. {@code SCREAMING_SNAKE_CASE} - which is why
 *     {@link io.yupiik.fusion.a2a.model.TaskState} and {@link io.yupiik.fusion.a2a.model.Role} constants are
 *     named that way and not with the usual Java convention,</li>
 *     <li>a {@code google.protobuf.Struct} becomes a {@code Map<String, Object>} and a
 *     {@code google.protobuf.Value} an {@code Object},</li>
 *     <li>a {@code google.protobuf.Timestamp} becomes an ISO-8601 UTC {@code String}, see
 *     {@link io.yupiik.fusion.a2a.model.A2ATimestamps},</li>
 *     <li>a {@code oneof} becomes a record with one nullable component per branch - {@code null} components are
 *     not serialized so exactly one attribute is sent, see {@link io.yupiik.fusion.a2a.model.Part},
 *     {@link io.yupiik.fusion.a2a.model.StreamResponse} or
 *     {@link io.yupiik.fusion.a2a.model.SendMessageResponse}.</li>
 * </ul>
 * Every type is a {@code @JsonModel} record so the Fusion annotation processor generates its codec at build time -
 * no reflection at runtime, GraalVM friendly.
 * <p>
 * The JSON-RPC request envelopes are not mapped for the client to server direction: the Fusion JSON-RPC stack binds
 * {@code params} to the {@code @JsonRpc} method arguments. Only
 * {@link io.yupiik.fusion.a2a.model.JsonRpcStreamMessage} - the server to client direction - is mapped.
 */
package io.yupiik.fusion.a2a.model;
