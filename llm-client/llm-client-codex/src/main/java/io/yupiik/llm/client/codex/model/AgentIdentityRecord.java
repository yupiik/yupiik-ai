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
package io.yupiik.llm.client.codex.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

/**
 * The durable enterprise Agent Identity registration for a ChatGPT account, mirroring the
 * {@code agent_identity} record OpenAI's Codex CLI keeps in {@code ~/.codex/auth.json}. It is the
 * result of the two-phase enrollment (agent runtime registration then run task registration)
 * performed by {@code io.yupiik.llm.client.codex.CodexAgentIdentityClient}, and it drives the
 * signed {@code AgentAssertion} request header instead of the plain bearer token.
 *
 * <p>See also {@code codex-rs/login/src/auth/storage.rs} {@code AgentIdentityAuthRecord} in
 * {@code openai/codex}.</p>
 *
 * @param agentRuntimeId the id returned by {@code POST /v1/agent/register}.
 * @param agentPrivateKeyPkcs8Base64 the Ed25519 private key (PKCS#8 DER, base64) used to sign run
 *     task registrations and request assertions.
 * @param accountId the {@code chatgpt_account_id} of the enrolled account.
 * @param chatgptUserId the {@code chatgpt_user_id} of the enrolled account.
 * @param email the account email, when disclosed by the id token.
 * @param planType the account plan type from the id token (eg. {@code free}, {@code plus}, {@code
 *     pro}, {@code business}, {@code enterprise}).
 * @param chatgptAccountIsFedramp whether the account must route through the FedRAMP edge.
 * @param taskId the run task id returned by {@code POST /v1/agent/{runtime}/task/register}; absent
 *     until the per-run task registration has run once.
 */
@JsonModel
public record AgentIdentityRecord(
        @JsonProperty("agent_runtime_id") String agentRuntimeId,
        @JsonProperty("agent_private_key") String agentPrivateKeyPkcs8Base64,
        @JsonProperty("account_id") String accountId,
        @JsonProperty("chatgpt_user_id") String chatgptUserId,
        String email,
        @JsonProperty("plan_type") String planType,
        @JsonProperty("chatgpt_account_is_fedramp") boolean chatgptAccountIsFedramp,
        @JsonProperty("task_id") String taskId) {

    /** True when the per-run task registration still has to run (no usable task id yet). */
    public boolean needsTaskRegistration() {
        return taskId == null || taskId.isBlank();
    }

    /** True when there is not even a device identity yet (no agent route id / runtime). */
    public boolean needsEnrollment() {
        return agentRuntimeId == null || agentRuntimeId.isBlank();
    }

    /** Copy of this record carrying a (fresh) run task id. */
    public AgentIdentityRecord withTaskId(final String runTaskId) {
        return new AgentIdentityRecord(
                agentRuntimeId,
                agentPrivateKeyPkcs8Base64,
                accountId,
                chatgptUserId,
                email,
                planType,
                chatgptAccountIsFedramp,
                runTaskId);
    }
}
