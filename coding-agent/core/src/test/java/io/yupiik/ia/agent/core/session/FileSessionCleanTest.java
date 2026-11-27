/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.yupiik.ia.agent.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.container.FusionBean;
import io.yupiik.fusion.framework.api.container.FusionListener;
import io.yupiik.fusion.framework.api.container.FusionModule;
import io.yupiik.fusion.framework.api.container.bean.ProvidedInstanceBean;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.ia.agent.core.configuration.CodingAgentConfiguration;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSessionCleanTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T12:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path home;

    @Test
    void conjunctiveStrictThresholdsAndProtection() throws Exception {
        final var store = store();
        final var expired = session(store, 4, 101);
        final var small = session(store, 4, 99);
        final var young = session(store, 2, 101);
        final var ageBoundary = session(store, 3, 101);
        final var sizeBoundary = session(store, 4, 100);
        final var recent = session(store, 0, 101);
        final var resolved = session(store, 10, 101);
        store.keepSession(resolved);

        final var result = store.clean(new SessionCleanPolicy(Duration.ofDays(3), 100L), false);

        assertEquals(
                List.of(expired),
                result.sessions().stream().map(SessionStore.SessionInfo::id).toList());
        assertEquals(101, result.bytes());
        assertEquals(4, result.kept());
        assertEquals(2, result.protectedSessions());
        assertFalse(Files.exists(store.sessionDir(expired)));
        for (final var id : List.of(small, young, ageBoundary, sizeBoundary, recent, resolved)) {
            assertTrue(Files.exists(store.path(id)), id);
        }
        assertEquals(
                0,
                store.clean(new SessionCleanPolicy(Duration.ofDays(3), 100L), false)
                        .sessions()
                        .size());
    }

    @Test
    void dryRunDoesNotDeleteCompressOrCreateFiles() throws Exception {
        final var store = store();
        final var id = session(store, 40, 120);
        final var before = Files.readAllBytes(store.path(id));
        final var timestamp = Files.getLastModifiedTime(store.path(id));
        final var report = store.clean(new SessionCleanPolicy(Duration.ofDays(3), null), true);
        assertEquals(1, report.sessions().size());
        assertEquals(120, report.bytes());
        assertEquals(timestamp, Files.getLastModifiedTime(store.path(id)));
        assertEquals(new String(before), Files.readString(store.path(id)));
        try (final var files = Files.list(store.sessionDir(id))) {
            assertEquals(List.of(store.path(id)), files.toList());
        }
    }

    @Test
    void sizeIncludesArtifactsAndCompressedBytesWithoutReadingTranscript() throws Exception {
        final var store = store();
        final var id = session(store, 5, 20);
        final var gz = Path.of(store.path(id) + ".gz");
        Files.move(store.path(id), gz); // Deliberately not valid gzip: cleanup must never decompress it.
        Files.createDirectories(store.sessionDir(id).resolve("nested"));
        Files.writeString(store.sessionDir(id).resolve("nested/artifact"), "a".repeat(90));
        final var result = store.clean(new SessionCleanPolicy(null, 100L), false);
        assertEquals(110, result.bytes());
        assertEquals(1, result.sessions().size());
        assertFalse(Files.exists(store.sessionDir(id)));
    }

    @Test
    void legacySidecarsAndDuplicateLayoutsAreCountedAndDeletedOnce() throws Exception {
        final var store = store();
        final var id = session(store, 5, 10);
        writeAged(store.dir().resolve(id + ".jsonl"), 5, 20);
        writeAged(store.dir().resolve(id + ".jsonl.gz"), 5, 30);
        writeAged(store.dir().resolve(id + ".jsonl.bak"), 5, 40);
        writeAged(home.resolve("todos-" + id + ".json"), 5, 50);
        writeAged(home.resolve("snapshots/" + id + ".snap"), 5, 60);
        writeAged(home.resolve("plans/title-" + id + ".plan.md"), 5, 70);
        Files.writeString(home.resolve("todos.json"), "shared");
        Files.writeString(home.resolve("plans/unrelated.plan.md"), "unrelated");
        final var result = store.clean(new SessionCleanPolicy(null, 1L), false);
        assertEquals(1, result.sessions().size());
        assertEquals(280, result.bytes());
        assertFalse(Files.exists(store.sessionDir(id)));
        assertFalse(Files.exists(home.resolve("todos-" + id + ".json")));
        assertFalse(Files.exists(home.resolve("snapshots/" + id + ".snap")));
        assertFalse(Files.exists(home.resolve("plans/title-" + id + ".plan.md")));
        try (final var files = Files.list(store.dir())) {
            assertEquals(0, files.count());
        }
        assertEquals("shared", Files.readString(home.resolve("todos.json")));
        assertEquals("unrelated", Files.readString(home.resolve("plans/unrelated.plan.md")));
    }

    @Test
    void flatOnlySessionsAreSupportedAndRecentDuplicateProtectsWholeSession() throws Exception {
        final var store = store();
        final var old = store.dir().resolve("s123.jsonl.gz");
        writeAged(old, 20, 50);
        final var backup = store.dir().resolve("s123.jsonl.bak");
        writeAged(backup, 20, 70);
        final var duplicate = session(store, 20, 50);
        writeAged(store.dir().resolve(duplicate + ".jsonl"), 0, 50);
        final var result = store.clean(new SessionCleanPolicy(Duration.ofDays(3), null), false);
        assertEquals(
                List.of("s123"),
                result.sessions().stream().map(SessionStore.SessionInfo::id).toList());
        assertEquals(1, result.protectedSessions());
        assertEquals(120, result.bytes());
        assertTrue(Files.exists(store.path(duplicate)));
        assertFalse(Files.exists(old));
        assertFalse(Files.exists(backup));
    }

    @Test
    void symlinksAreNeitherFollowedNorCounted() throws Exception {
        final var store = store();
        final var id = session(store, 5, 20);
        final var outside = home.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("precious"), "preserve".repeat(100));
        Files.createSymbolicLink(store.sessionDir(id).resolve("external"), outside);
        Files.createSymbolicLink(store.dir().resolve("sabc"), outside);
        Files.createSymbolicLink(store.dir().resolve("sdef.jsonl"), outside.resolve("precious"));
        final var report = store.clean(new SessionCleanPolicy(null, 100L), true);
        assertEquals(0, report.sessions().size());
        final var result = store.clean(new SessionCleanPolicy(null, 1L), false);
        assertEquals(20, result.bytes());
        assertEquals(1, result.sessions().size());
        assertFalse(Files.exists(store.sessionDir(id), LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.isSymbolicLink(store.dir().resolve("sabc")));
        assertTrue(Files.isSymbolicLink(store.dir().resolve("sdef.jsonl")));
        assertEquals("preserve".repeat(100), Files.readString(outside.resolve("precious")));
    }

    @Test
    void genericDeletionAlsoDoesNotFollowDirectorySymlinks() throws Exception {
        final var store = store();
        final var id = session(store, 5, 20);
        final var outside = home.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("precious"), "preserve");
        Files.createSymbolicLink(store.sessionDir(id).resolve("external"), outside);
        store.delete(id);
        assertEquals("preserve", Files.readString(outside.resolve("precious")));
        assertFalse(Files.exists(store.sessionDir(id)));
    }

    @Test
    void ambiguousFolderSymlinkDoesNotDeleteItsLegacyTranscript() throws Exception {
        final var store = store();
        writeAged(store.dir().resolve("s123.jsonl"), 20, 50);
        final var external = home.resolve("external");
        Files.createDirectories(external);
        Files.createSymbolicLink(store.dir().resolve("s123"), external);
        assertEquals(
                0,
                store.clean(new SessionCleanPolicy(null, 1L), false).sessions().size());
        assertTrue(Files.exists(store.dir().resolve("s123.jsonl")));
    }

    @Test
    void disabledOrPristineStorageIsNotCreated() {
        final var store = store();
        assertEquals(
                0,
                store.clean(new SessionCleanPolicy(null, null), false)
                        .sessions()
                        .size());
        assertEquals(
                0,
                store.clean(new SessionCleanPolicy(null, 1L), false).sessions().size());
        assertFalse(Files.exists(store.dir()));
    }

    @Test
    void unsafeOrUnreadableStorageFailsRatherThanReportingSuccess() throws Exception {
        final var store = store();
        Files.createDirectories(home);
        Files.writeString(store.dir(), "not a directory");
        assertThrows(UncheckedIOException.class, () -> store.clean(new SessionCleanPolicy(null, 1L), false));
        Files.delete(store.dir());
        final var external = home.resolve("external");
        Files.createDirectories(external);
        Files.createSymbolicLink(store.dir(), external);
        assertThrows(UncheckedIOException.class, () -> store.clean(new SessionCleanPolicy(null, 1L), false));
    }

    private FileSessionStore store() {
        try (final var container = ConfiguringContainer.of()
                        .register(new StoreModule(home))
                        .start();
                final var configuration = container.lookup(CodingAgentConfiguration.class)) {
            return new FileSessionStore(configuration.instance(), null, CLOCK);
        }
    }

    private static String session(final FileSessionStore store, final int ageDays, final int bytes) throws Exception {
        final var id = store.create();
        writeAged(store.path(id), ageDays, bytes);
        return id;
    }

    private static void writeAged(final Path file, final int ageDays, final int bytes) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "a".repeat(bytes));
        Files.setLastModifiedTime(file, FileTime.from(CLOCK.instant().minus(Duration.ofDays(ageDays))));
    }

    private static class StoreModule implements FusionModule {
        private final Map<String, String> values;

        private StoreModule(final Path home) {
            values = Map.of("yca.storageHome", home.toString(), "yca.sessions.startupCleanup", "false");
        }

        @Override
        public Stream<FusionBean<?>> beans() {
            return Stream.of(
                    new ProvidedInstanceBean<>(DefaultScoped.class, ConfigurationSource.class, () -> values::get));
        }

        @Override
        public BiPredicate<RuntimeContainer, FusionBean<?>> beanFilter() {
            return (container, bean) -> bean instanceof ProvidedInstanceBean<?>
                    || !(bean.type() instanceof Class<?> type)
                    || !ConfigurationSource.class.isAssignableFrom(type);
        }

        @Override
        public BiPredicate<RuntimeContainer, FusionListener<?>> listenerFilter() {
            return (container, listener) -> false;
        }
    }
}
