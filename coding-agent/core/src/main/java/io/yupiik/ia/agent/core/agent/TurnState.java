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
package io.yupiik.ia.agent.core.agent;

/** Mutable counters and flags confined to one {@link AgentLoop} turn. */
final class TurnState {
    int llmCalls;
    long thinkingMs;
    long toolSumMs;
    long toolWallMs;
    int toolsRun;
    boolean thinkingActive;
    long thinkingStartNanos;
    long llmActiveMs;
    long llmPausedMs;
    int outputTokens;
    int reasoningTokens;
    long llmReportedMs;
    int reportedOutputTokens;
    int reportedRequests;
    boolean outputEstimated;
    int unfinishedTodoContinues;
    boolean lastRequestProducedReasoning;
    boolean iterationLimitReached;

    void reset() {
        llmCalls = 0;
        thinkingMs = 0L;
        toolSumMs = 0L;
        toolWallMs = 0L;
        toolsRun = 0;
        thinkingActive = false;
        thinkingStartNanos = 0L;
        llmActiveMs = 0L;
        llmPausedMs = 0L;
        outputTokens = 0;
        reasoningTokens = 0;
        llmReportedMs = 0L;
        reportedOutputTokens = 0;
        reportedRequests = 0;
        outputEstimated = false;
        unfinishedTodoContinues = 0;
        lastRequestProducedReasoning = false;
        iterationLimitReached = false;
    }
}
