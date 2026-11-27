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
package io.yupiik.codegraph.index;

/**
 * A node in an {@link IndexGraph}: a named symbol anchored to a source location.
 *
 * @param fqn fully-qualified name
 * @param kind ordinal of the producer-specific kind (type/method/field/... enum, mapped by the producer)
 * @param file relative source file path ("" when unknown, e.g. a dependency analyzed from bytecode)
 * @param startLine 1-based start line (0 when unknown)
 * @param endLine 1-based end line (0 when unknown)
 * @param signature one-line signature ("" when unknown)
 */
public record IndexSymbol(String fqn, int kind, String file, int startLine, int endLine, String signature) {}
