package io.github.tritium_launcher.launcher.lsp

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.ui.notifications.NotificationMngr
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.LSPServerDefinition
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxLanguage
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import io.github.tritium_launcher.launcher.ui.theme.qt.icon
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.pushButton
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

object LSPInstaller {
    private val logger = logger()
    private val downloading = ConcurrentHashMap.newKeySet<String>()
    private val httpClient = HttpClient(CIO)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun isInstalled(lang: SyntaxLanguage, server: LSPServerDefinition): Boolean {
        val binary = getBinaryPath(lang, server) ?: return false
        return binary.exists() && binary.toJFile().canExecute()
    }

    fun getBinaryPath(lang: SyntaxLanguage, server: LSPServerDefinition): VPath? {
        val spec = server.installSpec ?: return null
        return TConstants.LSPS_DIR.resolve(lang.id).resolve(server.id).resolve(spec.binaryPath)
    }

    fun checkAndPromptInstallation(project: ProjectBase, file: VPath) {
        val lang = BuiltinRegistries.SyntaxLanguage.all().find { it.matches(file) } ?: return
        val lsp = lang.lsp ?: return
        
        // Find the first server that has an install spec and is not installed
        val installableServer = lsp.servers.find { server ->
            server.installSpec != null && !isInstalled(lang, server) && !isOnPath(server)
        } ?: return

        if (downloading.contains("${lang.id}:${installableServer.id}")) return

        NotificationMngr.post(
            id = "lsp_install_prompt",
            project = project,
            header = "LSP Server Missing",
            description = "An LSP server (${installableServer.id}) is available for ${lang.displayName} but not installed. Would you like to install it?",
            customWidgetFactory = { context ->
                pushButton("Install ${installableServer.id} LSP", null) {
                    clicked.connect {
                        install(project, lang, installableServer)
                        NotificationMngr.dismiss(context.entry.instanceId)
                    }
                }
            }
        )
    }

    private fun isOnPath(server: LSPServerDefinition): Boolean {
        val exe = server.command.firstOrNull() ?: return false
        return isExecutableOnPath(exe)
    }

    private fun isExecutableOnPath(exe: String): Boolean {
        val direct = File(exe)
        if (direct.isAbsolute || exe.contains(File.separator)) {
            return direct.isFile && direct.canExecute()
        }
        val path = System.getenv("PATH") ?: return false
        return path.split(File.pathSeparator).any { dir ->
            val f = File(dir, exe)
            f.isFile && f.canExecute()
        }
    }

    fun install(project: ProjectBase, lang: SyntaxLanguage, server: LSPServerDefinition) {
        val spec = server.installSpec ?: return
        val url = spec.downloadUrls[Platform.current] ?: spec.downloadUrls[Platform.Unknown] ?: return
        
        val downloadKey = "${lang.id}:${server.id}"
        if (!downloading.add(downloadKey)) {
            logger.info("Already downloading LSP for {}", downloadKey)
            return
        }

        scope.launch {
            val notification = NotificationMngr.post(
                id = "generic",
                project = project,
                header = "Installing ${server.id} LSP",
                description = "Downloading from $url...",
                icon = TIcons.Run.icon
            )

            try {
                val tempFile = File.createTempFile("lsp-download", ".tmp")
                logger.info("Downloading LSP for {} from {} to {}", downloadKey, url, tempFile.absolutePath)
                
                val response: HttpResponse = httpClient.get(url)
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("HTTP ${response.status.value} when downloading LSP")
                }
                
                val channel: ByteReadChannel = response.body()
                FileOutputStream(tempFile).use { output ->
                    channel.copyTo(output)
                }

                notification?.let { NotificationMngr.dismiss(it.instanceId) }
                
                val extractingNotification = NotificationMngr.post(
                    id = "generic",
                    project = project,
                    header = "Installing ${server.id} LSP",
                    description = "Extracting...",
                    icon = TIcons.Run.icon
                )

                val installDir = TConstants.LSPS_DIR.resolve(lang.id).resolve(server.id)
                if (installDir.exists()) {
                    installDir.toJFile().deleteRecursively()
                }
                installDir.mkdirs()
                
                logger.info("Extracting LSP for {} to {}", downloadKey, installDir.toAbsolute())
                extract(tempFile, installDir.toJFile())
                tempFile.delete()

                val binary = getBinaryPath(lang, server)
                if (binary != null && binary.exists()) {
                    binary.toJFile().setExecutable(true)
                    logger.info("LSP for {} installed to {}", downloadKey, binary.toAbsolute())
                } else {
                    logger.warn("LSP for {} installed but binary not found at expected path: {}", downloadKey, binary?.toAbsolute())
                }

                extractingNotification?.let { NotificationMngr.dismiss(it.instanceId) }
                NotificationMngr.post(
                    id = "generic",
                    project = project,
                    header = "${server.id} LSP Installed",
                    description = "The Language Server has been installed successfully. Please reopen the file to activate it.",
                    icon = TIcons.Run.icon
                )
            } catch (t: Throwable) {
                logger.error("Failed to install LSP for {}", downloadKey, t)
                notification?.let { NotificationMngr.dismiss(it.instanceId) }
                NotificationMngr.post(
                    id = "bootstrap_failure",
                    project = project,
                    header = "LSP Installation Failed",
                    description = "Failed to install LSP for ${server.id}: ${t.message}"
                )
            } finally {
                downloading.remove(downloadKey)
            }
        }
    }

    private suspend fun extract(archive: File, destination: File) = withContext(Dispatchers.IO) {
        if (archive.name.contains(".zip", ignoreCase = true)) {
            ZipInputStream(archive.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val file = File(destination, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile.mkdirs()
                        file.outputStream().use { output ->
                            zip.copyTo(output)
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        } else if (archive.name.contains(".tar.gz", ignoreCase = true) || archive.name.contains(".tgz", ignoreCase = true)) {
            if (Platform.isWindows) {
                 throw UnsupportedOperationException("TAR extraction not yet supported on Windows")
            } else {
                val proc = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", destination.absolutePath)
                    .inheritIO()
                    .start()
                val exitCode = proc.waitFor()
                if (exitCode != 0) {
                    throw IllegalStateException("tar command failed with exit code $exitCode")
                }
            }
        } else {
            throw UnsupportedOperationException("Unsupported archive format: ${archive.name}")
        }
    }
}
