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
package io.yupiik.llm.client;

import java.io.IOException;

/**
 * Thrown when a provider does not implement a dialect (HTTP 404/405/501), enabling a router to
 * react, e.g. to switch to a fallback client.
 */
public final class DialectNotSupportedException extends IOException {

    /**
     * Creates the exception for an unsupported dialect.
     *
     * @param dialect the name of the unsupported dialect.
     */
    public DialectNotSupportedException(final String dialect) {
        super(dialect + " dialect not supported by the provider");
    }
}
