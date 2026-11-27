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
 * A typed, directed edge between two symbols' fully-qualified names.
 *
 * @param from source FQN
 * @param to target FQN
 * @param type ordinal of the producer-specific edge type (calls/imports/extends/implements/uses, mapped by producer)
 */
public record IndexEdge(String from, String to, int type) {}
