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
package io.yupiik.maven.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.codegraph.index.CodeGraphIndex;
import io.yupiik.maven.knowledge.config.KnowledgeConfig;
import io.yupiik.maven.knowledge.content.ContentAcquirer;
import io.yupiik.maven.knowledge.content.ContentKind;
import io.yupiik.maven.knowledge.content.LocalRepository;
import io.yupiik.maven.knowledge.test.FakeMavenRepo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentAcquirerTest {

    @TempDir
    Path tmp;

    @Test
    void prefersSourcesOverJavadoc() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "a", "1", "g/a/A.java", "package g.a; public class A { public void run(){} }")
                    .javadocJar("g", "a", "1", "index-all.html", "<html><body>A docs</body></html>")
                    .pom("g", "a", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            assertEquals(
                    ContentKind.SOURCES, acquirer.acquire(Gav.of("g", "a", "1")).kind());
        }
    }

    @Test
    void resolvesJavadocWhenNoSources() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.javadocJar("g", "b", "1", "index-all.html", "<html><body>B docs</body></html>")
                    .pom("g", "b", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var acquired = acquirer.acquire(Gav.of("g", "b", "1"));
            assertEquals(ContentKind.JAVADOC, acquired.kind());
        }
    }

    @Test
    void fetchesPomUrlWhenNoJarsButSkipsGitHostsAndNonDocUrls() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.pom("g", "c", "1", "https://docs.example.com/c/");
            repo.pom("g", "d", "1", "https://github.com/org/repo");
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            assertEquals(
                    ContentKind.DOC, acquirer.acquire(Gav.of("g", "c", "1")).kind(), "doc host should be webfetched");
            assertEquals(
                    ContentKind.NONE,
                    acquirer.acquire(Gav.of("g", "d", "1")).kind(),
                    "git url must be skipped and no jar present -> none");
        }
    }

    @Test
    void docContentIsMarkdownifiedWithoutChrome() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.pom("g", "doc", "1", "https://docs.example.com/doc/");
            final var html = """
                    <!DOCTYPE html>
                    <html><head><title>Docs</title></head><body>
                    <nav class="topbar"><a href="/x">nav link</a></nav>
                    <header id="header"><h1>Site header</h1></header>
                    <main>
                      <h1>Widget</h1>
                      <p>The <strong>Widget</strong> library exposes one entry point.</p>
                      <script>var x = 1;</script>
                    </main>
                    <footer>copyright</footer>
                    </body></html>""";
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> html, config);
            final var acquired = acquirer.acquire(Gav.of("g", "doc", "1"));
            assertEquals(ContentKind.DOC, acquired.kind());
            final var text = acquired.content();
            assertFalse(text.contains("<nav"), "nav must be dropped");
            assertFalse(text.contains("<script"), "script must be dropped");
            assertFalse(text.contains("<main"), "raw html must not reach the LLM");
            assertFalse(text.contains("nav link"), "nav content must be dropped");
            assertTrue(text.contains("Widget"), "main content must be kept");
        }
    }

    @Test
    void webfetchFailureIsOptionalAndNeverBlocks() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.pom("g", "f", "1", "https://docs.example.com/f/");
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(
                    new LocalRepository(tmp),
                    (url, timeout, bytes) -> {
                        throw new IOException("connection refused (mocked)");
                    },
                    config);
            assertEquals(
                    ContentKind.NONE,
                    acquirer.acquire(Gav.of("g", "f", "1")).kind(),
                    "a webfetch failure must fall through, not throw");
        }
    }

    @Test
    void webfetchRuntimeFailureFallsThroughToClasses() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.pom("g", "f2", "1", "https://docs.example.com/f2/").classesJar("g", "f2", "1", "g.f2.Main");
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(
                    new LocalRepository(tmp),
                    (url, timeout, bytes) -> {
                        throw new IllegalStateException("unexpected runtime failure");
                    },
                    config);
            final var acquired = acquirer.acquire(Gav.of("g", "f2", "1"));
            assertEquals(
                    ContentKind.CLASSES,
                    acquired.kind(),
                    "an unexpected webfetch runtime failure must not block, classes listing must be used");
        }
    }

    @Test
    void fallsBackToClassesListing() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.classesJar("g", "e", "1", "g.e.Main").pom("g", "e", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var acquired = acquirer.acquire(Gav.of("g", "e", "1"));
            assertEquals(ContentKind.CLASSES, acquired.kind());
            assertTrue(acquired.content().contains("Artifact API:"), "bytecode surface must be listed");
        }
    }

    @Test
    void producesCompactBinaryCodeGraphSidecar() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.classesJar("g", "cgraph", "1", "g.cgraph.Main").pom("g", "cgraph", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var bytes = acquirer.codeGraph(Gav.of("g", "cgraph", "1"));
            assertFalse(bytes.length == 0, "codegraph must be produced for a jar with classes");
            // the fake jar embeds an invalid class entry, so BytecodeAnalyzer tolerates it and yields no symbols;
            // what matters is that the payload is a valid, loadable shared index (round-trips without error)
            final var index = CodeGraphIndex.load(bytes);
            assertEquals(0, index.symbolCount(), "invalid fake class is skipped, index still loads");
        }
    }

    @Test
    void codeGraphIsEmptyWhenNoMainJar() {
        final var config = KnowledgeConfig.defaults(tmp);
        final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
        assertEquals(0, acquirer.codeGraph(Gav.of("missing", "x", "9")).length, "no jar -> no codegraph");
    }

    @Test
    void readsPluginDescriptorBeforeSources() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.pluginJar("g", "plugin-a", "1", "<plugin><name>a-plugin</name></plugin>")
                    .sourcesJar("g", "plugin-a", "1", "g/a/A.java", "package g.a; public class A {}")
                    .pom("g", "plugin-a", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var acquired = acquirer.acquire(Gav.of("g", "plugin-a", "1"));
            assertEquals(ContentKind.PLUGIN, acquired.kind(), "plugin.xml descriptor must win over sources");
            assertTrue(acquired.content().contains("<plugin>"), "content should be the plugin descriptor");
        }
    }

    @Test
    void readsExtensionDescriptorInMavenSubPath() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.extensionJar(
                            "g",
                            "ext-a",
                            "1",
                            "META-INF/maven/extension.xml",
                            "<extension><name>a-ext</name></extension>")
                    .pom("g", "ext-a", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            assertEquals(
                    ContentKind.EXTENSION,
                    acquirer.acquire(Gav.of("g", "ext-a", "1")).kind());
        }
    }

    @Test
    void readsExtensionDescriptorInRootPath() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.extensionJar("g", "ext-b", "1", "META-INF/extension.xml", "<extension><name>b-ext</name></extension>")
                    .pom("g", "ext-b", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            assertEquals(
                    ContentKind.EXTENSION,
                    acquirer.acquire(Gav.of("g", "ext-b", "1")).kind());
        }
    }

    @Test
    void fallsBackToSourcesWhenNoDescriptor() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar("g", "plain-a", "1", "g/a/A.java", "package g.a; public class A {}")
                    .pom("g", "plain-a", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            assertEquals(
                    ContentKind.SOURCES,
                    acquirer.acquire(Gav.of("g", "plain-a", "1")).kind());
        }
    }

    @Test
    void reportsNoneForAbsentArtifact() {
        final var config = KnowledgeConfig.defaults(tmp);
        final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
        assertEquals(
                ContentKind.NONE, acquirer.acquire(Gav.of("missing", "x", "9")).kind());
    }

    @Test
    void javadocExtractsOnlyClassPagesAndMembers() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.javadocJar(
                            "g",
                            "doc",
                            "1",
                            Map.of(
                                    "index-all.html", "<html>INDEX</html>",
                                    "help-doc.html", "<html>HELP</html>",
                                    "overview-summary.html", "<html>OVERVIEW</html>",
                                    "com/example/Foo.html",
                                            "<html><body><h1>Class Foo</h1>"
                                                    + "<pre class=\"type-signature\">final class com.example.Foo</pre>"
                                                    + "<div class=\"description\"><div class=\"block\">A short description of Foo.</div>"
                                                    + "</div>"
                                                    + "<pre class=\"member-signature\">public void run(String arg)</pre>"
                                                    + "<div class=\"block\">Runs the thing.</div>"
                                                    + "</body></html>"))
                    .pom("g", "doc", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var acquired = acquirer.acquire(Gav.of("g", "doc", "1"));
            assertEquals(ContentKind.JAVADOC, acquired.kind());
            final var content = acquired.content();
            assertTrue(content.contains("Class Foo"), "class name must be present");
            assertTrue(content.contains("public void run(String arg)"), "member signature must be extracted");
            assertFalse(content.contains("INDEX"), "index-all.html must be pruned (chrome only)");
            assertFalse(content.contains("HELP"), "help-doc.html must be pruned (chrome only)");
            assertFalse(content.contains("OVERVIEW"), "summary/overview pages must be pruned");
        }
    }

    @Test
    void prunesGeneratedAndModuleInfoSources() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar(
                            "g",
                            "p",
                            "1",
                            Map.of(
                                    "g/p/A.java", "package g.p; public class A {}",
                                    "g/p/module-info.java", "module g.p {}",
                                    "g/p/package-info.java", "package g.p;",
                                    "g/p/generated/B.java", "package g.p.generated; public class B {}",
                                    "g/p/srcgen/C.java", "package g.p.srcgen; public class C {}"))
                    .pom("g", "p", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var acquired = acquirer.acquire(Gav.of("g", "p", "1"));
            assertEquals(ContentKind.SOURCES, acquired.kind());
            final var content = acquired.content();
            assertTrue(content.contains("class A"), "regular source must be kept");
            assertFalse(content.contains("module-info"), "module-info must be pruned");
            assertFalse(content.contains("package-info"), "package-info must be pruned");
            assertFalse(content.contains("class B"), "generated source must be pruned");
            assertFalse(content.contains("class C"), "srcgen source must be pruned");
        }
    }

    @Test
    void dedupesIdenticalSourceContent() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            final var body = "package g.d; public class Duplicate {}";
            repo.sourcesJar(
                            "g",
                            "d",
                            "1",
                            Map.of(
                                    "g/d/Foo.java", body,
                                    "g/d/Bar.java", body))
                    .pom("g", "d", "1", null);
            final var config = KnowledgeConfig.defaults(tmp);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var acquired = acquirer.acquire(Gav.of("g", "d", "1"));
            assertEquals(ContentKind.SOURCES, acquired.kind());
            final var content = acquired.content();
            final var occurrences =
                    (content.length() - content.replace("Duplicate", "").length()) / "Duplicate".length();
            assertEquals(1, occurrences, "identical file content must be deduplicated to a single occurrence");
        }
    }

    @Test
    void limitsSourceContentToContentBudget() {
        try (final var repo = new FakeMavenRepo(tmp)) {
            repo.sourcesJar(
                            "g",
                            "b",
                            "1",
                            Map.of(
                                    "g/b/A.java", "package g.b; public class A {" + "x".repeat(10_000) + "}",
                                    "g/b/B.java", "package g.b; public class B {" + "y".repeat(10_000) + "}"))
                    .pom("g", "b", "1", null);
            final var config = new KnowledgeConfig(
                    tmp, 1, 8, true, false, KnowledgeConfig.BoundedFetch.of(1, 30_000L, 65536), 0, null, 12_000, 0);
            final var acquirer = new ContentAcquirer(new LocalRepository(tmp), (url, timeout, bytes) -> "DOC", config);
            final var acquired = acquirer.acquire(Gav.of("g", "b", "1"));
            assertEquals(ContentKind.SOURCES, acquired.kind());
            assertTrue(
                    acquired.content().length() <= 13_000,
                    "per-artifact content must be capped by the content budget (got "
                            + acquired.content().length() + ")");
        }
    }
}
