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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.ia.agent.github.client.GitHubClient;
import io.yupiik.ia.agent.github.configuration.FixConfiguration;
import io.yupiik.ia.agent.github.model.AgentResult;
import io.yupiik.ia.agent.github.model.TaskDocument;
import io.yupiik.ia.agent.github.store.TaskStore;
import io.yupiik.ia.agent.github.support.BotJsonMapper;
import io.yupiik.ia.agent.github.support.BotTestConfiguration;
import io.yupiik.ia.agent.github.support.TaskFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FixPublisherTest {
    private static final String BASE = "a".repeat(40);
    private static final String ROOT = "b".repeat(40);
    private static final String DIR = "c".repeat(40);
    private static final String BLOB = "d".repeat(40);
    private static final String TREE = "e".repeat(40);
    private static final String COMMIT = "f".repeat(40);
    private static final String MOVED = "1".repeat(40);
    private static final String URL = "https://github.com/up/repo/pull/42";

    @TempDir
    Path home;

    @Test
    void pinsPublishesOnlyToExistingHeadAndRetainsEvidence() throws Exception {
        try (final var f = new Fixture()) {
            final var pinned = f.prepare();
            assertEquals("HEAD_ID", pinned.repositoryId());
            assertEquals("fork/repo", pinned.repository());
            assertEquals(BASE, pinned.headSha());
            assertEquals(pinned, f.prepare());
            assertEquals(pinned, f.restart().prepare(f.task()));
            assertEquals(URL, f.publish());
            assertEquals(List.of("blob-0", "tree", "commit", "cas"), f.writes);
            assertEquals(
                    List.of("/repos/fork/repo/git/trees/" + ROOT, "/repos/fork/repo/git/trees/" + DIR),
                    f.reads.stream().filter(p -> p.contains("/git/trees/")).toList());
            final var tree = TaskStore.map(f.journal().get("tree"));
            final var treeRequest = TaskStore.map(tree.get("request"));
            final var entry = TaskStore.map(((List<?>) treeRequest.get("tree")).getFirst());
            assertEquals("100755", entry.get("mode"));
            assertEquals(ROOT, treeRequest.get("base_tree"));
            final var commitRequest =
                    TaskStore.map(TaskStore.map(f.journal().get("commit")).get("request"));
            assertEquals(List.of(BASE), commitRequest.get("parents"));
            assertEquals("Fix tests", commitRequest.get("message"));
            final var cas = TaskStore.map(TaskStore.map(f.journal().get("cas")).get("request"));
            final var input = TaskStore.map(TaskStore.map(cas.get("variables")).get("input"));
            assertEquals("HEAD_ID", input.get("repositoryId"));
            assertEquals(f.journal().get("fingerprint"), input.get("clientMutationId"));
            assertEquals(
                    Map.of("name", "refs/heads/topic", "beforeOid", BASE, "afterOid", COMMIT, "force", false),
                    ((List<?>) input.get("refUpdates")).getFirst());
            assertEquals("retained-attempt", f.task().attemptDirectory());
            assertEquals(f.result, f.task().result());
            assertEquals(
                    Map.of("nested", Map.of("opaque", List.of("retained", Map.of("future", true)))),
                    f.task().recovery().get("other"));
            assertEquals(URL, f.restart().publish(f.task(), f.result));
            assertEquals(4, f.writes.size());
            assertTrue(f.unexpected.isEmpty(), f.unexpected.toString());
        }
    }

    @Test
    void mixedDeletionAndTypedRequestsKeepExactDurableWireEvidenceAfterRestart() throws Exception {
        try (final var f = new Fixture()) {
            f.prepare();
            f.setChanges(List.of(
                    change("src/new.txt", "Quotes: \"yes\"\nBackslash: \\ and accents: \u00e9"),
                    new AgentResult.Change("src/run.sh", null, true, null)));
            assertEquals(URL, f.publish());
            final var request =
                    TaskStore.map(TaskStore.map(f.journal().get("tree")).get("request"));
            final var entries = (List<?>) request.get("tree");
            final var deletion = new LinkedHashMap<String, Object>();
            deletion.put("path", "src/run.sh");
            deletion.put("mode", "100755");
            deletion.put("type", "blob");
            deletion.put("sha", null);
            assertEquals(
                    Map.of(
                            "base_tree",
                            ROOT,
                            "tree",
                            List.of(
                                    Map.of("path", "src/new.txt", "mode", "100644", "type", "blob", "sha", BLOB),
                                    deletion)),
                    request);
            final var blob =
                    TaskStore.map(TaskStore.map(f.journal().get("blob-0")).get("request"));
            assertEquals(
                    Map.of(
                            "content",
                            f.result.strictSolve().changes().getFirst().content(),
                            "encoding",
                            "base64"),
                    blob);
            assertEquals(URL, f.restart().publish(f.task(), f.result));
            assertEquals(List.of("blob-0", "tree", "commit", "cas"), f.writes);
            assertTrue(f.unexpected.isEmpty(), f.unexpected.toString());
        }
    }

    @Test
    void disabledAndEmptyExactAllowlistDenyBeforeWrites() throws Exception {
        try (final var f = new Fixture()) {
            for (final var config : List.of(
                    new FixConfiguration(false, "fork/repo"),
                    new FixConfiguration(true, ""),
                    new FixConfiguration(true, "fork/*"),
                    new FixConfiguration(true, "Fork/repo"),
                    new FixConfiguration(true, "other/repo"))) {
                assertThrows(
                        IllegalStateException.class, () -> f.publisher(config).prepare(f.task()));
                assertTrue(f.writes.isEmpty());
                assertNull(f.task().recovery().get("fixTarget"));
            }
        }
    }

    @Test
    void rejectsDiscussionIssueClosedArchivedPermissionlessAndIdentityChanges() throws Exception {
        for (final var invalid :
                List.of("discussion", "issue", "closed", "archived", "permission", "missingHead", "upstreamArchived")) {
            try (final var f = new Fixture()) {
                f.invalid = invalid;
                if (invalid.equals("discussion")) {
                    f.store.transaction(state -> {
                        state.task(f.id).sourceType("discussion");
                        return null;
                    });
                }
                assertThrows(RuntimeException.class, f::prepare, invalid);
                assertTrue(f.writes.isEmpty(), invalid);
                assertNull(f.task().recovery().get("fixTarget"));
            }
        }
        for (final var invalid :
                List.of("headId", "headName", "upstreamId", "prId", "branch", "closed", "permission")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.invalid = invalid;
                assertThrows(RuntimeException.class, f::prepare, invalid);
                assertThrows(RuntimeException.class, f::publish, invalid);
                assertTrue(f.writes.isEmpty(), invalid);
            }
        }
    }

    @Test
    void headMovementNeverRepinsAndRejectsFastForwardRewindAndDivergence() throws Exception {
        for (final var status : List.of("ahead", "behind", "diverged")) {
            try (final var f = new Fixture()) {
                final var target = f.prepare();
                f.head = MOVED;
                f.compareStatus = status;
                assertThrows(IllegalStateException.class, f::prepare);
                assertThrows(IllegalStateException.class, f::publish);
                assertEquals(
                        target.headSha(),
                        TaskStore.map(f.task().recovery().get("fixTarget")).get("headSha"));
                assertTrue(f.writes.isEmpty());
            }
        }
    }

    @Test
    void movingHeadImmediatelyBeforeCasIsRejectedWithoutRefWrite() throws Exception {
        for (final var status : List.of("ahead", "behind", "diverged")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.moveOnCommit = true;
                f.compareStatus = status;
                assertThrows(IllegalStateException.class, f::publish);
                assertEquals(List.of("blob-0", "tree", "commit"), f.writes);
                assertFalse(f.journal().containsKey("cas"));
            }
        }
    }

    @Test
    void atomicBeforeOidRejectsForcePushBetweenReadAndMutation() throws Exception {
        for (final var status : List.of("ahead", "behind", "diverged")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.race = true;
                f.compareStatus = status;
                // Even "ahead" is not proof if the new commit is not the merge base.
                assertThrows(IllegalStateException.class, f::publish);
                assertEquals(MOVED, f.head);
                assertEquals(List.of("blob-0", "tree", "commit", "cas"), f.writes);
                assertTrue(f.journal().containsKey("cas"));
                assertFalse(f.journal().containsKey("url"));
                assertThrows(IllegalStateException.class, () -> f.restart().publish(f.task(), f.result));
                assertEquals(4, f.writes.size());
            }
        }
    }

    @Test
    void uncertainCasOnlyReconcilesExactHeadOrAncestryWithoutReplay() throws Exception {
        for (final var incorporated : List.of(false, true)) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.fail = "cas";
                f.acceptUncertainCas = true;
                f.incorporated = incorporated;
                assertEquals(URL, f.publish());
                assertEquals(URL, f.restart().publish(f.task(), f.result));
                assertEquals(4, f.writes.size());
                assertEquals(URL, f.journal().get("url"));
            }
        }
        try (final var f = new Fixture()) {
            f.prepare();
            f.fail = "cas";
            assertThrows(IllegalStateException.class, f::publish);
            assertTrue(f.journal().containsKey("cas"));
            assertFalse(f.journal().containsKey("url"));
            f.fail = null;
            assertThrows(IllegalStateException.class, () -> f.restart().publish(f.task(), f.result));
            assertEquals(4, f.writes.size());
            // An operator can repair the remote state; the service still performs only reads.
            f.head = COMMIT;
            assertEquals(URL, f.restart().publish(f.task(), f.result));
            assertEquals(4, f.writes.size());
        }
    }

    @Test
    void stalePullHeadAfterSuccessfulCasDefersThenReconcilesWithoutReplay() throws Exception {
        try (final var f = new Fixture()) {
            f.prepare();
            f.lagPullAfterCas = true;
            assertThrows(IllegalStateException.class, f::publish);
            assertEquals(COMMIT, f.head);
            assertEquals(List.of("blob-0", "tree", "commit", "cas"), f.writes);
            assertFalse(f.journal().containsKey("url"));
            final var claimedCas = f.journal().get("cas");

            // GitHub's PR snapshot can lag the authoritative ref after a successful mutation.
            // Once its read models agree, restart recovery must not submit the CAS again.
            f.lagPullAfterCas = false;
            assertEquals(URL, f.restart().publish(f.task(), f.result));
            assertEquals(claimedCas, f.journal().get("cas"));
            assertEquals(4, f.writes.size());
            assertEquals(COMMIT, f.journal().get("observedHead"));
            assertTrue(f.unexpected.isEmpty(), f.unexpected.toString());
        }
    }

    @Test
    void everyUnknownObjectStopsBeforeCasAndNeverReplaysAfterRestart() throws Exception {
        for (final var stage : List.of("blob-0", "tree", "commit")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.fail = stage;
                assertThrows(RuntimeException.class, f::publish);
                final int count = f.writes.size();
                assertTrue(TaskStore.map(f.journal().get(stage)).containsKey("intent"));
                assertFalse(TaskStore.map(f.journal().get(stage)).containsKey("sha"));
                assertFalse(f.journal().containsKey("cas"));
                f.fail = null;
                assertThrows(IllegalStateException.class, () -> f.restart().publish(f.task(), f.result));
                assertEquals(count, f.writes.size());
                assertEquals("retained-attempt", f.task().attemptDirectory());
                assertEquals(f.result, f.task().result());
            }
        }
    }

    @Test
    void concurrentPublicationClaimsEachIntentAtMostOnce() throws Exception {
        try (final var f = new Fixture();
                final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            f.prepare();
            final var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++) {
                tasks.add(executor.submit(() -> {
                    try {
                        f.restart().publish(f.task(), f.result);
                    } catch (final IllegalStateException expectedContention) {
                        // A loser cannot replay a claimed write, even if its peer is still sending it.
                    }
                }));
            }
            for (final var task : tasks) {
                task.get();
            }
            assertEquals(4, f.writes.size());
            assertEquals(f.writes.size(), f.writes.stream().distinct().count());
            assertTrue(f.unexpected.isEmpty(), f.unexpected.toString());
        }
    }

    @Test
    void ignoresBodyButBindsResultBaseTitleAndChanges() throws Exception {
        try (final var f = new Fixture()) {
            f.prepare();
            assertEquals(URL, f.publish());
            final var original = f.result;
            f.result = new AgentResult(
                    original.schemaVersion(),
                    original.taskId(),
                    original.summary(),
                    original.failure(),
                    original.prUrl(),
                    original.review(),
                    new AgentResult.StrictSolve(
                            BASE,
                            "Fix tests",
                            "ignored changed body",
                            original.strictSolve().changes()),
                    original.tests(),
                    original.reviewId(),
                    original.evidence());
            assertEquals(URL, f.publish());
            f.setChanges(List.of(change("src/run.sh", "other")));
            assertThrows(IllegalStateException.class, f::publish);
            assertEquals(4, f.writes.size());
        }
    }

    @Test
    void validatesBoundsCanonicalBase64PathsUnknownAttributesAndOverlapBeforeWrites() throws Exception {
        final var cases = new ArrayList<List<AgentResult.Change>>();
        cases.add(List.of());
        cases.add(IntStream.range(0, 101).mapToObj(i -> change("f" + i, "x")).toList());
        for (final var path : List.of(
                "../x",
                "x/../y",
                "/x",
                "a//b",
                "a/./b",
                "a\\b",
                ".git/config",
                "a/.GIT/config",
                ".github/workflows/x.yml",
                ".GITHUB/workflows/X",
                ".github/workflows",
                "a.",
                "a ",
                "a:b",
                "a~b")) {
            cases.add(List.of(change(path, "x")));
        }
        cases.add(List.of(change("a", "x"), change("a/b", "y")));
        cases.add(List.of(change("a", "x"), change("a", "y")));
        cases.add(List.of(new AgentResult.Change("a", "eA", false, null)));
        cases.add(List.of(new AgentResult.Change("a", "eB==", false, null)));
        cases.add(List.of(new AgentResult.Change("a", "eA==\n", false, null)));
        cases.add(List.of(new AgentResult.Change("a", "", null, null)));
        cases.add(List.of(new AgentResult.Change("a", "", false, Map.of("mode", "100755"))));
        cases.add(List.of(new AgentResult.Change("a", "eA==", true, null)));
        cases.add(List.of(change("big", "x".repeat(1024 * 1024 + 1))));
        cases.add(IntStream.range(0, 5)
                .mapToObj(i -> change("big" + i, "x".repeat(1024 * 1024)))
                .toList());
        try (final var f = new Fixture()) {
            f.prepare();
            for (final var changes : cases) {
                f.setChanges(changes);
                assertThrows(IllegalArgumentException.class, f::publish);
                assertTrue(f.writes.isEmpty());
            }
        }
    }

    @Test
    void deletionWithNullOmittingMapperFailsBeforeAnyMutation() throws Exception {
        try (final var f = new Fixture()) {
            f.prepare();
            f.setChanges(List.of(new AgentResult.Change("src/run.sh", null, true, null)));
            final var publisher =
                    new FixPublisher(f.github, f.store, f.rawJson, new FixConfiguration(true, "fork/repo"));
            final var error = assertThrows(IllegalStateException.class, () -> publisher.publish(f.task(), f.result));
            assertTrue(error.getMessage().contains("serialize nulls"));
            assertTrue(f.writes.isEmpty());
        }
    }

    @Test
    void newFilesUseRegularModeAndReadOnlyExistingAncestorDirectories() throws Exception {
        for (final var path : List.of("src/new.sh", "new-file", "new/directory/file")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.setChanges(List.of(change(path, "new")));
                assertEquals(URL, f.publish());
                final var request =
                        TaskStore.map(TaskStore.map(f.journal().get("tree")).get("request"));
                assertEquals(
                        "100644",
                        TaskStore.map(((List<?>) request.get("tree")).getFirst())
                                .get("mode"));
                assertEquals(
                        path.startsWith("src/") ? 2 : 1,
                        f.reads.stream().filter(p -> p.contains("/git/trees/")).count());
            }
        }
        for (final var path : List.of("src/run.sh/child")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.setChanges(List.of(change(path, "x")));
                assertThrows(IllegalArgumentException.class, f::publish);
                assertTrue(f.writes.isEmpty());
            }
        }
    }

    @Test
    void invalidResultBindingBaseAndTitleDenyWithoutMutation() throws Exception {
        for (final var invalid : List.of("base", "title", "newline", "task")) {
            try (final var f = new Fixture()) {
                f.prepare();
                final var original = f.result;
                f.result = new AgentResult(
                        original.schemaVersion(),
                        invalid.equals("task") ? "other" : f.id,
                        original.summary(),
                        original.failure(),
                        original.prUrl(),
                        original.review(),
                        new AgentResult.StrictSolve(
                                invalid.equals("base") ? MOVED : BASE,
                                invalid.equals("title")
                                        ? "x".repeat(257)
                                        : invalid.equals("newline") ? "bad\nmessage" : "Fix tests",
                                "ignored",
                                original.strictSolve().changes()),
                        original.tests(),
                        original.reviewId(),
                        original.evidence());
                assertThrows(IllegalArgumentException.class, f::publish);
                assertTrue(f.writes.isEmpty());
            }
        }
    }

    @Test
    void onlyRegularFilesAndExistingDeletionsAllowedAndTruncatedDirectoryDenies() throws Exception {
        for (final var mode : List.of("120000", "160000", "040000")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.mode = mode;
                assertThrows(IllegalArgumentException.class, f::publish);
                assertTrue(f.writes.isEmpty());
            }
        }
        try (final var f = new Fixture()) {
            f.prepare();
            f.truncated = true;
            assertThrows(IllegalStateException.class, f::publish);
            assertTrue(f.writes.isEmpty());
        }
        try (final var f = new Fixture()) {
            f.prepare();
            f.setChanges(List.of(new AgentResult.Change("absent", null, true, null)));
            assertThrows(IllegalArgumentException.class, f::publish);
            assertTrue(f.writes.isEmpty());
        }
        try (final var f = new Fixture()) {
            f.prepare();
            f.setChanges(List.of(new AgentResult.Change("src/run.sh", null, true, null)));
            assertEquals(URL, f.publish());
            assertEquals(List.of("tree", "commit", "cas"), f.writes);
            final var request =
                    TaskStore.map(TaskStore.map(f.journal().get("tree")).get("request"));
            final var deletion = TaskStore.map(((List<?>) request.get("tree")).getFirst());
            assertTrue(deletion.containsKey("sha"), "Deletion must send explicit null, not omit sha");
            assertNull(deletion.get("sha"));
        }
    }

    @Test
    void malformedGraphqlIsUncertainAndOnlyRemoteIncorporationCanCompleteIt() throws Exception {
        for (final var malformed : List.of("graphqlErrors", "graphqlData", "graphqlMissing")) {
            for (final var accepted : List.of(false, true)) {
                try (final var f = new Fixture()) {
                    f.prepare();
                    f.malformed = malformed;
                    f.suppressCas = !accepted;
                    if (accepted) {
                        assertEquals(URL, f.publish());
                    } else {
                        assertThrows(IllegalStateException.class, f::publish);
                    }
                    final var writes = List.copyOf(f.writes);
                    if (accepted) {
                        assertEquals(URL, f.publish());
                    } else {
                        assertThrows(IllegalStateException.class, f::publish);
                    }
                    assertEquals(writes, f.writes);
                }
            }
        }
    }

    @Test
    void commitAndComparisonIdentitiesMustMatchPinnedQueries() throws Exception {
        try (final var f = new Fixture()) {
            f.prepare();
            f.malformed = "commitId";
            assertThrows(IllegalStateException.class, f::publish);
            assertTrue(f.writes.isEmpty());
        }
        for (final var malformed : List.of("compareBase", "compareMerge")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.incorporated = true;
                f.malformed = malformed;
                assertThrows(IllegalStateException.class, f::publish);
                assertThrows(IllegalStateException.class, f::publish);
                assertEquals(4, f.writes.size());
                assertFalse(f.journal().containsKey("url"));
            }
        }
    }

    @Test
    void successfulCasCannotHideChangedTargetIdentityOrAuthorization() throws Exception {
        for (final var changed :
                List.of("headId", "headName", "upstreamId", "prId", "branch", "closed", "permission")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.changeAfterCas = changed;
                assertThrows(RuntimeException.class, f::publish);
                assertThrows(RuntimeException.class, f::publish);
                assertEquals(4, f.writes.size());
                assertFalse(f.journal().containsKey("url"));
            }
        }
    }

    @Test
    void malformedObjectAcknowledgementsAreUnknownAndCannotBeReplayed() throws Exception {
        for (final var stage : List.of("blob-0", "tree", "commit")) {
            try (final var f = new Fixture()) {
                f.prepare();
                f.malformed = stage;
                assertThrows(IllegalStateException.class, f::publish);
                final var writes = List.copyOf(f.writes);
                assertFalse(TaskStore.map(f.journal().get(stage)).containsKey("sha"));
                f.malformed = null;
                assertThrows(IllegalStateException.class, f::publish);
                assertEquals(writes, f.writes);
            }
        }
    }

    private static AgentResult.Change change(final String path, final String content) {
        return new AgentResult.Change(
                path, Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)), false, null);
    }

    private final class Fixture implements AutoCloseable {
        private final JsonMapper rawJson = BotJsonMapper.create(ignored -> Optional.empty());
        private final JsonMapper json = rawJson.as(JsonMapper.Configuring.class)
                .orElseThrow()
                .serializeNulls()
                .build();
        private final Path directory = home.resolve(UUID.randomUUID().toString());
        private final TaskStore store = new TaskStore(BotTestConfiguration.home(directory), rawJson);
        private final String id;
        private final HttpServer server;
        private final GitHubClient github;
        private final List<String> reads = new CopyOnWriteArrayList<>();
        private final List<String> writes = new CopyOnWriteArrayList<>();
        private final List<String> unexpected = new CopyOnWriteArrayList<>();
        private AgentResult result;
        private volatile String head = BASE;
        private String invalid;
        private String fail;
        private String mode = "100755";
        private boolean truncated;
        private boolean race;
        private boolean moveOnCommit;
        private boolean acceptUncertainCas;
        private boolean incorporated;
        private String compareStatus = "diverged";
        private String malformed;
        private String changeAfterCas;
        private boolean suppressCas;
        private boolean lagPullAfterCas;

        private Fixture() throws IOException {
            final var task = TaskFixtures.task(Map.of(
                    "repository",
                    "up/repo",
                    "number",
                    42,
                    "kind",
                    "fix",
                    "request",
                    "fix tests",
                    "attemptDirectory",
                    "retained-attempt"));
            id = store.transaction(state -> {
                final var queued = state.enqueue("mention", task);
                state.task(queued)
                        .recovery(Map.of(
                                "other",
                                Map.of("nested", Map.of("opaque", List.of("retained", Map.of("future", true))))));
                return queued;
            });
            result = TaskFixtures.result(Map.of(
                    "taskId",
                    id,
                    "strictSolve",
                    Map.of(
                            "baseSha",
                            BASE,
                            "title",
                            "Fix tests",
                            "body",
                            "Never published",
                            "changes",
                            List.of(Map.of("path", "src/run.sh", "content", "eA==", "delete", false)))));
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
            github = new GitHubClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    json,
                    () -> "host-secret",
                    BotTestConfiguration.CLOCK);
        }

        private FixPublisher publisher(final FixConfiguration configuration) {
            return new FixPublisher(github, store, json, configuration);
        }

        private FixPublisher restart() {
            return new FixPublisher(
                    github,
                    new TaskStore(BotTestConfiguration.home(directory), rawJson),
                    json,
                    new FixConfiguration(true, "fork/repo"));
        }

        private FixPublisher.Target prepare() {
            return restart().prepare(task());
        }

        private String publish() {
            return restart().publish(task(), result);
        }

        private TaskDocument task() {
            return store.transaction(state -> state.task(id).document());
        }

        private Map<String, Object> journal() {
            return TaskStore.map(task().recovery().get("fixPublication"));
        }

        private void setChanges(final List<AgentResult.Change> changes) {
            result = new AgentResult(
                    result.schemaVersion(),
                    id,
                    result.summary(),
                    result.failure(),
                    result.prUrl(),
                    result.review(),
                    new AgentResult.StrictSolve(BASE, "Fix tests", "ignored", changes),
                    result.tests(),
                    result.reviewId(),
                    result.evidence());
        }

        private Map<String, Object> repo(final boolean upstream) {
            return Map.of(
                    "node_id",
                    upstream
                            ? ("upstreamId".equals(invalid) ? "REPLACED" : "UP_ID")
                            : ("headId".equals(invalid) ? "REPLACED" : "HEAD_ID"),
                    "full_name",
                    upstream ? "up/repo" : ("headName".equals(invalid) ? "other/repo" : "fork/repo"),
                    "archived",
                    upstream ? "upstreamArchived".equals(invalid) : "archived".equals(invalid),
                    "disabled",
                    false,
                    "permissions",
                    Map.of("push", !"permission".equals(invalid)));
        }

        private Map<String, Object> pr() {
            final var pr = new LinkedHashMap<String, Object>();
            pr.put("number", 42);
            pr.put("node_id", "prId".equals(invalid) ? "OTHER_PR" : "PR_ID");
            pr.put("state", "closed".equals(invalid) ? "closed" : "open");
            pr.put("html_url", URL);
            pr.put(
                    "head",
                    "missingHead".equals(invalid)
                            ? Map.of()
                            : Map.of(
                                    "sha",
                                    lagPullAfterCas && writes.contains("cas") ? BASE : head,
                                    "ref",
                                    "branch".equals(invalid) ? "other" : "topic",
                                    "repo",
                                    repo(false)));
            pr.put("base", Map.of("ref", "main", "repo", repo(true)));
            return pr;
        }

        private void handle(final HttpExchange exchange) throws IOException {
            int status = 200;
            Object response = Map.of();
            final var path = exchange.getRequestURI().toString();
            try {
                if (exchange.getRequestMethod().equals("GET")) {
                    reads.add(path);
                    response = switch (path) {
                        case "/repos/up/repo/pulls/42" -> {
                            if ("issue".equals(invalid)) {
                                status = 404;
                            }
                            yield pr();
                        }
                        case "/repos/fork/repo" -> repo(false);
                        case "/repos/up/repo" -> repo(true);
                        case "/repos/fork/repo/git/ref/heads/topic" ->
                            Map.of("ref", "refs/heads/topic", "object", Map.of("sha", head));
                        case "/repos/fork/repo/git/commits/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" ->
                            Map.of("sha", "commitId".equals(malformed) ? MOVED : BASE, "tree", Map.of("sha", ROOT));
                        case "/repos/fork/repo/git/trees/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" ->
                            Map.of(
                                    "sha",
                                    ROOT,
                                    "truncated",
                                    truncated,
                                    "tree",
                                    List.of(Map.of("path", "src", "mode", "040000", "type", "tree", "sha", DIR)));
                        case "/repos/fork/repo/git/trees/cccccccccccccccccccccccccccccccccccccccc" ->
                            Map.of(
                                    "sha",
                                    DIR,
                                    "truncated",
                                    false,
                                    "tree",
                                    List.of(Map.of(
                                            "path",
                                            "run.sh",
                                            "mode",
                                            mode,
                                            "type",
                                            "040000".equals(mode) ? "tree" : "160000".equals(mode) ? "commit" : "blob",
                                            "sha",
                                            BLOB)));
                        default -> {
                            if (path.startsWith("/repos/fork/repo/compare/" + COMMIT + "...")) {
                                yield Map.of(
                                        "status",
                                        incorporated ? "ahead" : compareStatus,
                                        "base_commit",
                                        Map.of("sha", "compareBase".equals(malformed) ? BASE : COMMIT),
                                        "merge_base_commit",
                                        Map.of(
                                                "sha",
                                                incorporated && !"compareMerge".equals(malformed) ? COMMIT : BASE));
                            }
                            unexpected.add("GET " + path);
                            status = 400;
                            yield Map.of();
                        }
                    };
                } else if (exchange.getRequestMethod().equals("POST")) {
                    final var body = TaskStore.map(json.fromString(
                            Object.class,
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    final var stage =
                            switch (path) {
                                case "/repos/fork/repo/git/blobs" -> "blob-0";
                                case "/repos/fork/repo/git/trees" -> "tree";
                                case "/repos/fork/repo/git/commits" -> "commit";
                                case "/graphql" -> "cas";
                                default -> "unexpected";
                            };
                    final var intent = TaskStore.map(journal().get(stage));
                    if (!Boolean.TRUE.equals(intent.get("intent"))
                            || !body.equals(intent.get("request"))
                            || !path.equals(intent.get("path"))
                            || writes.contains(stage)) {
                        throw new IllegalStateException("Missing durable intent or replay: " + path);
                    }
                    writes.add(stage);
                    if (stage.equals("cas")) {
                        final var input = TaskStore.map(
                                TaskStore.map(body.get("variables")).get("input"));
                        final var update = TaskStore.map(((List<?>) input.get("refUpdates")).getFirst());
                        if (race) {
                            head = MOVED; // Models a concurrent force push after all client-side reads.
                        }
                        if (!head.equals(update.get("beforeOid")) || !Boolean.FALSE.equals(update.get("force"))) {
                            status = 422;
                        } else if (!suppressCas && (!stage.equals(fail) || acceptUncertainCas)) {
                            head = incorporated ? MOVED : (String) update.get("afterOid");
                        }
                        response = Map.of(
                                "data",
                                Map.of("updateRefs", Map.of("clientMutationId", input.get("clientMutationId"))));
                        if ("graphqlErrors".equals(malformed)) {
                            response = Map.of(
                                    "data",
                                    Map.of("updateRefs", Map.of()),
                                    "errors",
                                    List.of(Map.of("message", "partial")));
                        } else if ("graphqlData".equals(malformed)) {
                            response = Map.of("data", "invalid");
                        } else if ("graphqlMissing".equals(malformed)) {
                            response = Map.of();
                        }
                        if (changeAfterCas != null) {
                            invalid = changeAfterCas;
                        }
                    } else {
                        response = Map.of(
                                "sha",
                                stage.equals(malformed)
                                        ? "invalid"
                                        : stage.equals("blob-0") ? BLOB : stage.equals("tree") ? TREE : COMMIT);
                        if (stage.equals("commit") && moveOnCommit) {
                            head = MOVED;
                        }
                    }
                    if (stage.equals(fail)) {
                        status = 503;
                    }
                } else {
                    unexpected.add(exchange.getRequestMethod() + " " + path);
                    status = 400;
                }
            } catch (final Throwable failure) {
                unexpected.add(failure.toString());
                status = 400;
            }
            final var bytes = json.toString(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (exchange) {
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override
        public void close() {
            github.close();
            server.stop(0);
            json.close();
            rawJson.close();
            assertTrue(unexpected.isEmpty(), unexpected.toString());
        }
    }
}
