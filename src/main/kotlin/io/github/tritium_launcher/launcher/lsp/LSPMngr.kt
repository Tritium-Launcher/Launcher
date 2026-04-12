package io.github.tritium_launcher.launcher.lsp

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.project.editor.syntax.SyntaxLanguage
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages one LSP connection per project/language pair.
 *
 * Connections are reused to avoid spawning duplicate servers for the same
 * project and language.
 */
object LSPMngr {
    private val connections = ConcurrentHashMap<Pair<ProjectBase, String>, LSPConnection>()
    private val refCounts = ConcurrentHashMap<Pair<ProjectBase, String>, java.util.concurrent.atomic.AtomicInteger>()
    private val logger = logger()

    /**
     * Returns an existing connection for the file's language or starts one if needed.
     *
     * Returns null when the language has no configured LSP command.
     */
    fun getOrStart(project: ProjectBase, file: VPath): LSPConnection? {
        val lang = BuiltinRegistries.SyntaxLanguage.all().find { it.matches(file) }
        if(lang == null) return null
        val cmd = resolveCmd(lang) ?: return null

        val key = project to lang.id
        val connection = connections.computeIfAbsent(key) {
            LSPConnection(project, lang.id, cmd).apply { start() }
        }
        refCounts.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
        return connection
    }

    /**
     * Decrements the reference count for the given project/language and stops the server
     * when no open documents remain.
     */
    fun release(project: ProjectBase, langId: String) {
        val key = project to langId
        val count = refCounts[key]?.decrementAndGet() ?: return
        if(count <= 0) {
            refCounts.remove(key)
            connections.remove(key)?.stop()
            logger.info("Stopped LSP for '{}'", langId)
        }
    }

    private fun resolveCmd(lang: SyntaxLanguage): List<String>? {
        val options = lang.lspCmds ?: lang.lspCmd?.let { listOf(it) }
        if(options == null) {
            logger.info("LSP disabled for '{}' (no lspCmd configured)", lang.id)
            return null
        }

        for(option in options) {
            val exe = option.firstOrNull() ?: continue
            if(isExecutableOnPath(exe)) {
                if(options.size > 1) {
                    logger.info("LSP for '{}' selected cmd={}", lang.id, option.joinToString(" "))
                }
                return option
            }
        }

        logger.warn(
            "No LSP executable found for '{}' (tried: {})",
            lang.id,
            options.joinToString(" | ") { it.joinToString(" ") }
        )
        return null
    }

    private fun isExecutableOnPath(exe: String): Boolean {
        val direct = java.io.File(exe)
        if(direct.isAbsolute || exe.contains(java.io.File.separator)) {
            return direct.isFile && direct.canExecute()
        }
        val path = System.getenv("PATH") ?: return false
        return path.split(java.io.File.pathSeparator).any { dir ->
            val f = java.io.File(dir, exe)
            f.isFile && f.canExecute()
        }
    }
}

/**
 * Wraps an LSP server process and a connected client proxy.
 *
 * The [ready] future completes after initialize/initialized handshake finishes.
 * Editors should wait for it before sending didOpen/didChange.
 */
class LSPConnection(val project: ProjectBase, val langId: String, val cmd: List<String>) {
    private var process: Process? = null
    lateinit var server: LanguageServer
    val client = TritiumLanguageClient()
    val ready = java.util.concurrent.CompletableFuture<Unit>()

    private val logger = logger()

    /**
     * Starts the server process and performs the LSP initialization handshake.
     */
    fun start() {
        try {
            logger.info("Starting LSP for '{}' with cmd={}", langId, cmd.joinToString(" "))
            val pb = ProcessBuilder(cmd)
                .directory(project.projectDir.toJFile())
            process = pb.start()

            val launcher = LSPLauncher.createClientLauncher(
                client, process!!.inputStream, process!!.outputStream
            )
            launcher.startListening()
            server = launcher.remoteProxy

            @Suppress("DEPRECATION")
            val params = InitializeParams().apply {
                val rootFile = project.projectDir.toJFile()
                rootUri = rootFile.toURI().toString()
                // Some servers (pyright) are sensitive to URI decoding; rootPath helps with spaces.
                rootPath = rootFile.absolutePath

                workspaceFolders = listOf(
                    WorkspaceFolder(rootUri, project.name)
                )

                capabilities = ClientCapabilities().apply {
                    textDocument = TextDocumentClientCapabilities().apply {
                        completion = CompletionCapabilities()
                        hover = HoverCapabilities()
                        publishDiagnostics = PublishDiagnosticsCapabilities()
                    }
                }
            }
            server.initialize(params).thenRun {
                server.initialized(InitializedParams())
                ready.complete(Unit)
            }.exceptionally { t ->
                ready.completeExceptionally(t)
                null
            }
        } catch (t: Throwable) {
            logger.error("Failed to start Language Server for '{}'", langId, t)
            ready.completeExceptionally(t)
        }
    }

    /**
     * Stops the underlying server process.
     */
    fun stop() {
        try {
            server.shutdown().thenRun { server.exit() }
        } catch (t: Throwable) {
            logger.warn("LSP shutdown failed for '{}'", langId, t)
        } finally {
            process?.destroy()
        }
    }
}
