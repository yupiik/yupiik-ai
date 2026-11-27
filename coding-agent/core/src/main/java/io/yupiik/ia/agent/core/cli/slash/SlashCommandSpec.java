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
package io.yupiik.ia.agent.core.cli.slash;

public record SlashCommandSpec(String name, String usage, String description, SlashGroup group) {

    public SlashCommandSpec {
        if (group == null) {
            throw new IllegalArgumentException("group is required for " + name);
        }
    }

    public String display() {
        return usage == null || usage.isBlank() ? name : name + " " + usage;
    }

    /** Command name without the leading '/': "help" for "/help". */
    public String command() {
        return name.startsWith("/") ? name.substring(1) : name;
    }
}
