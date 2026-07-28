/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.extension.kubejs

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.docks.projectRootDirectory
import io.github.tritium_launcher.api.editor.intelligence.EditorIntelligenceProvider
import io.github.tritium_launcher.api.extension.Extension
import io.github.tritium_launcher.api.file.FileTypeDescriptor
import io.github.tritium_launcher.api.file.SyntaxLanguage
import io.github.tritium_launcher.api.inspection.*
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.registrydb.RegistryDatabase
import io.github.tritium_launcher.launcher.ui.search.ScriptSymbolSearchResultRenderer
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import org.koin.core.module.Module
import org.koin.dsl.module

class KubeJSExtension : Extension {
    override val namespace: String = "kubejs"
    override val displayName: String = "KubeJS"
    override val description: String = "KubeJS script editing — syntax highlighting, file type detection, project root directory"
    override val requiresRestart: Boolean = true
//    override val icon: QIcon get() = TIcons.KubeScript.icon

    override val modules: List<Module> = listOf(module {
        single(createdAtStart = true) { KubeJSIntelligenceService }
    })

    override fun onRegister() {
        logger().info("KubeJSExtension.onRegister() called")
        EditorIntelligenceProvider.instance = KubeJSIntelligenceService

        BuiltinRegistries.FileType.register(KubeScriptType)
        BuiltinRegistries.FileType.register(KubeJSLog)
        BuiltinRegistries.ProjectRootDirectory.register(
            projectRootDirectory("kubejs", "kubejs", "KubeJS")
        )
        BuiltinRegistries.SyntaxLanguage.register(KubeScriptLanguage)
        BuiltinRegistries.SearchIndexContributor.register(KubeJSContributor())
        BuiltinRegistries.SearchResultRenderer.register(
            ScriptSymbolSearchResultRenderer()
        )
        registerKubeScriptInspections()
    }

    private fun registerKubeScriptInspections() {
        InspectionDataProviders.register("kubejs:param_type_resolver") {
            KubeJSParamTypeResolver()
        }

        InspectionRegistry.register(InspectionSpec(
            id = "unknown_item_id",
            title = "Unknown item ID",
            description = "Item or ingredient strings referencing IDs not found in the registry.",
            languageId = "kubescript",
            category = listOf("KubeJS", "Registry"),
            defaultSeverity = Severity.WARNING,
            type = InspectionType.ParameterizedStringCheck(
                matchTypes = setOf("item_id"),
                messageTemplate = "Unknown item '{value}'.",
                check = { value, context ->
                    runCatching {
                        RegistryDatabase.itemDetail(context.project, value) == null
                    }.getOrDefault(false)
                }
            )
        ))
    }

    private class KubeJSParamTypeResolver : ParamTypeResolver {
        override fun resolve(receiver: String?, method: String, paramIndex: Int): String? {
            when (receiver) {
                "Item", "Ingredient" -> {
                    if (method == "of" && paramIndex == 0) return "item_id"
                }
            }
            return null
        }
    }

    companion object {
        val KubeScriptType = FileTypeDescriptor.create(
            id = "kubescript",
            displayName = "KubeJS Script",
            icon = TIcons.KubeScript.icon,
            matches = { file, _ ->
                file.parent().fileName().matches("startup_scripts", "server_scripts", "client_scripts") &&
                        file.extension().matches("js")
            },
            order = -10,
            canCreateIn = { directory, _ ->
                directory.fileName().matches("startup_scripts", "server_scripts", "client_scripts")
            },
            defaultFileName = { "" },
            createDefaultFile = { directory, name, _ ->
                val fileName = "$name.js"
                val file = directory.resolve(fileName)
                runCatching { file.writeBytesAtomic(ByteArray(0)); file }.getOrNull()
            }
        )

        val KubeScriptLanguage = SyntaxLanguage.create(
            id = "kubescript",
            displayName = "KubeJS Script",
            predicate = { this.parent().fileName().matches("startup_scripts", "server_scripts", "client_scripts") &&
                        this.extension().matches("js")
            },
        )

        val KubeJSLog = FileTypeDescriptor.create(
            id = "kjs_log",
            displayName = "KubeJS Log",
            icon = TIcons.KubeLog.icon,
            matches = { file, _ ->
                file.parent().fileName().matches("kubejs") &&
                        file.fileName().matches("startup.log", "server.log", "client.log")
            }
        )
    }
}
