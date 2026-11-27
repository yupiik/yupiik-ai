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
package io.yupiik.ia.agent.core.codegraph;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.List;

/**
 * Language-neutral structural description of a parsed source file. Every {@link GraphParser}
 * implementation produces this model so that the graph store, tools and enrichment pass stay
 * independent of the source language.
 */
public record GraphModel(String language, List<Symbol> symbols, List<Edge> edges) {

    public static GraphModel empty(final String language) {
        return new GraphModel(language, List.of(), List.of());
    }

    @JsonModel
    public record Symbol(String fqn, Kind kind, String file, int startLine, int endLine, String signature) {}

    @JsonModel
    public record Edge(String from, String to, EdgeType type) {}

    @JsonModel
    public enum Kind {
        TYPE,
        METHOD,
        FIELD,
        FUNCTION,
        CONSTRUCTOR,
        ENUM,
        INTERFACE,
        ANNOTATION,
        MODULE
    }

    @JsonModel
    public enum EdgeType {
        CALLS,
        IMPORTS,
        EXTENDS,
        IMPLEMENTS,
        USES
    }
}
