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

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import java.util.function.BiPredicate;

/** Isolated generated wire bindings with explicitly owned per-test services. */
public final class WebBindings {
    private WebBindings() {}

    public static RuntimeContainer create(final WebJsonRpc rpc, final JsonMapper mapper) {
        return ConfiguringContainer.of()
                .register(
                        new ProvidedInstanceBean<>(DefaultScoped.class, WebJsonRpc.class, () -> rpc),
                        new ProvidedInstanceBean<>(DefaultScoped.class, JsonMapper.class, () -> mapper))
                .register(new FusionModule() {
                    @Override
                    public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
                        return (container, bean) ->
                                bean.type() instanceof Class<?> type && JsonRpcMethod.class.isAssignableFrom(type);
                    }

                    @Override
                    public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
                        return (container, listener) -> false;
                    }
                })
                .start();
    }
}
