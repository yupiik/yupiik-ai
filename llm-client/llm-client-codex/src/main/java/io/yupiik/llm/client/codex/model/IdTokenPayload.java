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
import java.util.List;

/**
 * The subset of a Codex id-token payload yca needs to derive account routing metadata, mirroring
 * {@code codex-rs/login/src/token_data.rs}. The mapper reads only these fields and ignores the rest,
 * so deserialization is robust to formatting (eg. {@code "chatgpt_account_is_fedramp": false} with a
 * space), unlike regex-over-raw-JSON parsing.
 *
 * @param email the account email.
 * @param preferredUsername an alternate email claim some tokens use.
 * @param auth the nested {@code https://api.openai.com/auth} claim object (the modern/primary form).
 * @param flatAuthAccountId the flat legacy claim {@code https://api.openai.com/auth.chatgpt_account_id}.
 * @param flatAccountId the bare legacy claim {@code chatgpt_account_id}.
 * @param organizations the organizations/workspaces the account belongs to.
 */
@JsonModel
public record IdTokenPayload(
        String email,
        @JsonProperty("preferred_username") String preferredUsername,
        @JsonProperty("https://api.openai.com/auth") Auth auth,

        @JsonProperty("https://api.openai.com/auth.chatgpt_account_id")
        String flatAuthAccountId,

        @JsonProperty("chatgpt_account_id") String flatAccountId,
        List<Organization> organizations) {

    /** The namespaced {@code https://api.openai.com/auth} claim object. */
    @JsonModel
    public record Auth(
            @JsonProperty("chatgpt_account_id") String accountId,
            @JsonProperty("chatgpt_user_id") String userId,
            @JsonProperty("chatgpt_plan_type") String planType,
            @JsonProperty("chatgpt_account_is_fedramp") boolean chatgptAccountIsFedramp,
            @JsonProperty("exp") Long exp) {}

    /** A single organization/workspace entry. */
    @JsonModel
    public record Organization(
            String id,
            @JsonProperty("is_default") boolean isDefault,
            @JsonProperty("is_active") boolean isActive) {}
}
