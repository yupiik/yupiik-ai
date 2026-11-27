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
package io.yupiik.maven.knowledge.process;

/**
 * Unchecked wrapper for a spawned-process failure (start refusal, interrupted wait, or a
 * non-zero exit), so the process lifecycle API stays runtime-exception based like the rest of
 * the knowledge layer.
 */
public class ProcessExecutionException extends RuntimeException {
    public ProcessExecutionException(final String message) {
        super(message);
    }

    public ProcessExecutionException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
