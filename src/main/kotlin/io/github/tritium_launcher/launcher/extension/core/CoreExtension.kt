/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.extension.core

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.core.TritiumEvent
import io.github.tritium_launcher.api.core.onEvent
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.docks.*
import io.github.tritium_launcher.api.extension.Extension
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.io.VWatchEvent
import io.github.tritium_launcher.api.platform.Platform
import io.github.tritium_launcher.api.settings.CategoryPath
import io.github.tritium_launcher.api.settings.NamespacedId
import io.github.tritium_launcher.launcher.accounts.CurseForgeAccount
import io.github.tritium_launcher.launcher.accounts.MicrosoftAccountProvider
import io.github.tritium_launcher.launcher.accounts.ModrinthAccount
import io.github.tritium_launcher.launcher.companion.CompanionModSource
import io.github.tritium_launcher.launcher.core.mod_config.BuiltinConfigFormats
import io.github.tritium_launcher.launcher.core.modloader.Fabric
import io.github.tritium_launcher.launcher.core.modloader.NeoForge
import io.github.tritium_launcher.launcher.core.project.ModpackProjectType
import io.github.tritium_launcher.launcher.core.project.ModpackTemplateDescriptor
import io.github.tritium_launcher.launcher.core.project.templates.generation.builtin.BuiltinStepRegistrar
import io.github.tritium_launcher.launcher.core.project.templates.generation.license.*
import io.github.tritium_launcher.launcher.core.source.CurseForge
import io.github.tritium_launcher.launcher.core.source.Modrinth
import io.github.tritium_launcher.launcher.search.*
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.dashboard.DvdStyleProvider
import io.github.tritium_launcher.launcher.ui.dashboard.ExtensionsManageList
import io.github.tritium_launcher.launcher.ui.dashboard.GridStyleProvider
import io.github.tritium_launcher.launcher.ui.dashboard.ListStyleProvider
import io.github.tritium_launcher.launcher.ui.project.editor.file.builtin.BuiltinFileTypes
import io.github.tritium_launcher.launcher.ui.project.editor.inspection.registerJsInspections
import io.github.tritium_launcher.launcher.ui.project.editor.panes.*
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin.JavaScriptLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin.JsonLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin.PythonLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin.XmlLanguage
import io.github.tritium_launcher.launcher.ui.project.menu.builtin.BuiltinMenuItems
import io.github.tritium_launcher.launcher.ui.project.sidebar.*
import io.github.tritium_launcher.launcher.ui.theme.TColors
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.dominantColor
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.gui.QColor
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import io.qt.widgets.QApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Core extension that registers Tritium's base content and features.
 */
