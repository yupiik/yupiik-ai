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
package io.yupiik.fusion.a2a.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.Map;

/**
 * A protocol extension the agent understands.
 * <p>
 * A client states which extensions it activates with the {@code A2A-Extensions} header. When an extension is
 * {@code required} and the client does not declare it, the server answers {@code ExtensionSupportRequiredError}.
 *
 * @param uri         the extension identifier.
 * @param description how this agent uses it.
 * @param required    when {@code true} a client must declare it to be served.
 * @param params      extension specific configuration.
 */
@JsonModel
public record AgentExtension(
        @Property(documentation = "The extension identifier.")
        String uri,

        @Property(documentation = "How this agent uses it.") String description,

        @Property(documentation = "When true a client must declare it to be served.")
        boolean required,

        @Property(documentation = "Extension specific configuration.")
        Map<String, Object> params) {
    public static AgentExtension of(final String uri, final String description, final boolean required) {
        return new AgentExtension(uri, description, required, null);
    }
}
