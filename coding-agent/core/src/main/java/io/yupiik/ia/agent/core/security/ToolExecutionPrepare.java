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
package io.yupiik.ia.agent.core.security;

import java.util.Map;

/**
 * Tool-execution prepare hook (mutable holder emitted by {@code ToolExecutionService} right before a
 * tool executes). The secret redactor observer restores provenance-registry fakes back to the real
 * values in the tool {@link #args} map so commands that need a real credential (a deploy, a curl)
 * still work; the provider never saw the real value because the outbound filter redacted it. Only
 * registry-tracked fakes are restored; any other placeholder a model invented is left untouched.
 */
public record ToolExecutionPrepare(String tool, Map<String, Object> args, String sessionId) {}
