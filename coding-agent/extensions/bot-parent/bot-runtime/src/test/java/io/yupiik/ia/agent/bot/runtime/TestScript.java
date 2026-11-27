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
package io.yupiik.ia.agent.bot.runtime;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** Shell fixtures whose executable inode is never opened for writing during tests. */
public final class TestScript {
    private TestScript() {}

    public static Path write(final Path executable, final String body) throws IOException {
        final Path launcher;
        try {
            launcher =
                    Path.of(TestScript.class.getResource("script-launcher.sh").toURI());
        } catch (final URISyntaxException e) {
            throw new IOException(e);
        }
        // Do not copy the launcher: another JVM fork could inherit the copy's writable descriptor.
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("r-x------"));
        Files.writeString(executable.resolveSibling(executable.getFileName() + ".body"), body);
        if (!Files.exists(executable, LinkOption.NOFOLLOW_LINKS)) {
            Files.createSymbolicLink(executable, launcher);
        }
        return executable;
    }
}
