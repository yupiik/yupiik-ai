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
package io.yupiik.ia.agent.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AcpCommandPolicyTest {
    @Nested
    @FusionSupport(modules = AcpConfigurationTestModule.class)
    class Defaults {
        @Test
        void fusionDefaultsContainOnlyAcpAdditionsToTheCommonSecretRules(@Fusion final AcpCommand.Conf conf) {
            assertEquals(
                    List.of(
                            "passphrase",
                            "recoverycode",
                            "backupcode",
                            "payment",
                            "creditcard",
                            "debitcard",
                            "cardnumber",
                            "cardverification",
                            "securitycode",
                            "onetimecode"),
                    conf.elicitationSensitiveMarkers());
            assertEquals(List.of("pin", "cvv", "cvc", "otp"), conf.elicitationSensitiveWords());
        }
    }

    @Nested
    @FusionSupport(modules = OverridesModule.class)
    class Overrides {
        @Test
        void fusionParsesLiteralMarkerAndWholeWordOverrides(@Fusion final AcpCommand.Conf conf) {
            assertEquals(List.of("Team / Entry", "company badge"), conf.elicitationSensitiveMarkers());
            assertEquals(List.of("stepup", "o.tp"), conf.elicitationSensitiveWords());
        }
    }

    @Nested
    @FusionSupport(modules = EmptyModule.class)
    class Empty {
        @Test
        void explicitEmptyListsDisableOnlyTheAcpAdditionsInsteadOfRestoringDefaults(
                @Fusion final AcpCommand.Conf conf) {
            assertEquals(List.of(), conf.elicitationSensitiveMarkers());
            assertEquals(List.of(), conf.elicitationSensitiveWords());
        }
    }

    public static class OverridesModule extends AcpConfigurationTestModule {
        public OverridesModule() {
            super(Map.of(
                    "elicitationSensitiveMarkers",
                    "Team / Entry,company badge",
                    "elicitationSensitiveWords",
                    "stepup,o.tp"));
        }
    }

    public static class EmptyModule extends AcpConfigurationTestModule {
        public EmptyModule() {
            super(Map.of("elicitationSensitiveMarkers", "", "elicitationSensitiveWords", ""));
        }
    }
}
