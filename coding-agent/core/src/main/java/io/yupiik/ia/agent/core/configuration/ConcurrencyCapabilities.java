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
package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * Concurrency-budget knobs of a provider.
 *
 * @param useFileLock whether the provider concurrency budget is shared with the other yca processes
 *     of the same machine through file leases, instead of being enforced per process.
 */
@JsonModel
public record ConcurrencyCapabilities(
        @Property(
                defaultValue = "false",
                documentation =
                        "Share the provider concurrency budget across the yca processes of this machine using file leases under the storage home, so several sessions honour one account-wide limit instead of multiplying it. Requires a local POSIX filesystem (record locks are unreliable on NFS/CIFS) and an explicit maxConcurrent > 0 for the provider or yca.llm; when the filesystem refuses the locks the gate silently degrades to a per-process budget.")
        boolean useFileLock) {}
