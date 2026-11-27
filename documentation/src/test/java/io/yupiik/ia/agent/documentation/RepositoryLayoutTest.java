package io.yupiik.ia.agent.documentation;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.StreamSupport;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class RepositoryLayoutTest {
    private final Path root = Path.of("..").toAbsolutePath().normalize();

    @Test
    void allProjectModelsHaveMaven3ParentsAndHierarchicalNames() throws Exception {
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        final List<Path> poms;
        try (final var files = Files.walk(root)) {
            poms = files.filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> StreamSupport.stream(path.spliterator(), false)
                            .noneMatch(part -> List.of("target", "node_modules", ".git", ".node")
                                    .contains(part.toString())))
                    .toList();
        }
        assertTrue(poms.size() > 40);
        for (final var pom : poms) {
            final var project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
            assertEquals("4.0.0", child(project, "modelVersion").getTextContent(), pom.toString());
            assertNull(child(project, "subprojects"), pom.toString());
            final var name = child(project, "name");
            assertNotNull(name, pom + " must declare its own display name");
            final var displayName = name.getTextContent();
            assertEquals(displayName.strip(), displayName, pom.toString());
            if (pom.equals(root.resolve("pom.xml"))) {
                assertEquals("io.yupiik.ai", child(project, "groupId").getTextContent());
                assertEquals("Yupiik AI", displayName, pom.toString());
                continue;
            }
            final var parent = child(project, "parent");
            assertNotNull(parent, pom.toString());
            assertEquals("io.yupiik.ai", child(parent, "groupId").getTextContent(), pom.toString());
            assertEquals("1.0.0-SNAPSHOT", child(parent, "version").getTextContent(), pom.toString());
            final var relative = child(parent, "relativePath");
            assertNotNull(relative, pom.toString());
            final var parentFile =
                    pom.getParent().resolve(relative.getTextContent()).normalize();
            assertTrue(Files.isRegularFile(parentFile), parentFile.toString());
            final var actualParent =
                    factory.newDocumentBuilder().parse(parentFile.toFile()).getDocumentElement();
            final var namePrefix = child(actualParent, "name").getTextContent() + " :: ";
            assertTrue(displayName.startsWith(namePrefix), pom + " must extend " + namePrefix);
            final var localName = displayName.substring(namePrefix.length());
            assertFalse(localName.isBlank(), pom.toString());
            assertEquals(localName.strip(), localName, pom.toString());
            assertFalse(localName.contains(":"), pom + " must add exactly one hierarchy level");
            assertEquals(
                    child(actualParent, "artifactId").getTextContent(),
                    child(parent, "artifactId").getTextContent(),
                    pom.toString());
        }
    }

    @Test
    void reusableClientsAndProtocolsHaveNoAgentServerOrReflectiveInvocationDependency() throws Exception {
        for (final var module : List.of(
                "a2a/a2a-protocol",
                "a2a/a2a-client",
                "mcp/mcp-protocol",
                "mcp/mcp-client",
                "acp/acp-protocol",
                "acp/acp-server")) {
            final var pom = Files.readString(root.resolve(module).resolve("pom.xml"));
            for (final var forbidden : List.of(
                    "<artifactId>core</artifactId>",
                    "<artifactId>a2a-server</artifactId>",
                    "<artifactId>mcp-server</artifactId>",
                    "<artifactId>fusion-http-server-tomcat</artifactId>",
                    "<artifactId>tomcat-servlet-api</artifactId>")) {
                assertFalse(pom.contains(forbidden), module + " -> " + forbidden);
            }
            try (final var files = Files.walk(root.resolve(module).resolve("src/main/java"))) {
                for (final var file :
                        files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    final var source = Files.readString(file);
                    for (final var forbidden : List.of(
                            "import io.yupiik.ia.agent.",
                            "import jakarta.servlet.",
                            "Class.forName(",
                            ".getDeclaredMethod(",
                            "Proxy.newProxyInstance(")) {
                        assertFalse(source.contains(forbidden), file + " -> " + forbidden);
                    }
                }
            }
        }
        assertFalse(Files.exists(root.resolve("a2a/documentation/pom.xml")));
        assertFalse(Files.exists(root.resolve("mcp/documentation/pom.xml")));
        assertFalse(Files.exists(root.resolve("mcp/mcp-server/src/main/java/io/yupiik/fusion/mcp/model/Content.java")));
        assertFalse(
                Files.exists(root.resolve("mcp/mcp-server/src/main/java/io/yupiik/fusion/mcp/client/MCPClient.java")));
        assertFalse(Files.readString(root.resolve("mcp/mcp-client/src/main/java/io/yupiik/ai/mcp/client/McpWire.java"))
                .contains("@JsonModel"));
    }

    private Element child(final Element parent, final String name) {
        final var children = parent.getChildNodes();
        for (var i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }
}
