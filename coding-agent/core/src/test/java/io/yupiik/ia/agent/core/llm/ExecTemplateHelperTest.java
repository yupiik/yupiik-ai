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
package io.yupiik.ia.agent.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.handlebars.HandlebarsCompiler;
import io.yupiik.fusion.framework.handlebars.helper.HelperContext;
import io.yupiik.fusion.framework.handlebars.spi.Template;
import java.io.File;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@code {{exec}}} handlebars helper (core-side, used by provider
 * {@code dynamicHeaders}): environment passing, stripping, TTL caching semantics and failure
 * surfacing. The wire delivery of a rendered dynamic header through the llm-client customizer
 * is covered by the {@code ChatClient} tests.
 */
class ExecTemplateHelperTest {

    @Test
    void envPassingAndStrip() {
        // the env override wins and strip=true trims the trailing newline
        assertEquals("world", render("{{exec \"MY_ENV=world\" \"sh\" \"-c\" \"printf %s \\\"$MY_ENV\\\"\"}}"));
    }

    @Test
    void stripFalseKeepsWhitespace() {
        // a real LF embedded in the template source passes through the quoted arg verbatim
        assertEquals("a\nb\n", render("{{exec \"exec_options=strip=false\" \"printf\" \"a\nb\n\"}}"));
    }

    @Test
    void multiArgCommand() {
        assertEquals("a-b", render("{{exec \"printf\" \"%s-%s\" \"a\" \"b\"}}"));
    }

    @Test
    void ttlCachesAcrossRenders() throws Exception {
        final var marker = "exec-ttl-" + System.nanoTime() + ".tmp";
        final var tf = File.createTempFile(marker, ".txt");
        try {
            final var cmd = "printf x >> " + tf.getAbsolutePath() + " && wc -c < " + tf.getAbsolutePath();
            final var template = compile("{{exec \"exec_options=ttl=60\" \"sh\" \"-c\" \"" + cmd + "\"}}");
            assertEquals("1", template.render(Map.of()).strip());
            assertEquals("1", template.render(Map.of()).strip(), "the second render must hit the TTL cache");
        } finally {
            tf.delete();
        }
    }

    @Test
    void ttlExpiryReSpawns() throws Exception {
        final var marker = "exec-ttl-exp-" + System.nanoTime() + ".tmp";
        final var tf = File.createTempFile(marker, ".txt");
        try {
            final var cmd = "printf x >> " + tf.getAbsolutePath() + " && wc -c < " + tf.getAbsolutePath();
            final var helper = new ExecTemplateHelper();
            final var template = compile("{{exec \"exec_options=ttl=60\" \"sh\" \"-c\" \"" + cmd + "\"}}", helper);
            assertEquals("1", template.render(Map.of()).strip());
            assertEquals("1", template.render(Map.of()).strip(), "the entry must be cached before expiry");
            // No injectable clock: age the cached entry without waiting on wall time or clearing the cache.
            final var cacheField = ExecTemplateHelper.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            final var cache = (Map<String, Object>) cacheField.get(helper);
            assertEquals(1, cache.size());
            final var entry = cache.entrySet().iterator().next();
            final var cachedType = entry.getValue().getClass();
            final var output = cachedType.getDeclaredMethod("output");
            output.setAccessible(true);
            final var constructor = cachedType.getDeclaredConstructor(String.class, long.class);
            constructor.setAccessible(true);
            entry.setValue(constructor.newInstance(output.invoke(entry.getValue()), System.nanoTime() - 1));
            assertEquals("2", template.render(Map.of()).strip(), "after expiry the command must re-run");
            assertEquals("2", template.render(Map.of()).strip(), "the refreshed entry must be cached again");
        } finally {
            tf.delete();
        }
    }

    @Test
    void ttlZeroDisablesCaching() throws Exception {
        final var marker = "exec-ttl-0-" + System.nanoTime() + ".tmp";
        final var tf = File.createTempFile(marker, ".txt");
        try {
            final var cmd = "printf x >> " + tf.getAbsolutePath() + " && wc -c < " + tf.getAbsolutePath();
            final var template = compile("{{exec \"sh\" \"-c\" \"" + cmd + "\"}}");
            assertEquals("1", template.render(Map.of()).strip());
            assertEquals("2", template.render(Map.of()).strip(), "no ttl means every render re-executes");
        } finally {
            tf.delete();
        }
    }

    @Test
    void nonzeroExitFailsTheRender() {
        final var e = assertThrows(RuntimeException.class, () -> render("{{exec \"sh\" \"-c\" \"exit 3\"}}"));
        assertTrue(e.getMessage().contains("3"), e.getMessage());
    }

    @Test
    void missingCommandFails() {
        assertThrows(IllegalArgumentException.class, () -> render("{{exec}}"));
        assertThrows(IllegalArgumentException.class, () -> render("{{exec \"A=B\"}}"));
    }

    private static String render(final String source) {
        return compile(source).render(Map.of());
    }

    private static Template compile(final String source) {
        return compile(source, new ExecTemplateHelper());
    }

    private static Template compile(final String source, final ExecTemplateHelper helper) {
        final Function<HelperContext, Object> exec = helper.exec();
        return new HandlebarsCompiler()
                .compile(new HandlebarsCompiler.CompilationContext(
                        new HandlebarsCompiler.Settings().helpers(Map.of("exec", exec)), source));
    }
}
