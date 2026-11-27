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
package io.yupiik.fusion.a2a.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.Map;

/**
 * Tells the client a task produced - or extended - an artifact.
 * <p>
 * Streaming a long output is done by sending several events sharing the same {@link Artifact#artifactId()} with
 * {@code append=true}, the last one carrying {@code lastChunk=true}.
 *
 * @param taskId    the task which produced the artifact.
 * @param contextId the conversation the task belongs to.
 * @param artifact  the artifact, or the chunk to append to the one with the same identifier.
 * @param append    when {@code true} the parts must be appended to the previously sent artifact.
 * @param lastChunk when {@code true} this is the final chunk of the artifact.
 * @param metadata  free form metadata about the update itself.
 */
@JsonModel
public record TaskArtifactUpdateEvent(
        @Property(documentation = "The task which produced the artifact.")
        String taskId,

        @Property(documentation = "The conversation the task belongs to.")
        String contextId,

        @Property(documentation = "The artifact, or the chunk to append to the one with the same identifier.")
        Artifact artifact,

        @Property(documentation = "When true the parts must be appended to the previously sent artifact.")
        boolean append,

        @Property(documentation = "When true this is the final chunk of the artifact.")
        boolean lastChunk,

        @Property(documentation = "Free form metadata about the update itself.")
        Map<String, Object> metadata) {}
