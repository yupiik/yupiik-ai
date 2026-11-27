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
package io.yupiik.ia.agent.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TokenEstimatorTest {
    @Test
    void oneCommaDoesNotClassifyAnEntireParagraphAsDenseCode() {
        final var prose = "ordinary words ".repeat(100);
        assertTrue(TokenEstimator.estimate(prose + ",") - TokenEstimator.estimate(prose) < 5);
    }

    @Test
    void nullAndEmptyAreZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void plainTextEstimate() {
        assertEquals(2, TokenEstimator.estimate("abcdefgh"));
    }

    @Test
    void codeSyntaxCostsMore() {
        final var plain = TokenEstimator.estimate("abcdef");
        final var code = TokenEstimator.estimate("a{b}=c");
        assertEquals(3, code);
        assertTrue(code > plain * 0.8);
    }
}
