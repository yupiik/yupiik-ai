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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Discussions domain API. Every call is one GraphQL request, with no local retry. */
@ApplicationScoped
public class DiscussionClient {

    public static final int PAGE_SIZE = 50;
    private static final String AUTHOR = "author { __typename login ... on User { databaseId } }";
    private static final String DISCUSSION =
            "id number title body updatedAt url repository { nameWithOwner } " + AUTHOR;
    private static final String COMMENT =
            "id databaseId body updatedAt url " + AUTHOR + " discussion { id } replyTo { id }";
    private static final String PAGE = "pageInfo { hasNextPage endCursor }";
    private final GitHubClient github;

    public DiscussionClient(final GitHubClient github) {
        this.github = github;
    }

    private static final Pattern REPOSITORY_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]*");

    public boolean enabled(final String repository) {
        if (repository == null || !REPOSITORY_NAME.matcher(repository).matches()) {
            throw new IllegalArgumentException("Expected owner/repository");
        }
        final var parts = repository.split("/");
        final var data = execute(
                "query DiscussionCapability($owner:String!,$name:String!) { repository(owner:$owner,name:$name) { hasDiscussionsEnabled } }",
                new Variables(parts[0], parts[1], null, null, null));
        final var enabled = required(data.repository(), "repository").hasDiscussionsEnabled();
        if (enabled == null) {
            throw new IllegalStateException("Missing repository Discussion capability");
        }
        return enabled;
    }

    public Page<Discussion> discussions(final String repository, final String after) {
        if (repository == null || !REPOSITORY_NAME.matcher(repository).matches()) {
            throw new IllegalArgumentException("Expected owner/repository");
        }
        final var parts = repository.split("/");
        final var data = execute(
                "query DiscussionDiscovery($owner:String!,$name:String!,$after:String) {"
                        + " repository(owner:$owner,name:$name) { discussions(first:50,after:$after,"
                        + "orderBy:{field:CREATED_AT,direction:ASC}) { nodes { " + DISCUSSION + " } " + PAGE + " } } }",
                new Variables(parts[0], parts[1], after, null, null));
        final var repo = required(data.repository(), "repository");
        return page(repo.discussions(), after, this::discussion);
    }

    public Discussion discussion(final String id) {
        final var result = discussion(node(
                "query DiscussionRead($id:ID!) { node(id:$id) { ... on Discussion { " + DISCUSSION + " } } }", id));
        if (!id.equals(result.id())) {
            throw new IllegalStateException("Discussion node mismatch");
        }
        return result;
    }

    public Comment comment(final String id) {
        final var result = comment(node(
                "query DiscussionCommentRead($id:ID!) { node(id:$id) { ... on DiscussionComment { " + COMMENT
                        + " } } }",
                id));
        if (!id.equals(result.id())) {
            throw new IllegalStateException("Comment node mismatch");
        }
        return result;
    }

    public Page<Comment> comments(final String discussionId, final String after) {
        return commentsPage(discussionId, after, false);
    }

    public Page<Comment> replies(final String commentId, final String after) {
        return commentsPage(commentId, after, true);
    }

    public Comment addComment(final String discussionId, final String replyToId, final String body) {
        if (body == null || body.isBlank() || body.length() > 30000) {
            throw new IllegalArgumentException("Expected nonblank comment of at most 30000 characters");
        }
        final var data = execute(
                "mutation DiscussionPublish($input:AddDiscussionCommentInput!) {"
                        + " addDiscussionComment(input:$input) { comment { " + COMMENT + " } } }",
                new Variables(
                        null,
                        null,
                        null,
                        null,
                        new CommentInput(
                                requireId(discussionId), replyToId == null ? null : requireId(replyToId), body)));
        return comment(
                required(data.addDiscussionComment(), "addDiscussionComment").comment());
    }

    private Page<Comment> commentsPage(final String id, final String after, final boolean replies) {
        final var field = replies ? "replies" : "comments";
        final var data = execute(
                "query " + (replies ? "DiscussionReplies" : "DiscussionComments")
                        + "($id:ID!,$after:String) { node(id:$id) { ... on "
                        + (replies ? "DiscussionComment" : "Discussion") + " { id " + field
                        + "(first:50,after:$after) { nodes { " + COMMENT + " } " + PAGE + " } } } }",
                new Variables(null, null, after, requireId(id), null));
        final var node = required(data.node(), "node");
        if (!id.equals(node.id())) {
            throw new IllegalStateException("Connection parent mismatch");
        }
        return page(replies ? node.replies() : node.comments(), after, this::comment);
    }

    private Node node(final String query, final String id) {
        final var node = execute(query, new Variables(null, null, null, requireId(id), null))
                .node();
        if (node == null) {
            throw new MissingDiscussionNodeException();
        }
        return node;
    }

    /** GitHub deliberately does not distinguish a deleted node from an inaccessible one. */
    public static final class MissingDiscussionNodeException extends IllegalStateException {
        public MissingDiscussionNodeException() {
            super("Discussion or related comment is missing or inaccessible to the host token");
        }
    }

    private Data execute(final String query, final Variables variables) {
        return required(github.discussionGraphql(query, variables).data(), "data");
    }

    private Discussion discussion(final Node value) {
        required(value, "discussion");
        final var result = new Discussion(
                requireId(value.id()),
                required(required(value.repository(), "repository").nameWithOwner(), "nameWithOwner"),
                Integer.parseInt(positive(value.number())),
                required(value.title(), "title"),
                required(value.body(), "body"),
                Instant.parse(required(value.updatedAt(), "updatedAt")),
                required(value.url(), "url"),
                actor(value.author()));
        result.validateReference(result.url(), null);
        return result;
    }

    private Comment comment(final Node value) {
        required(value, "comment");
        return new Comment(
                requireId(value.id()),
                positive(value.databaseId()),
                required(required(value.discussion(), "discussion").id(), "id"),
                value.replyTo() == null ? null : required(value.replyTo().id(), "id"),
                required(value.body(), "body"),
                Instant.parse(required(value.updatedAt(), "updatedAt")),
                required(value.url(), "url"),
                actor(value.author()));
    }

    private Actor actor(final Author value) {
        if (value == null) {
            return new Actor("Deleted", "", null);
        }
        final var type = required(value.__typename(), "__typename");
        return new Actor(
                type,
                required(value.login(), "login"),
                "User".equals(type) && value.databaseId() != null ? positive(value.databaseId()) : null);
    }

    private <T> Page<T> page(final Connection value, final String after, final Function<Node, T> mapper) {
        required(value, "connection");
        final var nodes = value.nodes();
        if (nodes == null || nodes.size() > PAGE_SIZE) {
            throw new IllegalStateException("Invalid GraphQL connection nodes");
        }
        final var info = required(value.pageInfo(), "pageInfo");
        final var more = required(info.hasNextPage(), "hasNextPage");
        final var next = more ? required(info.endCursor(), "endCursor") : null;
        if (more && (next.isBlank() || next.equals(after) || nodes.isEmpty())) {
            throw new IllegalStateException("Non-progressing GraphQL cursor");
        }
        return new Page<>(nodes.stream().map(mapper).toList(), next);
    }

    private static String requireId(final String id) {
        if (id == null || id.isBlank() || id.length() > 512) {
            throw new IllegalArgumentException("Invalid GraphQL node identifier");
        }
        return id;
    }

    private static String positive(final BigDecimal value) {
        final var id = String.valueOf(value);
        if (!POSITIVE_ID.matcher(id).matches()) {
            throw new IllegalStateException("Expected positive numeric GitHub identifier");
        }
        return id;
    }

    private static <T> T required(final T value, final String field) {
        if (value == null) {
            throw new IllegalStateException("Missing GraphQL field: " + field);
        }
        return value;
    }

    @JsonModel
    public record Variables(
            @Property(documentation = "Repository owner.") String owner,
            @Property(documentation = "Repository name.") String name,
            @Property(documentation = "Connection cursor.") String after,
            @Property(documentation = "Node identity.") String id,

            @Property(documentation = "Comment mutation input.")
            CommentInput input) {}

    @JsonModel
    public record CommentInput(
            @Property(documentation = "Discussion node identity.")
            String discussionId,

            @Property(documentation = "Optional reply parent.")
            String replyToId,

            @Property(documentation = "Comment Markdown.") String body) {}

    @JsonModel
    public record Envelope(
            @Property(documentation = "GraphQL operation data.")
            Data data,

            @JsonOthers @Property(documentation = "Unknown envelope attributes; presence of errors fails closed.")
            Map<String, Object> extensions) {}

    @JsonModel
    public record Data(
            @Property(documentation = "Repository discovery result.")
            Repository repository,

            @Property(documentation = "Read node.") Node node,
            @Property(documentation = "Mutation result.") Publication addDiscussionComment) {}

    @JsonModel
    public record Repository(
            Boolean hasDiscussionsEnabled,

            @Property(documentation = "Canonical repository name.")
            String nameWithOwner,

            @Property(documentation = "Discussion discovery connection.")
            Connection discussions) {}

    @JsonModel
    public record Publication(
            @Property(documentation = "Published comment.") Node comment) {}

    @JsonModel
    public record Connection(
            @Property(documentation = "Selected discussion or comment nodes.")
            List<Node> nodes,

            @Property(documentation = "Connection progress.")
            PageInfo pageInfo) {}

    @JsonModel
    public record PageInfo(
            @Property(documentation = "Whether the connection continues.")
            Boolean hasNextPage,

            @Property(documentation = "Continuation cursor.")
            String endCursor) {}

    @JsonModel
    public record Author(
            @Property(documentation = "GitHub author type.") String __typename,
            @Property(documentation = "Current login.") String login,

            @Property(documentation = "Immutable numeric user identity.")
            BigDecimal databaseId) {}

    @JsonModel
    public record Node(
            @Property(documentation = "GraphQL identity.") String id,
            @Property(documentation = "Discussion number.") BigDecimal number,

            @Property(documentation = "Comment database identity.")
            BigDecimal databaseId,

            @Property(documentation = "Discussion title.") String title,
            @Property(documentation = "Markdown body.") String body,
            @Property(documentation = "Update instant.") String updatedAt,

            @Property(documentation = "Canonical reference.")
            String url,

            @Property(documentation = "Parent repository.") Repository repository,
            @Property(documentation = "Author identity.") Author author,
            @Property(documentation = "Parent discussion.") Node discussion,
            @Property(documentation = "Parent comment.") Node replyTo,

            @Property(documentation = "Top-level comment connection.")
            Connection comments,

            @Property(documentation = "Reply connection.") Connection replies) {}

    public record Actor(String type, String login, String databaseId) {}

    public record Page<T>(List<T> nodes, String next) {
        public Page {
            nodes = List.copyOf(nodes);
        }
    }

    public record Comment(
            String id,
            String databaseId,
            String discussionId,
            String replyToId,
            String body,
            Instant updatedAt,
            String url,
            Actor author) {}

    public record Discussion(
            String id,
            String repository,
            int number,
            String title,
            String body,
            Instant updatedAt,
            String url,
            Actor author) {
        /** Binds a comment's node relationship and canonical URL to this discussion and thread. */
        public void validate(final Comment comment, final String expectedReplyToId) {
            if (!id.equals(comment.discussionId()) || !Objects.equals(expectedReplyToId, comment.replyToId())) {
                throw new IllegalStateException("Comment belongs to a different discussion or thread");
            }
            validateReference(comment.url(), comment.databaseId());
        }

        public void validateReference(final String reference, final String databaseId) {
            final var root = URI.create(url);
            final var candidate = URI.create(reference);
            if (!REPOSITORY_NAME.matcher(repository).matches()
                    || !"https".equals(root.getScheme())
                    || root.getHost() == null
                    || root.getUserInfo() != null
                    || (root.getPort() != -1 && root.getPort() != 443)
                    || root.getRawQuery() != null
                    || root.getRawFragment() != null
                    || !("/" + repository + "/discussions/" + number).equals(root.getRawPath())
                    || !Objects.equals(root.getScheme(), candidate.getScheme())
                    || !Objects.equals(root.getRawAuthority(), candidate.getRawAuthority())
                    || !Objects.equals(root.getRawPath(), candidate.getRawPath())
                    || candidate.getRawQuery() != null
                    || !Objects.equals(
                            databaseId == null ? null : "discussioncomment-" + databaseId,
                            candidate.getRawFragment())) {
                throw new IllegalStateException("Unexpected discussion reference");
            }
        }
    }
}
