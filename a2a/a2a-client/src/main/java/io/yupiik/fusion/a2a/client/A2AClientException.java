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
package io.yupiik.fusion.a2a.client;

import java.util.List;

/**
 * What an A2A call fails with when the agent answered an error.
 * <p>
 * {@link #code()} is the JSON-RPC code, so it can be compared with the constants of
 * {@link io.yupiik.fusion.a2a.protocol.A2AErrors} - {@code TASK_NOT_FOUND} for example - whatever the binding used to
 * reach the agent.
 */
public class A2AClientException extends RuntimeException {
    private final int code;
    private final List<Object> details;

    /**
     * @param code    the JSON-RPC error code.
     * @param message the human readable message.
     * @param details the structured details, each one carrying a {@code @type} key.
     */
    public A2AClientException(final int code, final String message, final List<Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : details;
    }

    /**
     * @return the JSON-RPC error code, see {@link io.yupiik.fusion.a2a.protocol.A2AErrors}.
     */
    public int code() {
        return code;
    }

    /**
     * @return the {@code google.rpc} details the agent attached, an empty list when there is none.
     */
    public List<Object> details() {
        return details;
    }
}
