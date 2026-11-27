/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.fusion.a2a.test;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The client is asynchronous, the assertions are not: this unwraps a stage the way a caller would, rethrowing the
 * original failure - and not a {@link CompletionException} wrapping it - so a test can assert on
 * {@link io.yupiik.fusion.a2a.client.A2AClientException}.
 */
public final class Awaits {
    private static final long TIMEOUT_SECONDS = 30;

    private Awaits() {
        // no-op
    }

    public static <T> T await(final CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fail(ie);
        } catch (final TimeoutException te) {
            return fail("The call did not complete within " + TIMEOUT_SECONDS + "s", te);
        } catch (final ExecutionException ee) {
            final var cause = ee.getCause() == null ? ee : ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return fail(cause);
        }
    }
}
