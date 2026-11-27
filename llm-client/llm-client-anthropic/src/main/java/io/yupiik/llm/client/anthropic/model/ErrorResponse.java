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
package io.yupiik.llm.client.anthropic.model;

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Anthropic error shape: {@code {"type":"error","error":{"type":...,"message":...}}}.
 *
 * @param type wire object type, always {@code error}.
 * @param error provider error details.
 */
@JsonModel
public record ErrorResponse(String type, Error error) {

    /**
     * Provider error details.
     *
     * @param type error type, e.g. {@code invalid_request_error}.
     * @param message human readable error message.
     */
    @JsonModel
    public record Error(String type, String message) {}
}
