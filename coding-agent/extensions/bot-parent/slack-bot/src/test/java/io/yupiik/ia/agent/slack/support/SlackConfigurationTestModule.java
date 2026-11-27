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
package io.yupiik.ia.agent.slack.support;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/** Isolates injected configuration from host settings and application startup. */
public class SlackConfigurationTestModule implements FusionModule {
    private final Map<String, String> values;

    public SlackConfigurationTestModule() {
        this(Map.of());
    }

    protected SlackConfigurationTestModule(final Map<String, String> values) {
        this.values = Map.copyOf(values);
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
