/*
 * Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.yupiik.ia.agent.core.tools;

/**
 * Identity supplied by an integration adapter, never inferred from a model-visible tool name or category.
 * Implementations are trusted JVM code, not a security boundary against arbitrary Java extensions.
 * The identity must describe the integration actually invoked, not caller-supplied tool arguments.
 */
public interface SandboxIntegration extends EmbeddedTool {
    /** Stable, exact trust configuration key (for example {@code mcp:my-server}). */
    String sandboxIntegrationId();
}
