/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.web;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/** Class-stable source for injected web test configurations, without application startup. */
public class WebConfigurationTestModule implements FusionModule {
    private final Map<String, String> values;

    public WebConfigurationTestModule() {
        this(Map.of());
    }

    protected WebConfigurationTestModule(final Map<String, String> overrides) {
        final var configured = new HashMap<String, String>();
        configured.put("yca.provider.providers.length", "1");
        configured.put("yca.provider.providers.0.id", "default");
        configured.put("yca.provider.providers.0.baseUrl", "http://localhost:0");
        configured.put("yca.provider.providers.0.apiKey", "test-key");
        configured.put("yca.provider.providers.0.model", "test-model");
        configured.put("yca.provider.providers.0.api", "auto");
        configured.put("yca.provider.providers.0.contextWindow", "128000");
        configured.put("yca.provider.providers.0.timeoutSeconds", "5");
        configured.put("yca.llm.retriesMaxAttempts", "1");
        configured.put("yca.llm.retriesBaseDelayMs", "1");
        configured.put("yca.llm.rateLimitRequestsPerMinute", "0");
        configured.put("yca.llm.maxConcurrent", "1");
        configured.put("yca.llm.retryOnContextOverflow", "true");
        configured.put("yca.llm.maxIterations", "250");
        configured.putAll(overrides);
        values = Map.copyOf(configured);
    }

    @Override
    public Stream<FusionBean<?>> beans() {
        return Stream.of(new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, () -> values::get));
    }

    @Override
    public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
        return (container, bean) -> !(bean.type() instanceof Class<?> type)
                || !ConfigurationSource.class.isAssignableFrom(type)
                || bean instanceof ProvidedInstanceBean<?>;
    }

    @Override
    public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
        return (container, listener) -> false;
    }
}
