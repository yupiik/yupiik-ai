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
package io.yupiik.ia.agent.web;

import static io.yupiik.fusion.framework.api.ConfiguringContainer.of;

import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.event.Emitter;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.serialization.JsonCodec;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit extension providing a {@link JsonMapper} covering the web module's generated codecs (incl.
 * {@link WebEventDto} and {@link WebReplay}'s inputs). Boots once per JVM a lightweight container
 * keeping only the JSON beans and filtering all listeners out, mirroring {@code core}'s
 * {@code TestJsonMapper} (which lives in the core test tree and is not on the web test classpath).
 */
public class TestWebJsonMapper implements ParameterResolver {
    @Override
    public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        return JsonMapper.class == parameterContext.getParameter().getType();
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) {
        return Holder.MAPPER.mapper();
    }

    private record Holder(Instance<JsonMapper> mapperInstance, RuntimeContainer container) {
        private static final Holder MAPPER = boot();

        private static final AtomicBoolean CLOSED = new AtomicBoolean();

        static {
            Runtime.getRuntime().addShutdownHook(new Thread(Holder::closeOnce, "yca-web-test-json-mapper-close"));
        }

        private JsonMapper mapper() {
            return mapperInstance.instance();
        }

        private static Holder boot() {
            final var container = of().register(new FusionModule() {
                        @Override
                        public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                            return (runtimeContainer, bean) -> isA(bean.type(), JsonCodec.class)
                                    || isA(bean.type(), JsonMapper.class)
                                    || isA(bean.type(), Configuration.class)
                                    || isA(bean.type(), Emitter.class)
                                    || isA(bean.type(), RuntimeContainer.class);
                        }

                        @Override
                        public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                            return (runtimeContainer, listener) -> false; // no listener -> nothing initializes
                        }
                    })
                    .start();
            try {
                return new Holder(container.lookup(JsonMapper.class), container);
            } catch (final RuntimeException e) {
                container.close();
                throw e;
            }
        }

        private static boolean isA(final Type type, final Class<?> expected) {
            final var clazz = type instanceof ParameterizedType parameterized
                    ? (Class<?>) parameterized.getRawType()
                    : (Class<?>) type;
            return expected.isAssignableFrom(clazz);
        }

        private static void closeOnce() {
            if (CLOSED.compareAndSet(false, true)) {
                MAPPER.mapperInstance.close();
                MAPPER.container.close();
            }
        }
    }
}
