/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.lsp

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.file.SyntaxLanguage
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import kotlinx.coroutines.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages one LSP connection per project/language pair.
 *
 * Connections are reused to avoid spawning duplicate servers for the same
 * project and language.
 */
object LSPMngr {
    private val connections = ConcurrentHashMap<Pair<ProjectBase, String>, LSPConnection>()
    private val refCounts = ConcurrentHashMap<Pair<ProjectBase, String>, AtomicInteger>()
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
        val connection = connections.compute(key) { _, existing ->
            if(existing == null || existing.isClosed || existing.ready.isCompletedExceptionally) {
                existing?.stop()
                LSPConnection(project, lang.id, cmd) { failed ->
                    connections.remove(key, failed)
                    refCounts.remove(key)
                }.apply { start() }
            } else {
                existing
            }
        }
        if(connection == null) return null
        refCounts.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
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
        val lsp = lang.lsp
        if(lsp == null) {
            logger.info("LSP disabled for '{}' (no lsp definition)", lang.id)
            return null
        }

        for (server in lsp.servers) {
            // 1. Check if the server is on PATH
            val exe = server.command.firstOrNull() ?: continue
            if (isExecutableOnPath(exe)) {
                logger.info("LSP for '{}' selected server='{}' from PATH", lang.id, server.id)
                return server.command
            }

            // 2. Check if the server is installed in Tritium's lsps directory
            val installedBinary = LSPInstaller.getBinaryPath(lang, server)
            if (installedBinary != null && installedBinary.exists() && installedBinary.toJFile().canExecute()) {
                logger.info("LSP for '{}' selected server='{}' from local install", lang.id, server.id)
                return listOf(installedBinary.toString())
            }
        }

        logger.warn(
            "No LSP executable found for '{}' (tried: {})",
            lang.id,
            lsp.servers.joinToString(", ") { it.id }
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
class LSPConnection(
    val project: ProjectBase,
    val langId: String,
    val cmd: List<String>,
    private val onFailedStart: ((LSPConnection) -> Unit)? = null
) {
    private var process: Process? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcherJob: Job? = null
    lateinit var server: LanguageServer
    val client = TritiumLanguageClient()
    val ready = CompletableFuture<Unit>()
    @Volatile var isClosed: Boolean = false
        private set
    var semanticTokensLegend: SemanticTokensLegend? = null

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

            // Watch for unexpected process exit so adapters can check isClosed
            val proc = process!!
            watcherJob = scope.launch {
                try {
                    val exitCode = runInterruptible { proc.waitFor() }
                    if (!isClosed) {
                        logger.info("LSP '{}' process exited unexpectedly (code {})", langId, exitCode)
                        isClosed = true
                    }
                } catch (_: InterruptedException) {
                }
            }

            val launcher = LSPLauncher.createClientLauncher(
                client, proc.inputStream, proc.outputStream
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

                        semanticTokens = SemanticTokensCapabilities(
                            SemanticTokensClientCapabilitiesRequests(
                                SemanticTokensClientCapabilitiesRequestsFull(false),
                                false
                            ),
                            tokenTypesList,
                            tokenModifiersList,
                            listOf(TokenFormat.Relative)
                        )
                    }
                }
            }

            server.initialize(params).thenAccept { result ->
                if(isClosed) return@thenAccept

                semanticTokensLegend = result.capabilities?.semanticTokensProvider?.legend

                server.initialized(InitializedParams())
                ready.complete(Unit)
            }.exceptionally { t ->
                onFailedStart?.invoke(this)
                ready.completeExceptionally(t)
                stop()
                null
            }
        } catch (t: Throwable) {
            logger.error("Failed to start Language Server for '{}'", langId, t)
            onFailedStart?.invoke(this)
            ready.completeExceptionally(t)
            stop()
        }
    }

    /**
     * Stops the underlying server process.
     */
    fun stop() {
        if(isClosed) {
            return
        }
        isClosed = true
        watcherJob?.cancel()
        scope.cancel()
        try {
            val shutdown = if(this::server.isInitialized) {
                server.shutdown()
            } else {
                CompletableFuture.completedFuture(null)
            }
            shutdown.orTimeout(2, TimeUnit.SECONDS).whenComplete { _, _ ->
                try {
                    if(this::server.isInitialized) {
                        server.exit()
                    }
                } catch (t: Throwable) {
                    logger.warn("LSP exit failed for '{}'", langId, t)
                } finally {
                    process?.destroy()
                }
            }
        } catch (t: Throwable) {
            logger.warn("LSP shutdown failed for '{}'", langId, t)
            process?.destroy()
        }
    }
}

private val tokenTypesList = listOf(
    SemanticTokenTypes.Namespace,
    SemanticTokenTypes.Type,
    SemanticTokenTypes.Class,
    SemanticTokenTypes.Enum,
    SemanticTokenTypes.Interface,
    SemanticTokenTypes.Struct,
    SemanticTokenTypes.TypeParameter,
    SemanticTokenTypes.Parameter,
    SemanticTokenTypes.Variable,
    SemanticTokenTypes.Property,
    SemanticTokenTypes.EnumMember,
    SemanticTokenTypes.Event,
    SemanticTokenTypes.Function,
    SemanticTokenTypes.Method,
    SemanticTokenTypes.Macro,
    SemanticTokenTypes.Keyword,
    SemanticTokenTypes.Modifier,
    SemanticTokenTypes.Comment,
    SemanticTokenTypes.String,
    SemanticTokenTypes.Number,
    SemanticTokenTypes.Regexp,
    SemanticTokenTypes.Operator,
    SemanticTokenTypes.Decorator
)

private val tokenModifiersList = listOf(
    SemanticTokenModifiers.Declaration,
    SemanticTokenModifiers.Definition,
    SemanticTokenModifiers.Readonly,
    SemanticTokenModifiers.Static,
    SemanticTokenModifiers.Deprecated,
    SemanticTokenModifiers.Abstract,
    SemanticTokenModifiers.Async,
    SemanticTokenModifiers.Modification,
    SemanticTokenModifiers.Documentation,
    SemanticTokenModifiers.DefaultLibrary
)
