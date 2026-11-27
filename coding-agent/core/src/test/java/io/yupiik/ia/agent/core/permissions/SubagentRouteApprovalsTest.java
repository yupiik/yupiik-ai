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
package io.yupiik.ia.agent.core.permissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class SubagentRouteApprovalsTest {
    @Test
    void concurrentSpawnsReuseOneSessionApproval() throws Exception {
        final var approvals = new SubagentRouteApprovals();
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var prompts = new AtomicInteger();
        final ApprovalGate gate = request -> {
            prompts.incrementAndGet();
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return ApprovalGate.Result.DENY;
            }
            return ApprovalGate.Result.ALLOW_SESSION;
        };
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                final var first =
                        executor.submit(() -> approvals.approve("$private/main", "$external/model", gate, () -> false));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                final var second =
                        executor.submit(() -> approvals.approve("$private/main", "$external/model", gate, () -> false));
                release.countDown();
                assertTrue(first.get(5, TimeUnit.SECONDS));
                assertTrue(second.get(5, TimeUnit.SECONDS));
                assertEquals(1, prompts.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void disposalWhilePromptIsPendingCannotRecreateGrant() throws Exception {
        final var approvals = new SubagentRouteApprovals();
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                final var pending = executor.submit(() -> approvals.approve(
                        "$private/main",
                        "$external/model",
                        request -> {
                            entered.countDown();
                            try {
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                            } catch (final InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                return ApprovalGate.Result.DENY;
                            }
                            return ApprovalGate.Result.ALLOW_SESSION;
                        },
                        () -> false));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                approvals.close();
                release.countDown();
                assertFalse(pending.get(5, TimeUnit.SECONDS));
                assertFalse(approvals.allows("$external/model"));
                assertTrue(approvals.routes().isEmpty());
            } finally {
                release.countDown();
            }
        }
    }
}