private val _json = Json { ignoreUnknownKeys = true }

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
            val directoryMarks    = BuiltinRegistries.ProjectDirectoryMark
            val treeRoots         = BuiltinRegistries.ProjectTreeRoot

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
            accountProviders.register(ModrinthAccount())
            accountProviders.register(CurseForgeAccount())

            modLoaders.register(Fabric())
            modLoaders.register(NeoForge())

            modSources.register(Modrinth())
            modSources.register(CurseForge())
            modSources.register(CompanionModSource())

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

            sidePanels.register(ProjectFilesDockPanelProvider())
            sidePanels.register(ProjectModpackDockPanelProvider())
            sidePanels.register(ProjectInstalledModsDockPanelProvider())
            sidePanels.register(RegistryBrowserDockPanel())
            sidePanels.register(ProjectConsoleDockPanelProvider())
            sidePanels.register(NotificationsDockPanel())
            sidePanels.register(ModBrowserDockPanelProvider())
            sidePanels.register(ItemInspectorDockPanelProvider())
            sidePanels.register(RecipeBuilderDockPanel())

            menuItems.register(BuiltinMenuItems.All)
            notifications.register(BuiltinNotifications.All)

            syntax.register(listOf(
                JsonLanguage(),
                PythonLanguage(),
                XmlLanguage(),
                JavaScriptLanguage()
            ))

            registerJsInspections()

            BuiltinRegistries.Template.register(ModpackTemplateDescriptor)
            BuiltinStepRegistrar.registerBuiltinSteps()

            editorPanes.register(ImageViewerProvider())
            editorPanes.register(ModDetailPaneProvider)
            editorPanes.register(SettingsEditorPaneProvider())
            editorPanes.register(ModConfigPane.Provider)
            editorPanes.register(OptionsTxtPane.Provider)
            editorPanes.register(SvgEditorPane.Provider)

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

            treeRoots.register(listOf(
                object : ProjectTreeRoot {
                    override val id: String = "project_root"
                    override val order: Int = 0

                    override fun displayName(project: ProjectBase): String = project.name
                    override fun rootPath(project: ProjectBase): VPath = project.projectDir
                    override fun icon(project: ProjectBase): QIcon = TIcons.ProjNode.icon
                    override fun iconColor(project: ProjectBase): QColor? {
                        val iconPath = project.getIconPath()
                        if (iconPath.isBlank()) return null
                        val px = runCatching { QPixmap(iconPath) }.getOrNull()
                        if (px == null || px.isNull) return null
                        return px.toImage().dominantColor()
                    }

                    override fun childEntries(project: ProjectBase, root: VPath): List<ProjectFilesNodeSpec> =
                        runCatching { root.list() }
                            .getOrDefault(emptyList())
                            .filterNot { it.fileName().startsWith('.') }
                            .map { ProjectFilesNodeSpec(it) }
                },
                object : ProjectTreeRoot {
                    override val id: String = "game_data"
                    override val order: Int = 100

                    private fun snapshotDir(project: ProjectBase): VPath? {
                        val root = rootPath(project)
                        val latestFile = root.resolve("latest.json")
                        if(!latestFile.exists()) return null
                        return runCatching {
                            val jsonRoot = _json.parseToJsonElement(latestFile.readText()).jsonObject
                            val snapPath = jsonRoot["path"]?.jsonPrimitive?.content ?: return@runCatching null
                            root.resolve(snapPath).takeIf { it.isDir() }
                        }.getOrNull()
                    }

                    override fun normalizePath(
                        project: ProjectBase,
                        path: VPath
                    ): String {
                        val snap = snapshotDir(project)?.toAbsoluteString() ?: return path.toAbsoluteString()
                        val abs = path.toAbsoluteString()
                        return if(abs.startsWith(snap)) abs.removePrefix(snap).trimStart('/') else abs
                    }

                    override fun resolvePath(
                        project: ProjectBase,
                        normalized: String
                    ): String {
                        if (normalized.startsWith("/")) return normalized
                        val snap = snapshotDir(project)?.toAbsolute()?.toString() ?: return normalized
                        return "$snap/$normalized"
                    }

                    override fun isAvailable(project: ProjectBase): Boolean {
                        val modsDir = project.projectDir.resolve("mods")
                        if (!modsDir.isDir() || modsDir.list().none { it.fileName().lowercase().contains("tritiumcompanion") }) return false
                        val registryDir = project.projectDir.resolve("registryObjs")
                        if (!registryDir.isDir()) return false
                        val latestFile = registryDir.resolve("latest.json")
                        if (!latestFile.exists()) return false
                        return runCatching {
                            val jsonRoot = _json.parseToJsonElement(latestFile.readTextOr("")).jsonObject
                            val snapPath = jsonRoot["path"]?.jsonPrimitive?.content ?: return@runCatching false
                            registryDir.resolve(snapPath).isDir()
                        }.getOrDefault(false)
                    }

                    override fun displayName(project: ProjectBase): String = "Game Data"
                    override fun rootPath(project: ProjectBase): VPath = project.projectDir.resolve("registryObjs")
                    override fun icon(project: ProjectBase): QIcon = TIcons.ProjNode.icon
                    override fun iconColor(project: ProjectBase): QColor? = TColors.GameDataRoot.toQC()

                    override fun childEntries(project: ProjectBase, root: VPath): List<ProjectFilesNodeSpec> {
                        val latestFile = root.resolve("latest.json")
                        if (!latestFile.exists()) return emptyList()
                        return runCatching {
                            val jsonRoot = _json.parseToJsonElement(latestFile.readTextOr("")).jsonObject
                            val snapPath = jsonRoot["path"]?.jsonPrimitive?.content ?: return@runCatching emptyList()
                            val snapDir = root.resolve(snapPath)
                            if (!snapDir.isDir()) return@runCatching emptyList()
                            snapDir.list().map { ProjectFilesNodeSpec(it) }
                        }.getOrDefault(emptyList())
                    }
                }
            ))

            directoryMarks.register(object : ProjectDirectoryMark {
                override val id = "excluded"
                override val displayName = "Excluded"
                override val icon: QIcon? = null
                override val incompatibleWith: List<String> = emptyList()
                override val order: Int = 0
                override val hueShiftDegrees: Float? = -20f

                override fun filterWatchEvent(path: VPath, event: VWatchEvent): Boolean = false
            })

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

            configFormats.register(BuiltinConfigFormats.All)

            // ── Search contributors ───────────────────────────────
            val searchContributors = BuiltinRegistries.SearchIndexContributor
            with(this@CoreExtension) {
                searchContributors.register(CoreFileContributor())
                searchContributors.register(RegistryEntryContributor())
                searchContributors.register(GameRecipeContributor())
            }

            // ── Search result renderers ──────────────────────────
            val renderers = BuiltinRegistries.SearchResultRenderer
            with(this@CoreExtension) {
                renderers.register(io.github.tritium_launcher.launcher.ui.search.FileSearchResultRenderer())
                renderers.register(io.github.tritium_launcher.launcher.ui.search.ActionSearchResultRenderer())
                renderers.register(io.github.tritium_launcher.launcher.ui.search.RegistryEntrySearchResultRenderer())
                renderers.register(io.github.tritium_launcher.launcher.ui.search.RecipeSearchResultRenderer())
            }

            // ── Search result actions ────────────────────────────
            val actions = BuiltinRegistries.SearchResultAction
            with(this@CoreExtension) {
                actions.register(io.github.tritium_launcher.launcher.ui.search.OpenSourceFileAction())
                actions.register(io.github.tritium_launcher.launcher.ui.search.CopyRegistryNameAction())
                actions.register(io.github.tritium_launcher.launcher.ui.search.RevealInRegistryBrowserAction())
                actions.register(io.github.tritium_launcher.launcher.ui.search.ImportToRecipeBuilderAction())
            }

            // ── Search service lifecycle ──────────────────────────
            CoroutineScope(Dispatchers.IO).onEvent<TritiumEvent.ProjectOpened> { event ->
                val projectPath = event.project.projectDir.toAbsolute().toString()
                TritiumSearchService.start(projectPath)
                if (TritiumSearchService.isRunning) {
                    val contributors = BuiltinRegistries.SearchIndexContributor.all().toList()
                    val watcher = ProjectIndexWatcher(event.project, contributors)
                    TritiumSearchService.watcher = watcher
                    watcher.start()
                }
            }

            CoroutineScope(Dispatchers.IO).onEvent<TritiumEvent.ProjectClosing> {
                TritiumSearchService.stop()
            }
        }
    }

    override val namespace: String = "tritium"
    override val isBuiltin: Boolean = true
    override val requiresRestart: Boolean = false
    override val displayName: String = "Core"
    override val description: String = "Core Tritium features"
//    override val icon: QIcon get() = if (SeasonalEvents.isPrideMonth()) TIcons.TritiumGrayscale.applyRainbowOverlay(opacity = 0.5f).icon else TIcons.Tritium.icon

    override val modules: List<Module> = listOf(coreModule)
}
