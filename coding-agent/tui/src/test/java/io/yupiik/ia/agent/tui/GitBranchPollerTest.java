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
package io.yupiik.ia.agent.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitBranchPollerTest {

    @TempDir
    Path temp;

    @Test
    void resolveHeadWalksUpToAPlainDotGitDirectory() throws Exception {
        final var repo = temp.resolve("a").resolve("b");
        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve(".git").resolve("HEAD"), "ref: refs/heads/main\n");

        assertEquals(
                repo.resolve(".git").resolve("HEAD").toAbsolutePath(),
                GitBranchPoller.resolveHead(repo.toAbsolutePath().toString()));
    }

    @Test
    void resolveHeadFollowsAGitDirFileForSubmodulesAndWorktrees() throws Exception {
        final var repo = temp.resolve("sub");
        Files.createDirectories(repo);
        // submodule layout: '.git' is a file pointing at the real git dir outside the worktree
        Files.writeString(repo.resolve(".git"), "gitdir: ../.git/modules/sub\n");
        final var gitDir = temp.resolve(".git").resolve("modules").resolve("sub");
        Files.createDirectories(gitDir);
        Files.writeString(gitDir.resolve("HEAD"), "ref: refs/heads/feature\n");

        final var head = GitBranchPoller.resolveHead(repo.toAbsolutePath().toString());
        assertEquals(gitDir.resolve("HEAD").toAbsolutePath(), head);
        assertTrue(Files.isRegularFile(head));
    }

    @Test
    void branchOfParsesRefAndDetachedHead() {
        assertEquals("main", GitBranchPoller.branchOf("ref: refs/heads/main\n"));
        assertEquals("1a2b3c4", GitBranchPoller.branchOf("1a2b3c4d5e6f7g8h"));
        assertEquals("abc", GitBranchPoller.branchOf("abc"));
        assertNull(GitBranchPoller.branchOf("   "));
        assertNull(GitBranchPoller.branchOf(null));
    }

    @Test
    void pollerNotifiesOnContentChangeAndStopsOnClose() throws Exception {
        final var gitDir = temp.resolve(".git");
        Files.createDirectories(gitDir);
        final var head = gitDir.resolve("HEAD");
        Files.writeString(head, "ref: refs/heads/main\n");

        final var changes = new LinkedBlockingQueue<String>();
        final var scheduler = testScheduler();
        try {
            // fast polling: the detection logic is unchanged, only the tick is shortened so the
            // test does not spend a full production interval waiting for the scheduler
            final var poller = new GitBranchPoller(
                    scheduler, changes::add, temp.toAbsolutePath().toString(), 15L);
            poller.start();
            assertEquals("main", changes.poll(5, TimeUnit.SECONDS), "initial poll must publish the branch");
            assertEquals("main", poller.branch());

            Files.writeString(head, "ref: refs/heads/feature\n");
            assertEquals("feature", changes.poll(5, TimeUnit.SECONDS), "content change must be detected");
            assertEquals("feature", poller.branch());

            // closing cancels the task: further changes must not notify anymore
            poller.close();
            Files.writeString(head, "ref: refs/heads/next\n");
            assertNull(changes.poll(150, TimeUnit.MILLISECONDS), "closed poller must not notify");
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static ScheduledExecutorService testScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            final var t = new Thread(r, "git-branch-poller-test");
            t.setDaemon(true);
            return t;
        });
    }
}
