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
package io.yupiik.ia.agent.core.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

/** Deterministic subprocess used to exercise transport failure boundaries. */
public class AdversarialMcpServer {
    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    public static void main(final String[] args) throws Exception {
        final var mode = args[0];
        final var directory = Path.of(args[1]);
        Files.writeString(
                directory.resolve("pid"), Long.toString(ProcessHandle.current().pid()));
        try (final var signal = "eof".equals(mode) || "malformed".equals(mode)
                        ? FileSystems.getDefault().newWatchService()
                        : null;
                final var reader = new BufferedReader(new InputStreamReader(System.in))) {
            if (signal != null) {
                directory.register(signal, StandardWatchEventKinds.ENTRY_CREATE);
            }
            String line;
            int pages = 0;
            while ((line = reader.readLine()) != null) {
                if ("wire".equals(mode) && (line.contains("\"result\":") || line.contains("\"error\":"))) {
                    Files.writeString(
                            directory.resolve("client-replies.jsonl"),
                            line + '\n',
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                    continue;
                }
                final var matcher = ID.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                final var id = matcher.group(1);
                if ("wire".equals(mode) && !line.contains("\"initialize\"")) {
                    final var reply =
                            Files.readString(directory.resolve("reply.jsonl")).replace("$id", id);
                    System.out.print(reply);
                    if (!reply.endsWith("\n")) {
                        System.out.println();
                    }
                    System.out.flush();
                    continue;
                }
                final String result;
                if (line.contains("\"initialize\"")) {
                    if ("silent-initialize".equals(mode)) {
                        new CountDownLatch(1).await();
                    }
                    result =
                            "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}";
                } else if (line.contains("tools/list")) {
                    if ("slow-pages".equals(mode)) {
                        Thread.sleep(650);
                    }
                    result = "pages".equals(mode) || "slow-pages".equals(mode)
                            ? "{\"tools\":[],\"nextCursor\":\"page" + (++pages) + "\"}"
                            : "{\"tools\":[]}";
                } else {
                    if ("delayed-call".equals(mode)) {
                        Thread.sleep(1500);
                    }
                    result = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}";
                }
                System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
                System.out.flush();
                if (line.contains("tools/list")
                        && !"pages".equals(mode)
                        && !"slow-pages".equals(mode)
                        && !"delayed-call".equals(mode)) {
                    Files.writeString(directory.resolve("ready"), "ready");
                    if (!"blocked".equals(mode)) {
                        while (!Files.exists(directory.resolve("terminate-reader"))) {
                            final var key = signal.take();
                            key.pollEvents();
                            key.reset();
                        }
                        if ("malformed".equals(mode)) {
                            System.out.println("{broken");
                            System.out.flush();
                        } else {
                            System.out.close();
                        }
                    }
                    new CountDownLatch(1).await(); // The transport must kill this deliberately idle child.
                    return;
                }
            }
        }
    }
}
