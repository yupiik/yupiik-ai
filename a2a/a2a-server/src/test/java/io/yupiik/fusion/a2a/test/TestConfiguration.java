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
package io.yupiik.fusion.a2a.test;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

/**
 * The agent the tests assert on. The HTTP server port and host come from the surefire configuration.
 */
@DefaultScoped
public class TestConfiguration implements ConfigurationSource {
    @Override
    public String get(final String key) {
        return switch (key) {
            case "fusion.a2a.name" -> "test-agent";
            case "fusion.a2a.description" -> "An agent used by the tests.";
            // a stable, absolute url so the card assertions do not depend on the random port
            case "fusion.a2a.publicUrl" -> "https://agent.example.com";
            // keep the tests fast: a task which never settles must not hold the assertion for minutes
            case "fusion.a2a.taskTimeout" -> "5";
            default -> null;
        };
    }
}
