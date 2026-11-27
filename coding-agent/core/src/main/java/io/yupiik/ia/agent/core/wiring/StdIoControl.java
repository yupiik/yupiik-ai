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
package io.yupiik.ia.agent.core.wiring;

import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.framework.build.api.scanning.Bean;
import java.util.List;
import java.util.Optional;

/**
 * Owns the {@link HackStdIo} lifecycle: installs the terminal takeover once the
 * configuration - hence the {@code yca.logging.auto} switch - is readable, and
 * restores the original streams when the container closes.
 * ACP owns protocol stdout instead, so its container's command arguments disable this takeover.
 * <p>
 * This is a plain discovered bean (like {@code YcaConfigurationProducer}), not
 * a {@code FusionModule}: the fusion processor owns the
 * {@code META-INF/services/...FusionModule} file and rewrites it when it runs,
 * while Maven copies {@code src/main/resources} over it on every incremental
 * build, so a hand-written services entry silently drops all the generated
 * beans. All mutable state lives in the {@link HackStdIo} instance this bean
 * produces: there is no static field.
 * <p>
 * The installation waits for the {@link ConfigurationRegistration} event: it
 * fires when the {@code Configuration} singleton is created, at which point
 * every configuration source (including {@code JsonConfigurationSource}) is
 * already registered and can contribute {@code yca.logging.auto}. The method
 * runs <em>after</em> those registrations (high {@link Order}), and there is no
 * application logging before that point: JUL's default {@code ConsoleHandler}
 * is still created against the hijacked streams, so it keeps its user
 * configured format and filter and the logging manager itself - its handlers,
 * levels, {@code useParentHandlers} - is left exactly as configured.
 */
@ApplicationScoped
public class StdIoControl {
    /** Higher than any configuration source registration, so {@code yca.logging.auto} is visible when we install. */
    static final int INSTALL_PRIORITY = Integer.MAX_VALUE - 1;

    private final HackStdIo hack = new HackStdIo();
    private final Optional<Args> args;

    /** Bean proxy constructor; the active instance uses the injection constructor. */
    protected StdIoControl() {
        this.args = Optional.empty();
    }

    public StdIoControl(final Optional<Args> args) {
        this.args = args;
    }

    /** The single terminal-stream control shared by every consumer. */
    @Bean
    @ApplicationScoped
    public HackStdIo hackStdIo() {
        return hack;
    }

    public void install(@OnEvent @Order(INSTALL_PRIORITY) final ConfigurationRegistration registration) {
        final var arguments = args.map(Args::args).orElseGet(List::of);
        if (!arguments.isEmpty() && "acp".equals(arguments.getFirst())) {
            return;
        }
        final var auto = registration
                .configuration()
                .get("yca.logging.auto")
                .map(Boolean::parseBoolean)
                .orElse(true);
        if (auto) {
            hack.install();
        }
    }

    public void uninstall(@OnEvent final Stop stop) {
        hack.uninstall();
    }
}
