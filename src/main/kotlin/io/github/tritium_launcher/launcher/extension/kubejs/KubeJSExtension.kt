package io.github.tritium_launcher.launcher.extension.kubejs

import io.github.tritium_launcher.launcher.extension.Extension
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.ui.project.editor.file.FileTypeDescriptor
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxLanguage
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.TreeSitterService
import io.github.tritium_launcher.launcher.ui.project.sidebar.projectRootDirectory
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.qt.gui.QIcon
import org.koin.core.module.Module
import org.koin.dsl.module

class KubeJSExtension : Extension {
    override val namespace: String = "kubejs"
    override val displayName: String = "KubeJS"
    override val description: String = "KubeJS script editing — syntax highlighting, file type detection, project root directory"
    override val requiresRestart: Boolean = true
    override val icon: QIcon get() = TIcons.KubeScript.icon

    override val modules: List<Module> = listOf(module {
        single(createdAtStart = true) {
            KubeJSInitializer(this@KubeJSExtension).also { it.init() }
        }
    })

    class KubeJSInitializer(private val ext: Extension) {
        fun init() {
            TreeSitterService.init()
            with(ext) {
                BuiltinRegistries.FileType.register(KubeScriptType)
                BuiltinRegistries.ProjectRootDirectory.register(projectRootDirectory("kubejs", "kubejs", "KubeJS"))
                BuiltinRegistries.SyntaxLanguage.register(KubeScriptLanguage)
            }
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
    }
}
