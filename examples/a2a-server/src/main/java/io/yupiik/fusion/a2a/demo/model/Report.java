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
package io.yupiik.fusion.a2a.demo.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * What {@code demo/report} produces, to show a skill returning a plain model: it ends up as the structured content of
 * an artifact.
 *
 * @param city        the city the report is about.
 * @param weather     the weather in one word.
 * @param temperature the temperature, in degrees Celsius.
 */
@JsonModel
public record Report(
        @Property(documentation = "The city the report is about.")
        String city,

        @Property(documentation = "The weather in one word.")
        String weather,

        @Property(documentation = "The temperature, in degrees Celsius.")
        int temperature) {}
