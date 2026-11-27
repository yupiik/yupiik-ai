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
package io.yupiik.ia.agent.github.store;

import io.yupiik.ia.agent.github.model.TaskDocument.PublicationObject;
import io.yupiik.ia.agent.github.model.TaskDocument.SolvePublication;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transaction-local publication progress, retaining unknown legacy stages. */
public final class MutableSolvePublication {
    private String forkOwner;
    private String upstream;
    private String baseSha;
    private String baseBranch;
    private String branch;
    private String fingerprint;
    private String author;
    private String treeSha;
    private String commitSha;
    private String prUrl;
    private Boolean treeIntent;
    private Boolean commitIntent;
    private Boolean refIntent;
    private Boolean refDone;
    private Boolean prIntent;
    private final Map<String, Object> extensions;
    private final Map<String, PublicationObject> blobs;

    public MutableSolvePublication(final SolvePublication document) {
        blobs = new LinkedHashMap<>(document.blobs() == null ? Map.of() : document.blobs());
        forkOwner = document.forkOwner();
        upstream = document.upstream();
        baseSha = document.baseSha();
        baseBranch = document.baseBranch();
        branch = document.branch();
        fingerprint = document.fingerprint();
        author = document.author();
        treeSha = document.treeSha();
        commitSha = document.commitSha();
        prUrl = document.prUrl();
        treeIntent = document.treeIntent();
        commitIntent = document.commitIntent();
        refIntent = document.refIntent();
        refDone = document.refDone();
        prIntent = document.prIntent();
        extensions = new LinkedHashMap<>(document.extensions() == null ? Map.of() : document.extensions());
    }

    public SolvePublication document() {
        return new SolvePublication(
                forkOwner,
                upstream,
                baseSha,
                baseBranch,
                branch,
                fingerprint,
                author,
                treeSha,
                commitSha,
                prUrl,
                treeIntent,
                commitIntent,
                refIntent,
                refDone,
                prIntent,
                blobs,
                extensions);
    }

    public String author() {
        return author;
    }

    public void author(final String value) {
        author = value;
    }

    public String prUrl() {
        return prUrl;
    }

    public void prUrl(final String value) {
        prUrl = value;
    }

    public Boolean refDone() {
        return refDone;
    }

    public void refDone(final Boolean value) {
        refDone = value;
    }

    public String objectSha(final String stage) {
        return switch (stage) {
            case "tree" -> treeSha;
            case "commit" -> commitSha;
            default -> blobs.containsKey(stage) ? blobs.get(stage).sha() : null;
        };
    }

    public void objectSha(final String stage, final String value) {
        switch (stage) {
            case "tree" -> treeSha = value;
            case "commit" -> commitSha = value;
            default -> blobs.put(stage, new PublicationObject(true, value));
        }
    }

    public void claim(final String stage) {
        final Boolean previous =
                switch (stage) {
                    case "treeIntent" -> treeIntent;
                    case "commitIntent" -> commitIntent;
                    case "refIntent" -> refIntent;
                    case "prIntent" -> prIntent;
                    default ->
                        blobs.containsKey(stage.substring(0, stage.length() - "Intent".length()))
                                ? blobs.get(stage.substring(0, stage.length() - "Intent".length()))
                                        .intent()
                                : null;
                };
        if (previous != null) {
            throw new IllegalStateException("Uncertain publication write: " + stage + "; operator recovery required");
        }
        switch (stage) {
            case "treeIntent" -> treeIntent = true;
            case "commitIntent" -> commitIntent = true;
            case "refIntent" -> refIntent = true;
            case "prIntent" -> prIntent = true;
            default ->
                blobs.put(stage.substring(0, stage.length() - "Intent".length()), new PublicationObject(true, null));
        }
    }
}
