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

/** Per-tool tuning for the git output processor. */
@JsonModel
public record GitProcessorOutputConfiguration(
        @Property(
                defaultValue = "true",
                documentation = "Enable the git output processor. When false it behaves as identity (passthrough).")
        boolean enabled,

        @Property(
                defaultValue = "8192",
                documentation =
                        "Raw blob budget (git show <rev>:<path>), in characters. Above it the dump is windowed to a "
                                + "head cut at the last line boundary plus a tail-recovery hint (user-run "
                                + "`git show '<rev>:<path>' | sed -n 'N,$p'`, never a second git call). "
                                + "0 disables windowing: the output is passed through byte-identical.")
        int maxBlobBytes) {

    /** @return maxBlobBytes value with the record default when the config resolved 0 from an absent entry. */
    public int effectiveMaxBlobBytes() {
        return maxBlobBytes > 0 ? maxBlobBytes : 8192;
    }
}
