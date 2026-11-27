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
package io.yupiik.ia.agent.acp;

import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.lifecycle.Stop;
import io.yupiik.fusion.framework.api.main.Args;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.framework.build.api.order.Order;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/** Reserves protocol stdout before configuration consumers and terminal redirection initialize. */
@ApplicationScoped
public class AcpStdio {
    private final Optional<Args> args;
    private PrintStream protocol;
    private PrintStream redirected;

    protected AcpStdio() {
        this.args = Optional.empty();
    }

    public AcpStdio(final Optional<Args> args) {
        this.args = args;
    }

    public void reserve(@OnEvent @Order(Integer.MIN_VALUE) final ConfigurationRegistration registration) {
        final var arguments = args.map(Args::args).orElseGet(List::of);
        if (!arguments.isEmpty() && "acp".equals(arguments.getFirst()) && protocol == null) {
            protocol = System.out;
            redirected = System.err;
            System.setOut(redirected);
        }
    }

    /** Keep shutdown diagnostics off protocol stdout until all other Stop listeners have run. */
    public void restore(@OnEvent @Order(Integer.MAX_VALUE) final Stop stop) {
        if (protocol != null) {
            if (System.out == redirected) {
                System.setOut(protocol);
            }
            protocol = null;
            redirected = null;
        }
    }

    public PrintStream protocolOutput() {
        if (protocol == null) {
            throw new IllegalStateException("ACP protocol streams were not initialized");
        }
        return protocol;
    }
}
