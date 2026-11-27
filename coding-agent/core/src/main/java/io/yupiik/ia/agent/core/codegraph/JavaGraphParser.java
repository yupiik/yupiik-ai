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

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SimpleTreeVisitor;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

@ApplicationScoped
public class JavaGraphParser implements GraphParser {
    private volatile boolean availabilityResolved;
    private volatile boolean compilerAvailable;

    @Override
    public Set<String> extensions() {
        return Set.of("java");
    }

    @Override
    public boolean jdkRequired() {
        return true;
    }

    @Override
    public boolean available() {
        if (!availabilityResolved) {
            compilerAvailable = System.getProperty("org.graalvm.nativeimage.imagecode") == null
                    && ToolProvider.getSystemJavaCompiler() != null;
            availabilityResolved = true;
        }
        return compilerAvailable;
    }

    @Override
    public GraphModel parse(final Path file) {
        return parseBatch(List.of(file)).getOrDefault(file, GraphModel.empty("java"));
    }

    /**
     * Parses multiple Java files in a single {@link JavacTask} for better performance.
     * Returns a map from file path to its {@link GraphModel}.
     */
    public Map<Path, GraphModel> parseBatch(final List<Path> files) {
        final var result = new HashMap<Path, GraphModel>();
        if (files.isEmpty()) {
            return result;
        }
        final var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            for (final var file : files) {
                result.put(file, GraphModel.empty("java"));
            }
            return result;
        }
        try (final StandardJavaFileManager fm =
                compiler.getStandardFileManager(new DiagnosticCollector<>(), null, null)) {
            final var fileObjects = new ArrayList<JavaFileObject>(files.size());
            for (final var file : files) {
                for (final var fo : fm.getJavaFileObjects(file.toFile())) {
                    fileObjects.add(fo);
                }
            }
            final JavacTask task = (JavacTask)
                    compiler.getTask(null, fm, new DiagnosticCollector<>(), List.of("-proc:none"), null, fileObjects);
            final var units = task.parse();
            final var trees = Trees.instance(task);
            for (final var unit : units) {
                final var positions = trees.getSourcePositions();
                final var collector = new Collector(unit, positions);
                unit.accept(collector, null);
                final var path = Path.of(unit.getSourceFile().getName());
                result.put(
                        path,
                        new GraphModel(
                                "java",
                                collector.nodes,
                                collector.edges.stream().distinct().toList()));
            }
            return result;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Collector extends TreeScanner<Void, Void> {
        private static final Set<String> PRIMITIVES =
                Set.of("void", "boolean", "byte", "char", "short", "int", "long", "float", "double");
        private static final Set<String> JAVA_LANG_TYPES = Set.of(
                "String",
                "Integer",
                "Long",
                "Double",
                "Float",
                "Boolean",
                "Byte",
                "Short",
                "Character",
                "Object",
                "Class",
                "Void",
                "Enum",
                "Record",
                "Exception",
                "RuntimeException",
                "Throwable",
                "Math",
                "System",
                "StringBuilder",
                "StringBuffer",
                "Iterable",
                "Comparable",
                "CharSequence");
        private final List<GraphModel.Symbol> nodes = new ArrayList<>();
        private final List<GraphModel.Edge> edges = new ArrayList<>();
        private final String fileName;
        private final String packageName;
        private final LineMap lineMap;
        private final SourcePositions positions;
        private final CompilationUnitTree unit;
        private final Map<String, String> importedTypes = new HashMap<>();
        private final Deque<String> ownerStack = new ArrayDeque<>();

        private Collector(final CompilationUnitTree unit, final SourcePositions positions) {
            this.fileName = unit.getSourceFile().getName();
            this.packageName = pkg(unit);
            this.lineMap = unit.getLineMap();
            this.positions = positions;
            this.unit = unit;
        }

        private String pkg(final CompilationUnitTree unit) {
            if (unit.getPackageName() != null) {
                return unit.getPackageName().toString();
            }
            final var out = new String[1];
            for (final var t : unit.getTypeDecls()) {
                final var p = t.accept(
                        new SimpleTreeVisitor<Object, Object>() {
                            @Override
                            public Object visitPackage(final PackageTree node, final Object o) {
                                out[0] = node.getPackageName() == null
                                        ? ""
                                        : node.getPackageName().toString();
                                return out[0];
                            }
                        },
                        null);
                if (p != null) {
                    out[0] = p.toString();
                    break;
                }
            }
            return out[0] == null ? "" : out[0];
        }

        private String fqn(final String packageName, final String name) {
            return packageName == null || packageName.isEmpty() ? name : packageName + "." + name;
        }

        private String join(final String parent, final String name) {
            return parent == null || parent.isEmpty() ? name : parent + "." + name;
        }

        private String first(final String name) {
            final var dot = name.indexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }

        private String last(final String name) {
            final var dot = name.lastIndexOf('.');
            return dot >= 0 ? name.substring(dot + 1) : name;
        }

        private int[] startEnd(final Tree tree) {
            if (tree == null || lineMap == null || positions == null) {
                return new int[] {0, 0};
            }
            final var start = positions.getStartPosition(unit, tree);
            final var end = positions.getEndPosition(unit, tree);
            final var startLine = start < 0 ? 0 : (int) lineMap.getLineNumber(start);
            final var endLine = end < 0 ? startLine : (int) lineMap.getLineNumber(end);
            return new int[] {startLine, endLine};
        }

        @Override
        public Void visitImport(final ImportTree node, final Void unused) {
            final var name = node.getQualifiedIdentifier() == null
                    ? ""
                    : node.getQualifiedIdentifier().toString();
            if (node.isStatic()) {
                final var idx = name.lastIndexOf('.');
                if (idx > 0) {
                    importedTypes.putIfAbsent(name.substring(idx + 1), name.substring(0, idx));
                }
            } else {
                final var idx = name.lastIndexOf('.');
                importedTypes.putIfAbsent(idx > 0 ? name.substring(idx + 1) : name, name);
            }
            if (!node.isStatic() && !name.endsWith(".*")) {
                edges.add(new GraphModel.Edge(fqn(packageName, last(name)), name, GraphModel.EdgeType.IMPORTS));
            }
            return super.visitImport(node, unused);
        }

        @Override
        public Void visitClass(final ClassTree node, final Void unused) {
            final var name =
                    node.getSimpleName() == null ? "" : node.getSimpleName().toString();
            final var owner = join(currentOwner(), name);
            ownerStack.push(owner);
            final var kind = node.getKind() == Tree.Kind.INTERFACE
                    ? GraphModel.Kind.INTERFACE
                    : node.getKind() == Tree.Kind.ANNOTATION_TYPE
                            ? GraphModel.Kind.ANNOTATION
                            : node.getKind() == Tree.Kind.ENUM ? GraphModel.Kind.ENUM : GraphModel.Kind.TYPE;
            final var bounds = startEnd(node);
            nodes.add(new GraphModel.Symbol(owner, kind, fileName, bounds[0], bounds[1], signature(node)));
            if (node.getExtendsClause() != null) {
                relationship(owner, node.getExtendsClause(), GraphModel.EdgeType.EXTENDS);
            }
            if (node.getImplementsClause() != null) {
                for (final var iface : node.getImplementsClause()) {
                    relationship(owner, iface, GraphModel.EdgeType.IMPLEMENTS);
                }
            }
            for (final var member : node.getMembers()) {
                scan(member, unused);
            }
            ownerStack.pop();
            return null;
        }

        @Override
        public Void visitMethod(final MethodTree node, final Void unused) {
            final var methodName = node.getName().toString();
            final var owner = join(currentOwner(), methodName);
            if (!"<init>".equals(methodName) && !"<clinit>".equals(methodName)) {
                final var bounds = startEnd(node);
                nodes.add(new GraphModel.Symbol(
                        owner, GraphModel.Kind.METHOD, fileName, bounds[0], bounds[1], signature(node)));
            }
            ownerStack.push(owner);
            for (final var param : node.getParameters()) {
                scan(param, unused);
            }
            if (node.getBody() != null) {
                scan(node.getBody(), unused);
            }
            ownerStack.pop();
            return null;
        }

        @Override
        public Void visitVariable(final VariableTree node, final Void unused) {
            // Field, parameter and local variable types are only receiver-typing hints, never edges
            // (graft's collectBindings contract) - otherwise it would leak primitives and java.lang
            // types as junk USES edges. The initializer may still contain calls and constructions.
            if (node.getInitializer() != null) {
                scan(node.getInitializer(), unused);
            }
            return null;
        }

        @Override
        public Void visitMethodInvocation(final MethodInvocationTree node, final Void unused) {
            final var called = methodName(node.getMethodSelect());
            final var owner = resolveOwner(node.getMethodSelect());
            if (called != null) {
                edges.add(new GraphModel.Edge(
                        currentOwner(), owner == null ? called : owner + "." + called, GraphModel.EdgeType.CALLS));
            }
            scan(node.getArguments(), unused);
            return null;
        }

        @Override
        public Void visitNewClass(final NewClassTree node, final Void unused) {
            final var type =
                    node.getIdentifier() == null ? "" : node.getIdentifier().toString();
            final var resolved = resolveConstructed(type);
            if (resolved != null) {
                edges.add(new GraphModel.Edge(currentOwner(), resolved, GraphModel.EdgeType.CALLS));
            }
            scan(node.getArguments(), unused);
            return null;
        }

        private void relationship(final String owner, final Tree targetType, final GraphModel.EdgeType type) {
            final var text = targetType == null ? "" : targetType.toString();
            if (text.isBlank()) {
                return;
            }
            final var resolved = resolve(text);
            // An unresolved base is usually an external/imported type - keep the raw name as the
            // target (graft's 'external type' contract) instead of dropping it.
            edges.add(new GraphModel.Edge(owner, resolved == null ? text : resolved, type));
        }

        private String resolveConstructed(final String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            if (name.indexOf('.') >= 0) {
                // Qualified construction (new java.io.File(), new demo.Foo()) is dropped: resolving it
                // to a scoped name risks false-matching an unrelated in-repo type (graft drops these).
                return null;
            }
            if (isNonSymbol(name)) {
                // Primitives and java.lang wrappers (new String(), new Integer()) are never project symbols.
                return null;
            }
            if (importedTypes.containsKey(name)) {
                // new Foo() where Foo is only an off-classpath import -> drop (the import edge already
                // records the dependency, matching graft).
                return null;
            }
            return fqn(packageName, name);
        }

        private String resolve(final String name) {
            if (name.indexOf('.') >= 0) {
                final var simpleFirst = first(name);
                if (isNonSymbol(simpleFirst)) {
                    return null;
                }
                final var imported = importedTypes.get(simpleFirst);
                if (imported != null) {
                    return imported + name.substring(simpleFirst.length());
                }
                if (last(name).isEmpty() || Character.isLowerCase(last(name).charAt(0))) {
                    return null;
                }
                return name;
            }
            if (isNonSymbol(name)) {
                return null;
            }
            final var imported = importedTypes.get(name);
            if (imported != null) {
                return imported;
            }
            return fqn(packageName, name);
        }

        private static boolean isNonSymbol(final String simpleName) {
            return PRIMITIVES.contains(simpleName) || JAVA_LANG_TYPES.contains(simpleName);
        }

        private String resolveOwner(final Tree select) {
            if (select instanceof final MemberSelectTree m) {
                final var expr = m.getExpression();
                if (expr instanceof final NewClassTree n) {
                    // `new demo.Foo().bar()` - the member call owner is the constructed type.
                    final var constructed =
                            n.getIdentifier() == null ? "" : n.getIdentifier().toString();
                    return resolve(constructed);
                }
                return expr == null ? null : resolve(expr.toString());
            }
            return null;
        }

        private String methodName(final Tree select) {
            if (select instanceof final MemberSelectTree m) {
                return m.getIdentifier() == null ? null : m.getIdentifier().toString();
            }
            if (select instanceof final IdentifierTree i) {
                return i.getName() == null ? null : i.getName().toString();
            }
            return null;
        }

        private String signature(final Tree tree) {
            return tree == null
                    ? ""
                    : tree.toString().lines().findFirst().orElse("").trim();
        }

        private String currentOwner() {
            return ownerStack.isEmpty() ? (packageName == null ? "" : packageName) : ownerStack.peek();
        }
    }
}
