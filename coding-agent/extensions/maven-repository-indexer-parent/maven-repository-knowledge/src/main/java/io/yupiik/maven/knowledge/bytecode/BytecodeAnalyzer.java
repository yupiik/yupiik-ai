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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the public API surface of a dependency jar straight from its bytecode using the JDK Class-File API
 * ({@code java.lang.classfile}, part of {@code java.base} since JDK 24). No ASM, no decompiler, no extra
 * dependency: the produced {@link ClassApi} model feeds both deterministic entry-point discovery and the
 * generated knowledge reference for jars with no {@code -sources}/{\code -javadoc}/doc.
 *
 * <p>Multi-release ({@code META-INF/versions/}) entries are skipped so the root {@code .class} set is what is
 * really exposed on the default classpath; inner classes and {@code module-info} are skipped.</p>
 */
public final class BytecodeAnalyzer {

    private final ClassFile classFile;

    public BytecodeAnalyzer() {
        this.classFile = ClassFile.of();
    }

    /**
     * Deterministic entry-point candidates: top-level public classes whose nature suggests they are how a
     * consumer "enters" the artifact. Ordered by heuristics score then name.
     *
     * @param jarPath the artifact main jar.
     * @return fully-qualified class names, never empty (bare-jar fallback still yields all public classes).
     */
    public List<String> entryPoints(final Path jarPath) {
        final var scored = new ArrayList<Scored>();
        for (final var api : analyze(jarPath)) {
            if ((api.kind().equals("class")
                            || api.kind().equals("abstract class")
                            || api.kind().equals("interface"))
                    && !api.className().contains("$")
                    && !isServiceBookkeeping(api.className())) {
                final var score = entryPointScore(api);
                if (score > 0) {
                    scored.add(new Scored(api.className(), score));
                }
            }
        }
        scored.sort((a, b) -> {
            final var byScore = Integer.compare(b.score, a.score);
            return byScore != 0 ? byScore : a.fqn.compareTo(b.fqn);
        });
        return scored.stream().map(s -> s.fqn).toList();
    }

