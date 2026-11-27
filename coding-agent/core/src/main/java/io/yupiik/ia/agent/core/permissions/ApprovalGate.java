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
package io.yupiik.ia.agent.core.permissions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public interface ApprovalGate {
    Result approve(Request request);

    enum Origin {
        PERMISSION,
        SANDBOX
    }

    record Request(Origin origin, String title, String detail, String toolName, Map<String, Object> args) {
        public Request {
            Objects.requireNonNull(origin, "origin");
            args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }
    }

    enum Result {
        DENY,
        ALLOW_ONCE,
        ALLOW_SESSION
    }
}
