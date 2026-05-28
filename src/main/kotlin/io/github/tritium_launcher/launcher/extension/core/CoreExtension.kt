package io.github.tritium_launcher.launcher.extension.core

import io.github.tritium_launcher.launcher.accounts.ui.MicrosoftAccountProvider
import io.github.tritium_launcher.launcher.applyRainbowOverlay
import io.github.tritium_launcher.launcher.core.mod_config.ConfigFormat
import io.github.tritium_launcher.launcher.core.modloader.Fabric
import io.github.tritium_launcher.launcher.core.modloader.NeoForge
import io.github.tritium_launcher.launcher.core.project.ModpackProjectType
import io.github.tritium_launcher.launcher.core.project.ModpackTemplateDescriptor
import io.github.tritium_launcher.launcher.core.project.templates.TemplateRegistry
import io.github.tritium_launcher.launcher.core.project.templates.generation.license.*
import io.github.tritium_launcher.launcher.core.source.CurseForge
import io.github.tritium_launcher.launcher.core.source.Modrinth
import io.github.tritium_launcher.launcher.extension.Extension
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.settings.CategoryPath
import io.github.tritium_launcher.launcher.settings.NamespacedId
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.dashboard.DvdStyleProvider
import io.github.tritium_launcher.launcher.ui.dashboard.ExtensionsManageList
import io.github.tritium_launcher.launcher.ui.dashboard.GridStyleProvider
import io.github.tritium_launcher.launcher.ui.dashboard.ListStyleProvider
import io.github.tritium_launcher.launcher.ui.project.editor.file.builtin.BuiltinFileTypes
import io.github.tritium_launcher.launcher.ui.project.editor.panes.ImageViewerProvider
import io.github.tritium_launcher.launcher.ui.project.editor.panes.ModBrowserPaneProvider
import io.github.tritium_launcher.launcher.ui.project.editor.panes.ModConfigPane
import io.github.tritium_launcher.launcher.ui.project.editor.panes.SettingsEditorPaneProvider
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin.JsonLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin.PythonLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin.XmlLanguage
import io.github.tritium_launcher.launcher.ui.project.menu.builtin.BuiltinMenuItems
import io.github.tritium_launcher.launcher.ui.project.sidebar.*
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.util.SeasonalEvents
import io.qt.gui.QIcon
import io.qt.widgets.QApplication
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Core extension that registers Tritium's base content and features.
 */
