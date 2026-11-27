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
package io.yupiik.ia.agent.github.service;

import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.client.RestResponses;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared host validation for both publication protocols, scoped to one pinned tree. */
final class PinnedTreeChanges {
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");

    private static final int DIRECTORY_LIMIT = 100;
    private static final int ENTRY_LIMIT = 100_000;
    private final GitHubClient github;
    private final String prefix;
    private final Map<String, Map<String, RestResponses.TreeEntry>> directories = new HashMap<>();
    private int entryCount;

    // Package scope is intentional: this implementation is shared only by the two host publishers.
    PinnedTreeChanges(final GitHubClient github, final String prefix) {
        this.github = github;
        this.prefix = prefix;
    }

    Map<String, String> modes(final String root, final List<String> paths, final Set<String> deletions) {
        sha(root);
        if (paths.isEmpty() || paths.size() > 100) {
            throw new IllegalArgumentException("Expected 1 to 100 changed paths");
        }
        final var modes = new HashMap<String, String>();
        for (final var path : paths) {
            var tree = root;
            final var segments = path.split("/");
            for (int i = 0; i < segments.length; i++) {
                final var entry =
                        directories.computeIfAbsent(tree, this::directory).get(segments[i]);
                if (i < segments.length - 1) {
                    if (entry == null) {
                        // A missing ancestor cannot hide a symlink or gitlink in the pinned tree.
                        // Git's create-tree operation safely creates these new regular directories.
                        if (deletions.contains(path)) {
                            throw new IllegalArgumentException("Deletion requires an existing regular file");
                        }
                        break;
                    }
                    if (!"040000".equals(entry.mode()) || !"tree".equals(entry.type())) {
                        throw new IllegalArgumentException("Changed path cannot traverse non-directory ancestors");
                    }
                    tree = sha(entry.sha());
                } else if (entry == null) {
                    if (deletions.contains(path)) {
                        throw new IllegalArgumentException("Deletion requires an existing regular file");
                    }
                } else if (!List.of("100644", "100755").contains(entry.mode()) || !"blob".equals(entry.type())) {
                    throw new IllegalArgumentException("Changes must target regular files only");
                } else {
                    modes.put(path, entry.mode());
                }
            }
        }
        return modes;
    }

    private Map<String, RestResponses.TreeEntry> directory(final String oid) {
        if (directories.size() >= DIRECTORY_LIMIT) {
            throw new IllegalArgumentException("Changed directory query budget exceeded");
        }
        final var response = github.get(prefix + "/git/trees/" + oid, RestResponses.Tree.class);
        if (!oid.equals(sha(response.sha()))
                || !Boolean.FALSE.equals(response.truncated())
                || response.tree() == null) {
            throw new IllegalStateException("Complete pinned nonrecursive directory tree required");
        }
        final var entries = response.tree();
        if (entries.size() > ENTRY_LIMIT - entryCount) {
            throw new IllegalStateException("Pinned tree entry budget exceeded");
        }
        entryCount += entries.size();
        final var result = new HashMap<String, RestResponses.TreeEntry>();
        for (final var entry : entries) {
            final var name = entry.path();
            if (name == null
                    || name.isEmpty()
                    || name.contains("/")
                    || name.equals(".")
                    || name.equals("..")
                    || name.indexOf(0) >= 0
                    || result.putIfAbsent(name, entry) != null) {
                throw new IllegalStateException("Noncanonical or duplicate directory entry");
            }
            sha(entry.sha());
            final var type =
                    switch (String.valueOf(entry.mode())) {
                        case "040000" -> "tree";
                        case "100644", "100755", "120000" -> "blob";
                        case "160000" -> "commit";
                        default -> throw new IllegalStateException("Noncanonical tree entry mode");
                    };
            if (!type.equals(entry.type())) {
                throw new IllegalStateException("Tree entry mode/type mismatch");
            }
        }
        return result;
    }

    private String sha(final Object value) {
        if (!(value instanceof String oid) || !COMMIT_SHA.matcher(oid).matches()) {
            throw new IllegalStateException("Expected canonical full tree entry SHA-1");
        }
        return oid;
    }
}
