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
 * The client side of A2A: {@link io.yupiik.fusion.a2a.client.A2AClient} is what an agent uses to delegate to another
 * agent, and what a test uses to drive this server.
 * <p>
 * Everything is asynchronous - every method answers a {@code CompletionStage} and the streams are dispatched event by
 * event as the bytes arrive - and typed on the {@link io.yupiik.fusion.a2a.model} records, so the same objects travel in
 * both directions. It is shipped in the main artifact and only needs a {@code java.net.http.HttpClient} and a Fusion
 * {@code JsonMapper}.
 */
package io.yupiik.fusion.a2a.client;
