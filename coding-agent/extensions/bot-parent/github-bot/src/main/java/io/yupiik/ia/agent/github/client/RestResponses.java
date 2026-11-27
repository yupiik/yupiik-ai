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
package io.yupiik.ia.agent.github.client;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;

/**
 * Host-owned REST projections. Nullable fields retain absence for each consumer's validation.
 * Identity/size wire scalars use Object where validators must distinguish JSON numbers from quoted
 * numbers: Fusion's BigDecimal codec accepts both, which would weaken strict authority checks.
 */
public final class RestResponses {
    private RestResponses() {}

    @JsonModel
    public record Repository(
            Object id,
            String node_id,
            String full_name,
            String default_branch,
            Object size,
            Boolean archived,
            Boolean disabled,
            Boolean fork,
            Repository parent,
            Permissions permissions,
            String visibility,
            @JsonProperty("private") Boolean privateRepository) {}

    @JsonModel
    public record Permissions(Boolean push) {}

    @JsonModel
    public record Commit(String sha, ObjectId tree) {}

    @JsonModel
    public record ObjectId(String sha) {}

    @JsonModel
    public record Ref(String ref, ObjectId object) {}

    @JsonModel
    public record Pull(
            Object id,
            String node_id,
            Object number,
            String title,
            String body,
            String state,
            Boolean merged,
            String html_url,
            Head head,
            Head base,
            User user) {}

    @JsonModel
    public record Head(String sha, String ref, Repository repo) {}

    @JsonModel
    public record User(Object id, String login) {}

    @JsonModel
    public record Permission(String permission, User user) {}

    @JsonModel
    public record Membership(String state) {}

    @JsonModel
    public record Comparison(String status, ObjectId merge_base_commit, ObjectId base_commit) {}

    @JsonModel
    public record Tree(String sha, Boolean truncated, List<TreeEntry> tree) {}

    @JsonModel
    public record TreeEntry(String path, String mode, String type, String sha) {}

    @JsonModel
    public record Review(String body, String commit_id, String state, User user, String html_url) {}

    @JsonModel
    public record File(String filename, String patch) {}

    @JsonModel
    public record Issue(Object id, String node_id, String title, String body, String state, PullLink pull_request) {}

    @JsonModel
    public record PullLink(String url) {}

    @JsonModel
    public record FeedItem(Object id, Object body, Object html_url, FeedUser user, PullLink pull_request) {}

    @JsonModel
    public record FeedUser(Object id, Object login, Object type) {}

    @JsonModel
    public record Blob(String sha, Object size, String encoding, String content) {}

    @JsonModel
    public record SourceTree(String sha, Boolean truncated, List<SourceEntry> tree) {}

    @JsonModel
    public record SourceEntry(String path, String mode, String type, String sha, Object size) {}

    /** Fixed scalar projection shared by bounded REST discussion pages. */
    @JsonModel
    public record ContextEntry(
            Object id,
            Object node_id,
            Object sha,
            Object filename,
            Object previous_filename,
            Object status,
            Object patch,
            Object body,
            Object state,
            Object commit_id,
            Object submitted_at,
            Object path,
            Object line,
            Object side,
            Object in_reply_to_id,
            Object updated_at) {}
}
