/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.acp;

import java.util.HashMap;
import java.util.Map;

/** Provider fixtures shared by the session option protocol tests. */
public class OptionConfigurationTestModule extends AcpConfigurationTestModule {
    public OptionConfigurationTestModule() {
        this(Map.of());
    }

    protected OptionConfigurationTestModule(final Map<String, String> overrides) {
        super(values(overrides));
    }

    private static Map<String, String> values(final Map<String, String> overrides) {
        final var props = new HashMap<String, String>();
        props.put("yca.provider.providers.length", "2");
        props.put("yca.provider.defaultProvider", "a");
        for (int i = 0; i < 2; i++) {
            final var prefix = "yca.provider.providers." + i;
            props.put(prefix + ".id", i == 0 ? "a" : "default");
            props.put(prefix + ".model", i == 0 ? "a-main" : "b-main");
            props.put(prefix + ".tuning.variants.length", "2");
            props.put(prefix + ".tuning.variants.0.name", "low");
            props.put(prefix + ".tuning.variants.0.reasoningEffort", "low");
            props.put(prefix + ".tuning.variants.1.name", "high");
            props.put(prefix + ".tuning.variants.1.reasoningEffort", "high");
            props.put(prefix + ".tuning.defaultVariant", "high");
            props.put(prefix + ".models.length", "1");
            props.put(prefix + ".models.0.model", "matching-prefix");
        }
        props.put("yca.model.compact.name", "$a/compact-only");
        props.put("yca.model.memory.name", "$a/memory-only");
        props.put("yca.sandbox.enabled", "false");
        props.putAll(overrides);
        return props;
    }
}
