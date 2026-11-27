package io.yupiik.ia.agent.documentation;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Verifies the rendered site, including generated pages and nested navigation. */
public final class SiteAcceptance {
    private static final Pattern LINK = Pattern.compile("(?:href|src)=\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\\bid=\"([^\"]+)\"");

    private SiteAcceptance() {}

    public static void main(final String[] args) throws IOException {
        final var root = Path.of(args[0]).toAbsolutePath().normalize();
        final var failures = new ArrayList<String>();
        final List<Path> pages;
        try (final var files = Files.walk(root)) {
            pages = files.filter(path -> path.toString().endsWith(".html")).toList();
        }
        if (pages.size() < 50) {
            throw new IllegalStateException("Expected the complete aggregated site, found " + pages.size() + " pages");
        }
        for (final var page : pages) {
            final var html = Files.readString(page);
            final var links = LINK.matcher(html);
            while (links.find()) {
                final var link = links.group(1).replace("&amp;", "&");
                if (link.isBlank() || link.startsWith("//") || link.contains(":") || link.startsWith("javascript")) {
                    continue;
                }
                final var parts = link.split("#", 2);
                final var path = parts[0].split("\\?", 2)[0];
                final var decoded = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
                final var destination = decoded.isEmpty()
                        ? page
                        : decoded.startsWith("/yupiik-ai/")
                                ? root.resolve(decoded.substring("/yupiik-ai/".length()))
                                : page.getParent().resolve(decoded).normalize();
                final var target = Files.isDirectory(destination) ? destination.resolve("index.html") : destination;
                if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                    failures.add(root.relativize(page) + " -> missing " + link);
                } else if (parts.length == 2
                        && !parts[1].isBlank()
                        && target.toString().endsWith(".html")) {
                    final var fragment = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    if (ID.matcher(Files.readString(target))
                            .results()
                            .noneMatch(match -> match.group(1).equals(fragment))) {
                        failures.add(root.relativize(page) + " -> missing anchor " + link);
                    }
                }
            }
            if (!page.getFileName().toString().equals("404.html")
                    && (!html.contains("/yupiik-ai/acp/index.html") || !html.contains("/yupiik-ai/mcp/client.html"))) {
                failures.add(root.relativize(page) + " -> incomplete hierarchical menu");
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    String.join("\n", failures.stream().distinct().toList()));
        }
        System.out.println(
                "Validated " + pages.size() + " rendered pages, local assets, anchors and hierarchical menus");
    }
}
