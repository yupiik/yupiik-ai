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
 * The wire level: constants, error mapping, the two JSON bindings and the streams.
 * <ul>
 *     <li>{@link io.yupiik.fusion.a2a.protocol.A2AJSONRPCProtocol} maps the operations on {@code @JsonRpc} methods and
 *     {@link io.yupiik.fusion.a2a.protocol.A2AEndpoint} serves them on {@code POST /a2a},</li>
 *     <li>{@link io.yupiik.fusion.a2a.protocol.A2ARestProtocol} serves the same operations as plain HTTP under
 *     {@code /a2a/v1},</li>
 *     <li>{@link io.yupiik.fusion.a2a.protocol.AgentCardEndpoint} publishes the agent card,</li>
 *     <li>{@link io.yupiik.fusion.a2a.protocol.SseBus} and {@link io.yupiik.fusion.a2a.protocol.A2AStream} are the
 *     server to client channel,</li>
 *     <li>{@link io.yupiik.fusion.a2a.protocol.A2AErrors} is the error catalog, shared by both bindings.</li>
 * </ul>
 * Both bindings delegate to {@link io.yupiik.fusion.a2a.service.A2AService}, so they behave the same by construction.
 * The gRPC binding of section 10 of the specification is not implemented: it needs a gRPC stack, which is out of the
 * scope of the Fusion HTTP server.
 */
package io.yupiik.fusion.a2a.protocol;
