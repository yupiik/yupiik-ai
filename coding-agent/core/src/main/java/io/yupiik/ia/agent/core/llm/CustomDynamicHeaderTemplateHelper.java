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

import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.helper.SafeString;
import java.util.function.Function;

/**
 * A callable helper made available to the provider {@code dynamicHeaders} handlebars templates.
 * Each {@code @ApplicationScoped} bean implementing this interface is registered under its
 * {@link #name()}, injected after the built-in {@code exec}, {@code codexAuth},
 * {@code codexAgentAssertion} and {@code codexAccountId} helpers, and can be invoked from any
 * provider template to compute request headers that a built-in helper cannot produce (eg. a custom
 * signing flow, a lease-based token or a gateway-scoped header).
 *
 * <p>As with the built-in helpers, the returned value is rendered into the header value: a
 * {@link SafeString} is inserted raw (no HTML escaping), a plain {@link String} too; returning
 * {@code null} or an empty value skips the header. Throwing a runtime exception fails the render
 * and the request is not sent, so validation errors surface loudly.</p>
 */
public interface CustomDynamicHeaderTemplateHelper extends Function<HelperContext, Object> {
    /** The helper name usable in the template, e.g. {@code {{myCustomAuth}}}. */
    String name();
}
