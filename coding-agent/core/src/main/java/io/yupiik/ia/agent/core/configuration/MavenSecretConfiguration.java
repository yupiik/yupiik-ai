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
import java.util.List;

/**
 * Maven settings secrets: paths to the Maven settings file and its security file whose values are
 * treated as secrets and redacted on the wire.
 */
@JsonModel
public record MavenSecretConfiguration(
        @Property(
                defaultValue = "\"~/.m2/settings.xml\"",
                documentation =
                        "Path to the Maven settings file to scan for secret values. Encrypted credentials (Maven 4 "
                                + "{[...]base64} and legacy {base64}), any server password/keystore passphrase, the "
                                + "values of matching <profile><properties> entries and the credential part of "
                                + "matching server <httpHeaders> are treated as secrets and redacted on the wire. "
                                + "'~' expands to the user home.")
        String settings,

        @Property(
                defaultValue = "\"~/.m2/settings-security4.xml\"",
                documentation =
                        "Path to the Maven settings-security file (Maven 4: settings-security4.xml). Its master "
                                + "dispatcher config (source + cipher) lets a MavenSecretService decrypt the real value "
                                + "so the decrypted plaintext is also redacted; the encrypted ciphertext is always "
                                + "redacted regardless. Maven 3 legacy settings-security.xml is detected for the "
                                + "ciphertext, not decrypted.")
        String masterSecret,

        @Property(
                defaultValue = "java.util.List.of(\"gpg.keyname\")",
                documentation =
                        "Explicit <profile><properties> property keys whose value is always treated as a secret and "
                                + "redacted on the wire, whatever the key name looks like (e.g. \"gpg.keyname\" - a GPG "
                                + "key fingerprint stored as a build property). Keys are matched case-insensitively "
                                + "against the XML element name. Empty/absent uses this default.")
        List<String> profilePropertyNames,

        @Property(
                defaultValue = "java.util.List.of(\"PASSWORD\", \"PASSWD\", \"PASSPHRASE\", \"SECRET\", \"TOKEN\", "
                        + "\"APIKEY\", \"CREDENTIAL\", \"PRIVATEKEY\", \"KEY\", \"CIPHER\")",
                documentation =
                        "Secret tokens: a <profile><properties> property key (lower-cased, separators stripped) that "
                                + "contains any of these is treated as secret and its value redacted (e.g. "
                                + "\"gpg.passphrase\" contains PASSPHRASE; \"settings.password\" contains PASSWORD). "
                                + "Only the value is harvested; the key name itself is left alone. Empty/absent uses "
                                + "this default.")
        List<String> profilePropertyKeywords) {}
