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
package io.yupiik.ia.agent.core.llm;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.mapper.DelegatingMapper;
import io.yupiik.fusion.json.serialization.JsonCodec;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/** Standalone JSON mappers for explicitly owned operations outside an application container. */
public final class JsonMappers {
    private JsonMappers() {}

    /** Closing the returned mapper also closes its isolated codec container. */
    public static JsonMapper create(final Configuration configuration) {
        return forModels(configuration);
    }

    /**
     * Limits discovery to the modules owning the given models and their explicitly listed nested models.
     * An empty list discovers all codecs. This keeps short-lived helper processes lightweight.
     */
    public static JsonMapper forModels(final Configuration configuration, final Class<?>... models) {
        final var selected = List.of(models);
        final var container = ConfiguringContainer.of()
                .disableAutoDiscovery(true)
                .register(new ProvidedInstanceBean<>(DefaultScoped.class, Configuration.class, () -> configuration))
                .register(new FusionModule() {
                    @Override
                    public Stream<FusionBean<?>> beans() {
                        return ServiceLoader.load(FusionModule.class).stream()
                                .filter(provider -> selected.isEmpty()
                                        || provider.type()
                                                .getPackageName()
                                                .startsWith(JsonMapper.class.getPackageName())
                                        || selected.stream()
                                                .anyMatch(model -> model.getPackageName()
                                                                .equals(provider.type()
                                                                        .getPackageName())
                                                        || model.getPackageName()
                                                                .startsWith(provider.type()
                                                                                .getPackageName() + ".")))
                                .flatMap(provider -> provider.get().beans());
                    }

                    @Override
                    public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                        return (container, bean) -> {
                            final var raw = bean.type() instanceof ParameterizedType parameterized
                                    ? parameterized.getRawType()
                                    : bean.type();
                            if (!(raw instanceof Class<?> type)) {
                                return false;
                            }
                            return JsonMapper.class.isAssignableFrom(type)
                                    || JsonCodec.class.isAssignableFrom(type)
                                            && (selected.isEmpty()
                                                    || bean.type() instanceof ParameterizedType parameterized
                                                            && selected.contains(parameterized
                                                                    .getActualTypeArguments()[0]));
                        };
                    }

                    @Override
                    public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                        return (container, listener) -> false;
                    }
                })
                .start();
        try {
            final var instance = container.lookup(JsonMapper.class);
            return new DelegatingMapper(instance.instance()) {
                @Override
                public void close() {
                    try {
                        instance.close();
                    } finally {
                        container.close();
                    }
                }
            };
        } catch (final RuntimeException | Error failure) {
            container.close();
            throw failure;
        }
    }
}