internal object CoreExtension : Extension {
    private val coreModule = module {
        single(createdAtStart = true) {
            val settings: SettingsMngr = get()

            val modLoaders        = BuiltinRegistries.ModLoader
            val modSources        = BuiltinRegistries.ModSource
            val projectTypes      = BuiltinRegistries.ProjectType
            val licenses          = BuiltinRegistries.License
            val accountProviders  = BuiltinRegistries.AccountProvider
            val fileTypes         = BuiltinRegistries.FileType
            val sidePanels        = BuiltinRegistries.SidePanel
            val menuItems         = BuiltinRegistries.MenuItem
            val notifications     = BuiltinRegistries.Notification
            val syntax            = BuiltinRegistries.SyntaxLanguage
            val editorPanes       = BuiltinRegistries.EditorPane
            val projectListStyles = BuiltinRegistries.ProjectListStyle
            val projectTreeDirectoryPresentations = BuiltinRegistries.ProjectTreeDirectoryPresentation
            val projectFilesViewModes = BuiltinRegistries.ProjectFilesViewMode
            val projectFilesActions = BuiltinRegistries.ProjectFilesAction
            val configFormats     = BuiltinRegistries.ConfigFormat
            val rootDirectories   = BuiltinRegistries.ProjectRootDirectory

            settings.register(this@CoreExtension.namespace, CoreSettings.registration)

            settings.forNamespace("tritium").widget(
                CategoryPath.root(NamespacedId("tritium", "extensions")),
                "manage"
            ) {
                title = "Manage Extensions"
                description = "Enable or disable extensions. Changes take effect after restart."
                fullWidth = true
                fullHeight = true
                serializer = null
                defaultValue = Unit
                widgetFactory = { ExtensionsManageList() }
            }

            accountProviders.register(MicrosoftAccountProvider())

            modLoaders.register(Fabric())
            modLoaders.register(NeoForge())

            modSources.register(Modrinth())
            modSources.register(CurseForge())

            projectTypes.register(ModpackProjectType())

            licenses.register(listOf(
                NoLicense(),
                MITLicense(),
                Apache2License(),
                Gpl3License(),
                Gpl2License(),
                Gpl21LesserLicense(),
                Bsd2License(),
                Bsd3License(),
                ISCLicense(),
                MPL2License(),
                Unlicense(),
                AllRightsReservedLicense()
            ))

            fileTypes.register(BuiltinFileTypes.all())

            sidePanels.register(ProjectFilesSidePanelProvider())
            sidePanels.register(ProjectModpackSidePanelProvider())
            sidePanels.register(ProjectInstalledModsSidePanelProvider())
            sidePanels.register(ProjectRegistryBrowserSidePanelProvider())
            sidePanels.register(ProjectLogsSidePanelProvider())
            sidePanels.register(ProjectNotificationsSidePanelProvider())

            menuItems.register(BuiltinMenuItems.All)
            notifications.register(BuiltinNotifications.All)

            syntax.register(listOf(
                JsonLanguage(),
                PythonLanguage(),
                XmlLanguage()
            ))

            TemplateRegistry.register(ModpackTemplateDescriptor)

            editorPanes.register(ImageViewerProvider())
            editorPanes.register(ModBrowserPaneProvider())
            editorPanes.register(SettingsEditorPaneProvider())
            editorPanes.register(ModConfigPane.Provider)

            projectListStyles.register(listOf(GridStyleProvider, ListStyleProvider, DvdStyleProvider))
            projectTreeDirectoryPresentations.register(ProjectTreeDirectoryPresentations.all())
            projectFilesViewModes.register(ProjectFilesViewModes.all())

            rootDirectories.register(listOf(
                projectRootDirectory("config", "config", "Configs"),
                projectRootDirectory("defaultconfigs", "defaultconfigs", "Default Configs"),
                projectRootDirectory("mods", "mods", "Mods"),
                projectRootDirectory("resourcepacks", "resourcepacks", "Resource Packs"),
                projectRootDirectory("shaderpacks", "shaderpacks", "Shader Packs"),
                projectRootDirectory("scripts", "scripts", "Scripts")
            ))

            projectFilesActions.register(listOf(
                ProjectFilesContextAction.create(
                    id = "cut",
                    displayName = "Cut",
                    order = 0,
                    section = ProjectFilesContextAction.Section.CLIPBOARD,
                    execute = { _, _, tree ->
                        setClipboard(selectedPaths(tree).map { it.toAbsolute().toString() }, true)
                    }
                ),
                ProjectFilesContextAction.create(
                    id = "copy",
                    displayName = "Copy",
                    order = 1,
                    section = ProjectFilesContextAction.Section.CLIPBOARD,
                    execute = { _, _, tree ->
                        setClipboard(selectedPaths(tree).map { it.toAbsolute().toString() }, false)
                    }
                ),
                ProjectFilesContextAction.create(
                    id = "paste",
                    displayName = "Paste",
                    order = 2,
                    section = ProjectFilesContextAction.Section.CLIPBOARD,
                    matches = { _, _, _, _ -> clipboardSource().isNotEmpty() },
                    needsRefresh = true,
                    execute = { path, _, _ ->
                        val isDir = runCatching { path.isDir() }.getOrDefault(false)
                        val targetDir = if (isDir) path else runCatching { path.parent() }.getOrNull() ?: path
                        pasteTo(targetDir)
                    }
                ),
                ProjectFilesContextAction.create(
                    id = "rename",
                    displayName = "Rename",
                    order = 0,
                    section = ProjectFilesContextAction.Section.RENAME,
                    needsRefresh = true,
                    execute = { path, _, tree ->
                        promptRename(path, tree)
                    }
                ),
                ProjectFilesContextAction.create(
                    id = "delete",
                    displayName = "Delete",
                    order = 0,
                    section = ProjectFilesContextAction.Section.DELETE,
                    needsRefresh = true,
                    execute = { path, _, tree ->
                        promptDelete(path, tree)
                    }
                ),
                ProjectFilesContextAction.create(
                    id = "reload_from_disk",
                    displayName = "Reload from Disk",
                    order = 0,
                    section = ProjectFilesContextAction.Section.RELOAD,
                    needsRefresh = true,
                    execute = { _, _, _ -> }
                ),
                ProjectFilesContextAction.create(
                    id = "copy_path",
                    displayName = "Copy Path",
                    order = 0,
                    section = ProjectFilesContextAction.Section.EXTENSIONS,
                    execute = { path, _, _ ->
                        QApplication.clipboard()?.setText(path.toAbsolute().toString())
                    }
                ),
                ProjectFilesContextAction.create(
                    id = "copy_relative_path",
                    displayName = "Copy Relative Path",
                    order = 1,
                    section = ProjectFilesContextAction.Section.EXTENSIONS,
                    execute = { path, project, _ ->
                        val rel = path.toAbsolute().toString().removePrefix(project.projectDir.toAbsolute().toString().trimEnd('/')).trimStart('/')
                        QApplication.clipboard()?.setText(rel)
                    }
                ),
                ProjectFilesContextAction.create(
                    id = "open_in_file_manager",
                    displayName = "Open in File Manager",
                    order = 2,
                    section = ProjectFilesContextAction.Section.EXTENSIONS,
                    execute = { path, _, _ ->
                        val file = runCatching { path.toJFile() }.getOrNull()
                        if (file != null) {
                            val parent = file.parentFile ?: file
                            runCatching { Platform.openFile(parent) }
                        }
                    }
                )
            ))

            configFormats.register(ConfigFormat.builtin)
        }
    }

    override val namespace: String = "tritium"
    override val isBuiltin: Boolean = true
    override val requiresRestart: Boolean = false
    override val displayName: String = "Core"
    override val description: String = "Core Tritium features"
    override val icon: QIcon get() = if (SeasonalEvents.isPrideMonth()) TIcons.TritiumGrayscale.applyRainbowOverlay(opacity = 0.5f).icon else TIcons.Tritium.icon

    override val modules: List<Module> = listOf(coreModule)
}
