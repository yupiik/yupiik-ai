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
package io.yupiik.maven.knowledge.test;

import io.yupiik.maven.knowledge.Gav;
import io.yupiik.maven.knowledge.llm.KnowledgeLlm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Configurable, recording {@link KnowledgeLlm} for tests. Records how many bulk calls were made and with how
 * many GAVs, so tests can assert batching (multiple GAVs per single call).
 */
public final class StubLlm implements KnowledgeLlm {

    private final AtomicInteger entryPointCalls = new AtomicInteger();
    private final AtomicInteger totalEntryPointGavs = new AtomicInteger();
    private final AtomicReference<List<String>> entryPointResponse = new AtomicReference<>();
    private final AtomicReference<List<Gav>> suggestResponse = new AtomicReference<>();
    private volatile boolean available = true;
    private final List<Map<Gav, String>> entryPointBatches = new ArrayList<>();
    private final AtomicInteger synthesizeCalls = new AtomicInteger();
    private final AtomicReference<String> skillResponse = new AtomicReference<>();

    public StubLlm entryPoints(final String... fqns) {
        entryPointResponse.set(List.of(fqns));
        return this;
    }

    public StubLlm suggest(final Gav... gavs) {
        suggestResponse.set(List.of(gavs));
        return this;
    }

    public StubLlm unavailable() {
        available = false;
        return this;
    }

    /** Make {@link #synthesizeSkill} return this exact text (eg. a placeholder JSON shell). */
    public StubLlm skill(final String skill) {
        skillResponse.set(skill);
        return this;
    }

    /** @return true when {@link #synthesizeSkill} was ever called. */
    public boolean wasInvoked() {
        return synthesizeCalls.get() > 0;
    }

    public int synthesizeCallCount() {
        return synthesizeCalls.get();
    }

    public int entryPointCallCount() {
        return entryPointCalls.get();
    }

    public int totalEntryPointGavs() {
        return totalEntryPointGavs.get();
    }

    public int lastBatchSize() {
        synchronized (entryPointBatches) {
            if (entryPointBatches.isEmpty()) {
                return 0;
            }
            return entryPointBatches.get(entryPointBatches.size() - 1).size();
        }
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public Map<Gav, List<String>> entryPoints(final Map<Gav, String> contentByGav) {
        entryPointCalls.incrementAndGet();
        totalEntryPointGavs.addAndGet(contentByGav.size());
        final var resp = entryPointResponse.get();
        final var out = new LinkedHashMap<Gav, List<String>>();
        for (final var gav : contentByGav.keySet()) {
            out.put(gav, resp == null ? List.of() : resp);
        }
        synchronized (entryPointBatches) {
            entryPointBatches.add(new LinkedHashMap<>(contentByGav));
        }
        return out;
    }

    @Override
    public List<Gav> suggestEntryGavs(final List<Gav> candidates) {
        final var resp = suggestResponse.get();
        return resp == null ? List.of() : resp;
    }

    @Override
    public String synthesizeSkill(
            final Gav gav, final String name, final String content, final List<String> entryPoints) {
        final var resp = skillResponse.get();
        if (resp != null) {
            synthesizeCalls.incrementAndGet();
            return resp;
        }
        synthesizeCalls.incrementAndGet();
        final var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append('\n');
        sb.append("description: ")
                .append(name)
                .append(" - generated skill for ")
                .append(gav.coordinate())
                .append(".\n");
        sb.append("---\n\n");
        sb.append("# ").append(name).append('\n');
        if (!entryPoints.isEmpty()) {
            sb.append("\n## Entry points\n");
            for (final var ep : entryPoints) {
                sb.append("- `").append(ep).append("`\n");
            }
        }
        return sb.toString();
    }
}
