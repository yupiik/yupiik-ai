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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.ia.agent.core.cli.CliCommands;
import io.yupiik.ia.agent.core.shell.btw.BtwSlashCommands;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the invariant that every shell command taking an argument documents
 * it through a completion: a command with a non-blank usage must register
 * something else than {@link ArgCompletion#NONE}, and a command with a
 * completion must document its usage. This replaces the exhaustive enum
 * switch the completion engine used to have, so a new arg-taking command can
 * no longer silently lose its completion.
 */
class SlashCommandCoverageTest {
    /** Commands whose argument is free-form by nature and gets no completion on purpose. */
    private static final Set<String> FREE_FORM_ARGUMENTS = Set.of("/btw");

    private static List<SlashCommand> allCommands() {
        final var out = new ArrayList<SlashCommand>();
        // the command sets only build specs and lambdas in commands(), they do
        // not dereference their services - null stays safe here
        out.addAll(new ConfigCommands(null, null, null, null, null, null, null).commands());
        out.addAll(new MaintainCommands(null, null, null).commands());
        out.addAll(new InspectCommands(null).commands());
        out.addAll(new SessionCommands(null, null).commands());
        out.addAll(new CliCommands(null).commands());
        out.addAll(new BtwSlashCommands(null).commands());
        return out;
    }

    @Test
    void everyArgumentTakingCommandHasCompletion() {
        final var missing = new ArrayList<String>();
        for (final var command : allCommands()) {
            final var usage = command.spec().usage();
            if (usage != null
                    && !usage.isBlank()
                    && !FREE_FORM_ARGUMENTS.contains(command.spec().name())
                    && command.completion() == ArgCompletion.NONE) {
                missing.add(command.spec().name() + " (" + usage + ")");
            }
        }
        assertTrue(missing.isEmpty(), "commands with a usage but no completion: " + missing);
    }

    @Test
    void everyCompletedCommandDocumentsItsUsage() {
        final var missing = new ArrayList<String>();
        for (final var command : allCommands()) {
            final var usage = command.spec().usage();
            if (command.completion() != ArgCompletion.NONE && (usage == null || usage.isBlank())) {
                missing.add(command.spec().name());
            }
        }
        assertTrue(missing.isEmpty(), "commands with a completion but no usage: " + missing);
    }

    /** The regression this whole completion work started from: /metrics must be completeable. */
    @Test
    void metricsCompletionIsRegistered() {
        final var registry = new SlashRegistry(List.of(new ConfigCommands(null, null, null, null, null, null, null)));
        assertNotEquals(ArgCompletion.NONE, registry.completionFor("metrics"), "/metrics must have a completion");
    }
}
