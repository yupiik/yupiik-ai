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
package io.yupiik.maven.knowledge.bytecode;

import java.util.List;

/**
 * Structural, public-facing information about a single class/interface/record/enum read from bytecode via
 * the JDK Class-File API. Purely derived from the compiled artifacts - no decompilation needed.
 *
 * @param className   binary name (FQN, dots), e.g. {@code com.example.Foo}.
 * @param modifiers   a descriptive modifier word (or empty): {@code public}, {@code abstract}, etc.
 * @param kind        {@code class}/{@code interface}/{@code record}/{@code enum}/{@code annotation}/{@code interface}.
 * @param superclass  binary name of the direct superclass, or {@code null} ({@code java.lang.Object}).
 * @param interfaces  binary names of directly implemented/extends interfaces.
 * @param annotations binary names of class-level annotations ({@code java.lang.Override} style).
 * @param methods     the public/protected method signatures.
 */
public record ClassApi(
        String className,
        String modifiers,
        String kind,
        String superclass,
        List<String> interfaces,
        List<String> annotations,
        List<MethodApi> methods) {

    public static ClassApi of(
            final String className,
            final String modifiers,
            final String kind,
            final String superclass,
            final List<String> interfaces,
            final List<String> annotations,
            final List<MethodApi> methods) {
        return new ClassApi(
                className,
                modifiers,
                kind,
                superclass,
                interfaces == null ? List.of() : List.copyOf(interfaces),
                annotations == null ? List.of() : List.copyOf(annotations),
                methods == null ? List.of() : List.copyOf(methods));
    }
}
