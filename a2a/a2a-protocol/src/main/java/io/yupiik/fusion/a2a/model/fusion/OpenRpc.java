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
package io.yupiik.fusion.a2a.model.fusion;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The subset of the OpenRPC document the Fusion annotation processor generates in
 * {@code META-INF/fusion/jsonrpc/openrpc.json} needed to describe an A2A skill.
 * <p>
 * It is intentionally partial: a skill descriptor only needs the method name and its documentation, the parameter
 * schemas are read to document the structured arguments a skill accepts.
 * <p>
 * NOTE: unlike the rest of {@link io.yupiik.fusion.a2a.model} this is an <em>input</em> model - it reads what Fusion
 * wrote - and it is never published in a schema. It is documented like the others anyway, so a reader does not have to
 * wonder whether the omission means something.
 *
 * @param schemas the shared schemas, by name.
 * @param methods the described JSON-RPC methods, by name.
 */
@JsonModel
public record OpenRpc(
        @Property(documentation = "The shared schemas, by name.")
        Map<String, JsonSchema> schemas,

        @Property(documentation = "The described JSON-RPC methods, by name.")
        Map<String, JsonRpcMethod> methods) {
    /**
     * @param name        the JSON-RPC method name, which is also the skill identifier.
     * @param description the {@code @JsonRpc} documentation, i.e. the skill description.
     * @param params      the method parameters.
     * @param result      the method result.
     */
    @JsonModel
    public record JsonRpcMethod(
            @Property(documentation = "The JSON-RPC method name, which is also the skill identifier.")
            String name,

            @Property(documentation = "The @JsonRpc documentation, i.e. the skill description.")
            String description,

            @Property(documentation = "The method parameters.")
            List<Parameter> params,

            @Property(documentation = "The method result.") Result result) {
        /**
         * @param name        the parameter name.
         * @param description the {@code @JsonRpcParam} documentation, note that Fusion sets it on the parameter and
         *                    not on its schema.
         * @param schema      the parameter schema.
         * @param required    whether the parameter is mandatory.
         */
        @JsonModel
        public record Parameter(
                @Property(documentation = "The parameter name.")
                String name,

                @Property(
                        documentation =
                                "The @JsonRpcParam documentation, note that Fusion sets it on the parameter and not on its schema.")
                String description,

                @Property(documentation = "The parameter schema.")
                JsonSchema schema,

                @Property(documentation = "Whether the parameter is mandatory.")
                Boolean required) {}
    }

    /**
     * @param schema the schema of the value a method answers.
     */
    @JsonModel
    public record Result(
            @Property(documentation = "The schema of the value a method answers.")
            JsonSchema schema) {}

    /**
     * @param ref                  a reference to a shared schema, {@code #/schemas/...}.
     * @param id                   the schema identifier.
     * @param type                 the JSON type: {@code object}, {@code array}, {@code string}, ...
     * @param nullable             whether the value may be absent, which is how Fusion expresses optionality.
     * @param description          the human oriented description.
     * @param format               the type refinement, {@code int32} for example.
     * @param pattern              the regular expression a string must match.
     * @param properties           the members, for an object.
     * @param additionalProperties either a boolean or a nested schema, for a map.
     * @param items                the item schema, for an array.
     * @param enumeration          the closed set of allowed values.
     */
    @JsonModel
    public record JsonSchema(
            @JsonProperty("$ref") @Property(documentation = "A reference to a shared schema, #/schemas/...")
            String ref,

            @JsonProperty("$id") @Property(documentation = "The schema identifier.")
            String id,

            @Property(documentation = "The JSON type: object, array, string, ...")
            String type,

            @Property(documentation = "Whether the value may be absent, which is how Fusion expresses optionality.")
            Boolean nullable,

            @Property(documentation = "The human oriented description.")
            String description,

            @Property(documentation = "The type refinement, int32 for example.")
            String format,

            @Property(documentation = "The regular expression a string must match.")
            String pattern,

            @Property(documentation = "The members, for an object.")
            Map<String, JsonSchema> properties,

            @Property(documentation = "Either a boolean or a nested schema, for a map.")
            Object additionalProperties,

            @Property(documentation = "The item schema, for an array.")
            JsonSchema items,

            @JsonProperty("enum") @Property(documentation = "The closed set of allowed values.")
            List<String> enumeration) {}
}
