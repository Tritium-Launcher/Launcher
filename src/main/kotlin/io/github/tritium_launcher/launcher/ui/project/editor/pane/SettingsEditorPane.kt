package io.github.tritium_launcher.launcher.ui.project.editor.pane

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPane
import io.github.tritium_launcher.launcher.ui.project.editor.EditorPaneProvider
import io.github.tritium_launcher.launcher.ui.settings.SettingsLink
import io.github.tritium_launcher.launcher.ui.settings.SettingsView
import io.qt.widgets.QWidget

/**
 * Settings editor pane for project-scoped settings files.
 *
 * @param project Active project context.
 * @param file Backing file used only for provider matching.
 * @see SettingsView
 * @see SettingsMngr
 */
class SettingsEditorPane(project: ProjectBase, file: VPath) : EditorPane(project, file) {
    private val view = SettingsView()

    /**
     * Returns the settings widget rendered by this editor pane.
     */
    override fun widget(): QWidget = view

    /**
     * Reloads category and setting rows when the pane is opened.
     */
    override fun onOpen() {
        view.reload()
    }

    /**
     * Opens [link] in this settings pane.
     *
     * @param link Target setting link.
     * @return `true` when the setting was found and focused.
     */
    fun openLink(link: SettingsLink): Boolean = view.openLink(link)

    /**
     * Persists all settings namespaces to disk.
     *
     * @return Always `true`.
     * @see SettingsMngr.persistAll
     */
    override suspend fun save(): Boolean {
        SettingsMngr.persistAll()
        return true
    }
}

/**
 * Provider that opens recognized settings files with [SettingsEditorPane].
 */
class SettingsEditorPaneProvider : EditorPaneProvider {
    override val id: String = "settings_editor"
    override val displayName: String = "Settings"
    override val order: Int = 5

    /**
     * Returns whether [file] should be opened by the settings editor.
     *
     * @param file Candidate file.
     * @param project Owning project.
     * @return `true` when [file] matches known settings file names and paths.
     */
    override fun canOpen(file: VPath, project: ProjectBase): Boolean = isSettingsFile(file)

    /**
     * Creates a new [SettingsEditorPane].
     *
     * @param project Active project.
     * @param file File being opened.
     * @return Settings editor pane instance.
     */
    override fun create(project: ProjectBase, file: VPath): EditorPane = SettingsEditorPane(project, file)

    /**
     * Checks whether [file] looks like a settings file in `.tr`/`.tritium` directories.
     *
     * @param file Candidate file path.
     * @return `true` when the file name/path pattern matches settings locations.
     */
    private fun isSettingsFile(file: VPath): Boolean {
        val name = file.fileName().lowercase()
        if (!name.matches("settings.conf", "settings.hocon", "settings.json", "settings.toml")) return false
        val parent = file.parent()
        val parentName = parent.fileName().lowercase()
        if (parentName.matches(".tr", ".tritium")) return true
        val grand = parent.parent()
        val grandName = grand.fileName().lowercase()
        return grandName.matches(".tr", ".tritium")
    }
}
