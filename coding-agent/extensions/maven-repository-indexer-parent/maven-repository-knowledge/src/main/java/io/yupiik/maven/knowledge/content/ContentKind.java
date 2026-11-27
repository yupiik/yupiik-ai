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
package io.yupiik.maven.knowledge.content;

/**
 * The kind of content acquired for an artifact, mirroring the fallback chain.
 */
public enum ContentKind {
    /** From a {@code -sources.jar}. */
    SOURCES,
    /** From a {@code -javadoc.jar}. */
    JAVADOC,
    /** From the pom {@code <url>} via bounded webfetch. */
    DOC,
    /** From the bytecode jar's class/index listing (entry points delegated to the LLM). */
    CLASSES,
    /** From a Maven plugin descriptor ({@code META-INF/maven/plugin.xml}); no sources/javadoc/deep scan. */
    PLUGIN,
    /** From a Maven build-extension descriptor; no sources/javadoc/deep scan. */
    EXTENSION,
    /** Nothing usable was present locally. */
    NONE
}
