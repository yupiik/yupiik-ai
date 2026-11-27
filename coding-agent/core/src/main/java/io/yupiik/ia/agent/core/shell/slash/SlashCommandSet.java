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
package io.yupiik.ia.agent.core.shell.slash;

import io.yupiik.fusion.framework.build.api.order.Order;
import java.util.List;

/**
 * A group of slash commands contributed to {@link SlashRegistry}. Beans are
 * collected by the registry (ordered by {@link Order}); domain modules can
 * contribute their own commands without touching any central dispatch code,
 * and shells contribute their own commands on top of the shared ones.
 */
public interface SlashCommandSet {
    List<SlashCommand> commands();

    /** Human-readable set title, used by documentation and help rendering. */
    default String title() {
        return getClass().getSimpleName();
    }
}
