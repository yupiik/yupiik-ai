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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Controls how the runtime takes the terminal over from the JDK.
 * <p>
 * Levels, formats, handlers and {@code useParentHandlers} are <em>not</em>
 * configured here: the takeover runs before the first logger is created, so the
 * default {@link java.util.logging.ConsoleHandler} is itself created against the
 * hijacked {@code System.err} and the logging manager stays exactly as
 * configured (JDK defaults when unconfigured). A record is formatted and
 * filtered by the real handler and still reaches the command owning the
 * terminal.
 */
@JsonModel
public record LoggingConfiguration(
        @Property(
                defaultValue = "true",
                documentation =
                        "Take over System.out/System.err at startup and route every write (including the root JUL handlers) to the command owning the terminal. Set to false to keep the raw streams; note that an interactive prompt is then exposed to any writer that does not know about it.")
        boolean auto) {}
