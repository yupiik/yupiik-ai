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
package io.yupiik.ia.agent.tui;

import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;

/**
 * The key binding presets exposed by the TUI, each mapping one-to-one to the
 * predefined {@link BindingSets} factories. The default is {@link #STANDARD}
 * which only binds navigation to the arrow keys and stays safe for text input.
 */
public enum KeyBindingPreset {
    STANDARD,
    VIM,
    EMACS,
    INTELLIJ,
    VSCODE;

    /**
     * @return the library bindings for this preset ({@link BindingSets})
     */
    public Bindings toBindings() {
        return switch (this) {
            case STANDARD -> BindingSets.standard();
            case VIM -> BindingSets.vim();
            case EMACS -> BindingSets.emacs();
            case INTELLIJ -> BindingSets.intellij();
            case VSCODE -> BindingSets.vscode();
        };
    }
}
