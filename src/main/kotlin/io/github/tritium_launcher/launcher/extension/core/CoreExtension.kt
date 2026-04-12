package io.github.tritium_launcher.launcher.extension.core

import io.github.tritium_launcher.launcher.accounts.ui.MicrosoftAccountProvider
import io.github.tritium_launcher.launcher.core.modloader.Fabric
import io.github.tritium_launcher.launcher.core.modloader.NeoForge
import io.github.tritium_launcher.launcher.core.modpack.CurseForge
import io.github.tritium_launcher.launcher.core.modpack.Modrinth
import io.github.tritium_launcher.launcher.core.project.ModpackProjectType
import io.github.tritium_launcher.launcher.core.project.ModpackTemplateDescriptor
import io.github.tritium_launcher.launcher.core.project.templates.TemplateRegistry
import io.github.tritium_launcher.launcher.core.project.templates.generation.license.*
import io.github.tritium_launcher.launcher.extension.Extension
import io.github.tritium_launcher.launcher.registry.RegistryMngr
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.dashboard.DvdStyleProvider
import io.github.tritium_launcher.launcher.ui.dashboard.GridStyleProvider
import io.github.tritium_launcher.launcher.ui.dashboard.ListStyleProvider
import io.github.tritium_launcher.launcher.ui.project.editor.file.builtin.BuiltinFileTypes
import io.github.tritium_launcher.launcher.ui.project.editor.pane.ImageViewerProvider
import io.github.tritium_launcher.launcher.ui.project.editor.pane.SettingsEditorPaneProvider
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin.JsonLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.builtin.PythonLanguage
import io.github.tritium_launcher.launcher.ui.project.menu.builtin.BuiltinMenuItems
import io.github.tritium_launcher.launcher.ui.project.sidebar.ProjectFilesSidePanelProvider
import io.github.tritium_launcher.launcher.ui.project.sidebar.ProjectLogsSidePanelProvider
import io.github.tritium_launcher.launcher.ui.project.sidebar.ProjectNotificationsSidePanelProvider
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Core extension that registers Tritium's base content and features.
 */
internal object CoreExtension : Extension {
    private val coreModule = module {
        single(createdAtStart = true) {
            val rm: RegistryMngr = get()
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

            settings.register(this@CoreExtension.namespace, CoreSettings.registration)

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
            sidePanels.register(ProjectLogsSidePanelProvider())
            sidePanels.register(ProjectNotificationsSidePanelProvider())

            menuItems.register(BuiltinMenuItems.All)
            notifications.register(BuiltinNotifications.All)

            syntax.register(listOf(
                JsonLanguage(),
                PythonLanguage(),
            ))

            TemplateRegistry.register(ModpackTemplateDescriptor)

            editorPanes.register(ImageViewerProvider())
            editorPanes.register(SettingsEditorPaneProvider())
            projectListStyles.register(listOf(GridStyleProvider, ListStyleProvider, DvdStyleProvider))
        }
    }

    override val namespace: String = "tritium"

    override val modules: List<Module> = listOf(coreModule)
}
