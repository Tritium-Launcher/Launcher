package io.github.tritium_launcher.launcher.extension

import io.qt.gui.QIcon
import org.koin.core.module.Module

/**
 * Implement this to create an Extension loaded on startup.
 *
 * Extensions can be used to add features not available in Tritium natively, such as:
 *
 * Mod Loaders
 *
 * Project Types
 *
 * 3rd Party Service Integration
 *
 * File Types
 *
 * Language Support
 *
 * Editor Panes
 * @see io.github.tritium_launcher.launcher.extension.core.CoreExtension
 * @see ExtensionLoader
 * @see ExtensionDirectoryLoader
 * @see io.github.tritium_launcher.launcher.registry.Registry
 *
 * @see io.github.tritium_launcher.launcher.core.modloader.ModLoader
 * @see io.github.tritium_launcher.launcher.core.project.ProjectType
 * @see io.github.tritium_launcher.launcher.accounts.AccountProvider
 * @see io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
 * @see io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxLanguage
 * @see io.github.tritium_launcher.launcher.ui.project.editor.EditorPane
 */
interface Extension {
    val namespace: String
    val modules: List<Module>

    val isBuiltin: Boolean get() = false
    val requiresRestart: Boolean get() = true
    val displayName: String get() = namespace
    val description: String? get() = null
    val icon: QIcon? get() = null
}