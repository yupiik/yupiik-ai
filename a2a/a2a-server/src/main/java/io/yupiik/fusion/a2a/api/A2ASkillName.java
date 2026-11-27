/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.api;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import io.yupiik.fusion.framework.build.api.metadata.BeanMetadataAlias;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The human readable name of a {@link A2ASkill}, which defaults to the JSON-RPC method name:
 * <pre>{@code
 * @A2ASkill
 * @A2ASkillName("City Report Builder")
 * @JsonRpc(value = "report/build", documentation = "Builds a report about a city.")
 * public Artifact build(...) { ... }
 * }</pre>
 * The identifier stays the method name, this only changes what a catalog displays.
 * <p>
 * IMPORTANT: this is a marker, the value is stored in the bean metadata ({@code a2a.name}) at build time so it does not
 * need to be kept at runtime.
 */
@Target(METHOD)
@Retention(SOURCE)
@BeanMetadataAlias(name = "a2a.name")
public @interface A2ASkillName {
    /**
     * @return the name to display.
     */
    String value();
}
