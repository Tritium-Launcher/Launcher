/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin

import io.github.tritium_launcher.api.file.SyntaxLanguage
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.launcher.matches

class JavaScriptLanguage : SyntaxLanguage {
    override val id: String = "javascript"
    override val displayName: String = "JavaScript"

    override fun matches(file: VPath): Boolean = file.extension().matches("js")
}