    /**
     * Full public API model of every top-level class in the jar, ordered by class name.
     */
    public List<ClassApi> analyze(final Path jarPath) {
        final var result = new ArrayList<ClassApi>();
        try (final var zip = new ZipFile(jarPath.toFile())) {
            final var entries = zip.stream()
                    .filter(BytecodeAnalyzer::isWorthParsing)
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (final var entry : entries) {
                final var clazz = parse(zip, entry);
                if (clazz != null) {
                    result.add(clazz);
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private ClassApi parse(final ZipFile zip, final ZipEntry entry) {
        try (final var in = zip.getInputStream(entry)) {
            final ClassModel model;
            try {
                model = classFile.parse(in.readAllBytes());
            } catch (final RuntimeException e) {
                return null; // hand-rolled/invalid class file, irrelevant for docs
            }
            final var className = model.thisClass().asInternalName().replace('/', '.');
            if (className.contains("$") || className.equals("module-info") || className.equals("package-info")) {
                return null;
            }
            final var flags = model.flags().flagsMask();
            final boolean iface = (flags & ClassFile.ACC_INTERFACE) != 0;
            final boolean isAnnotation = iface && (flags & ClassFile.ACC_ANNOTATION) != 0;
            final boolean isEnum = (flags & ClassFile.ACC_ENUM) != 0;
            // no ACC_RECORD bit exists; a record is a final class whose superclass is java.lang.Record
            final boolean isRecord = !iface
                    && model.superclass()
                            .map(c -> c.asInternalName().equals("java/lang/Record"))
                            .orElse(false);
            final String kind;
            if (isAnnotation) {
                kind = "annotation";
            } else if (iface) {
                kind = "interface";
            } else if (isRecord) {
                kind = "record";
            } else if (isEnum) {
                kind = "enum";
            } else if ((flags & ClassFile.ACC_ABSTRACT) != 0) {
                kind = "abstract class";
            } else {
                kind = "class";
            }
            final var modifiers = modifiers(flags, kind);
            final var superclass = model.superclass()
                    .map(c -> c.asInternalName().replace('/', '.'))
                    .orElse(null);
            final var interfaces = model.interfaces().stream()
                    .map(c -> c.asInternalName().replace('/', '.'))
                    .toList();
            final var annotations = new ArrayList<String>();
            try {
                model.elementStream()
                        .filter(Annotation.class::isInstance)
                        .map(Annotation.class::cast)
                        .forEach(a -> {
                            final var n = a.className().stringValue().replace('/', '.');
                            if (!n.startsWith("java.lang.annotation.")
                                    && !n.equals("java.lang.Deprecated")
                                    && !n.equals("java.lang.SuppressWarnings")
                                    && !n.equals("kotlin.Metadata")) {
                                annotations.add(n);
                            }
                        });
            } catch (final RuntimeException e) {
                // annotation model drift is non-fatal for docs
            }
            final var methods = new ArrayList<MethodApi>();
            for (final var method : model.methods()) {
                final var api = methodApi(method);
                if (api != null) {
                    methods.add(api);
                }
            }
            return ClassApi.of(className, modifiers, kind, superclass, interfaces, annotations, methods);
        } catch (final IOException e) {
            return null;
        }
    }

    private MethodApi methodApi(final MethodModel method) {
        final var flags = method.flags().flagsMask();
        // keep only public/protected surface (skip synthetic, bridge, private). keep constructors + <clinit> excluded
        final boolean constructor = method.methodName().stringValue().equals("<init>");
        final boolean clinit = method.methodName().stringValue().equals("<clinit>");
        if (clinit || ((flags & (ClassFile.ACC_PRIVATE | ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0)) {
            return null;
        }
        final boolean doc = (flags & (ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED)) != 0;
        if (!doc && !constructor) {
            return null;
        }
        final var type = method.methodTypeSymbol();
        final var ret = type.returnType().displayName();
        final var params = new ArrayList<String>();
        for (final var p : type.parameterList()) {
            params.add(display(p));
        }
        return new MethodApi(method.methodName().stringValue(), ret, params, methodModifiers(flags), constructor);
    }

    private static String display(final ClassDesc desc) {
        return desc.displayName();
    }

    private static String modifiers(final int flags, final String kind) {
        final var sb = new StringBuilder();
        appendFlag(sb, flags, ClassFile.ACC_PUBLIC, "public");
        if (kind.equals("abstract class")) {
            sb.append("abstract ");
        }
        return sb.toString().strip();
    }

    private static String methodModifiers(final int flags) {
        final var sb = new StringBuilder();
        appendFlag(sb, flags, ClassFile.ACC_PUBLIC, "public");
        appendFlag(sb, flags, ClassFile.ACC_PROTECTED, "protected");
        appendFlag(sb, flags, ClassFile.ACC_STATIC, "static");
        appendFlag(sb, flags, ClassFile.ACC_ABSTRACT, "abstract");
        appendFlag(sb, flags, ClassFile.ACC_FINAL, "final");
        appendFlag(sb, flags, ClassFile.ACC_SYNCHRONIZED, "synchronized");
        return sb.toString().strip();
    }

    private static void appendFlag(final StringBuilder sb, final int flags, final int mask, final String word) {
        if ((flags & mask) != 0) {
            sb.append(word).append(' ');
        }
    }

    private static int entryPointScore(final ClassApi api) {
        int score = 1; // any top-level public class is a candidate
        if (hasMethodNamed(api, "main")) {
            score += 100;
        }
        if (hasAnnotation(api, "Path", "Application", "SpringBootApplication", "EnableAutoConfiguration")) {
            score += 50;
        }
        if (hasAnnotation(api, "Service", "Component", "Configuration", "Repository", "Controller", "RestController")) {
            score += 30;
        }
        if (hasAnnotation(api, "Extension", "AutoConfiguration", "Provider", "Factory")) {
            score += 20;
        }
        if (api.kind().equals("interface")) {
            score += 5;
        }
        return score;
    }

    private static boolean hasMethodNamed(final ClassApi api, final String name) {
        return api.methods().stream().anyMatch(m -> !m.constructor() && m.name().equals(name));
    }

    private static boolean hasAnnotation(final ClassApi api, final String... simpleNames) {
        for (final var a : api.annotations()) {
            final var simple = a.contains(".") ? a.substring(a.lastIndexOf('.') + 1) : a;
            for (final var want : simpleNames) {
                if (want.equals(simple)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isServiceBookkeeping(final String fqn) {
        return fqn.equals("module-info") || fqn.startsWith("META-INF.");
    }

    private static boolean isWorthParsing(final ZipEntry entry) {
        final var name = entry.getName();
        if (entry.isDirectory() || !name.endsWith(".class")) {
            return false;
        }
        return !name.startsWith("META-INF/versions/") && !name.startsWith("META-INF/");
    }

    private record Scored(String fqn, int score) {}
}
