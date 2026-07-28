/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api

import io.github.tritium_launcher.api.accounts.AccountProvider
import io.github.tritium_launcher.api.dashboard.ProjectListStyleProvider
import io.github.tritium_launcher.api.docks.*
import io.github.tritium_launcher.api.editor.EditorPaneProvider
import io.github.tritium_launcher.api.file.FileTypeDescriptor
import io.github.tritium_launcher.api.file.SyntaxLanguage
import io.github.tritium_launcher.api.license.License
import io.github.tritium_launcher.api.menu.MenuItem
import io.github.tritium_launcher.api.modpack.ConfigFormat
import io.github.tritium_launcher.api.modpack.ModLoader
import io.github.tritium_launcher.api.modpack.ModSource
import io.github.tritium_launcher.api.notification.NotificationDefinition
import io.github.tritium_launcher.api.project.ProjectType
import io.github.tritium_launcher.api.project.template.StepFactory
import io.github.tritium_launcher.api.project.template.TemplateDescriptor
import io.github.tritium_launcher.api.registry.RegistryMngr
import io.github.tritium_launcher.api.search.SearchIndexContributor
import io.github.tritium_launcher.api.search.SearchResultAction
import io.github.tritium_launcher.api.search.SearchResultRenderer

/**
 * Central registry handles for core and UI extension points.
 *
 * Each property returns or creates the named registry via [RegistryMngr] so extensions can
 * register their implementations in a consistent location.
 */
object BuiltinRegistries {
    val AccountProvider  = RegistryMngr.getOrCreateRegistry<AccountProvider>("account_provider")
    val ConfigFormat     = RegistryMngr.getOrCreateRegistry<ConfigFormat>("config_format")
    val EditorPane       = RegistryMngr.getOrCreateRegistry<EditorPaneProvider>("editor_pane")
    val FileType         = RegistryMngr.getOrCreateRegistry<FileTypeDescriptor>("file_type")
    val License          = RegistryMngr.getOrCreateRegistry<License>("license")
    val MenuItem         = RegistryMngr.getOrCreateRegistry<MenuItem>("menu")
    val ModLoader        = RegistryMngr.getOrCreateRegistry<ModLoader>("mod_loader")
    val ModSource        = RegistryMngr.getOrCreateRegistry<ModSource>("mod_source")
    val Notification     = RegistryMngr.getOrCreateRegistry<NotificationDefinition>("notification")
    val ProjectType      = RegistryMngr.getOrCreateRegistry<ProjectType>("project_type")
    val ProjectListStyle = RegistryMngr.getOrCreateRegistry<ProjectListStyleProvider>("project_list_style")
    val SidePanel        = RegistryMngr.getOrCreateRegistry<DockPanelProvider>("side_panel")
    val SyntaxLanguage   = RegistryMngr.getOrCreateRegistry<SyntaxLanguage>("syntax")

    val ProjectTreeRoot      = RegistryMngr.getOrCreateRegistry<ProjectTreeRoot>("project_tree_root")
    val ProjectFilesAction   = RegistryMngr.getOrCreateRegistry<ProjectFilesContextAction>("project_files_action")
    val ProjectFilesViewMode = RegistryMngr.getOrCreateRegistry<ProjectFilesViewMode>("project_files_view_mode")
    val ProjectRootDirectory = RegistryMngr.getOrCreateRegistry<ProjectRootDirectoryProvider>("project_root_directory")
    val ProjectDirectoryMark = RegistryMngr.getOrCreateRegistry<ProjectDirectoryMark>("project_directory_mark")
    val ProjectTreeDirectoryPresentation = RegistryMngr.getOrCreateRegistry<ProjectTreeDirectoryPresentation>("project_tree_directory_presentation")

    val SearchResultAction     = RegistryMngr.getOrCreateRegistry<SearchResultAction>("search_result_action")
    val SearchResultRenderer   = RegistryMngr.getOrCreateRegistry<SearchResultRenderer>("search_result_renderer")
    val SearchIndexContributor = RegistryMngr.getOrCreateRegistry<SearchIndexContributor>("search_index_contributor")

    val Template = RegistryMngr.getOrCreateRegistry<TemplateDescriptor<*>>("template")
    val Step = RegistryMngr.getOrCreateRegistry<StepFactory>("step")
}
