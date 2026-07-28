/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.search

import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.search.IndexStatus
import io.github.tritium_launcher.api.search.IndexableDocument
import io.github.tritium_launcher.api.search.SearchFilters
import io.github.tritium_launcher.api.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object TritiumSearchService {
    private val log = logger()
    private const val REQUEST_TIMEOUT_MS = 30_000L
    private const val SOCKET_CONNECT_RETRIES = 20
    private const val SOCKET_RETRY_DELAY_MS = 100L
    private val mutex = Mutex()

    private var process: Process? = null
    private var channel: SocketChannel? = null
    private var reader: BufferedReader? = null
    private var currentProjectPath: String? = null
    private var sockPath: Path? = null
    internal var watcher: ProjectIndexWatcher? = null

    val isRunning: Boolean get() = process != null && process?.isAlive == true

    fun start(projectPath: String) {
        if (isRunning) {
            log.warn("Indexer already running for {}", currentProjectPath)
            return
        }

        val hash = projectHash(projectPath)
        val indexDir = resolveIndexDir(hash)
        val socketFile = resolveSocketPath(hash)
        sockPath = socketFile

        try { Files.deleteIfExists(socketFile) } catch (_: Exception) {}

        try {
            if (Files.exists(indexDir)) {
                indexDir.toFile().deleteRecursively()
            }
        } catch (e: Exception) {
            log.warn("Failed to reset index dir {}", indexDir, e)
        }

        try { Files.createDirectories(indexDir) } catch (e: Exception) {
            log.error("Failed to create index dir {}", indexDir, e)
            return
        }

        val binary = resolveBinary() ?: run {
            log.error("indexer binary not found")
            return
        }

        try {
            val pb = ProcessBuilder(
                binary,
                "--index-dir", indexDir.toString(),
                "--socket", socketFile.toString()
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc

            Thread({
                try {
                    BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                        reader.lines().forEach { line ->
                            if (line.isNotBlank()) log.debug("[indexer] {}", line)
                        }
                    }
                } catch (_: Exception) {}
            }, "indexer-stderr").apply { isDaemon = true }.start()

            val sock = waitForSocket(socketFile)
            if (sock == null) {
                log.error("Failed to connect to indexer socket at {}", socketFile)
                stop()
                return
            }
            channel = sock
            reader = BufferedReader(InputStreamReader(Channels.newInputStream(sock), StandardCharsets.UTF_8))
            currentProjectPath = projectPath
            log.info("Indexer started for {} (pid={})", projectPath, proc.pid())
        } catch (e: Exception) {
            log.error("Failed to start indexer", e)
            stop()
        }
    }

    fun stop() {
        watcher?.stop()
        watcher = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { channel?.close() } catch (_: Exception) {}
        channel = null
        try {
            process?.let { p ->
                if (p.isAlive) {
                    p.destroyForcibly()
                    p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        } catch (_: Exception) {}
        process = null
        try { sockPath?.let { Files.deleteIfExists(it) } } catch (_: Exception) {}
        sockPath = null
        currentProjectPath = null
        log.info("Indexer stopped")
    }

    suspend fun search(query: String, filters: SearchFilters? = null): List<SearchResult> =
        withContext(Dispatchers.IO) {
            if (!isRunning) {
                log.debug("Search skipped: indexer not running")
                return@withContext emptyList()
            }
            try {
                val payload = buildJsonObject {
                    put("query", query)
                    if (filters != null) {
                        put("filters", buildJsonObject {
                            filters.kind?.let { put("kind", it) }
                            filters.modId?.let { put("mod_id", it) }
                            filters.recipeType?.let { put("recipe_type", it) }
                        })
                    }
                    put("limit", 50)
                }
                val resp = sendRequest("search", payload)
                val results = resp["results"]?.jsonArray ?: emptyList()
                val mapped = results.map { it.jsonObject.toSearchResult() }
                log.debug("Search '{}' filters={} returned {} results", query, filters?.kind, mapped.size)
                mapped
            } catch (e: Exception) {
                log.warn("Search failed, returning empty results", e)
                emptyList()
            }
        }

    suspend fun addDocuments(docs: List<IndexableDocument>) = withContext(Dispatchers.IO) {
        if (!isRunning) {
            log.debug("addDocuments skipped: indexer not running")
            return@withContext
        }
        try {
            val payload = buildJsonObject {
                put("documents", buildJsonArray {
                    docs.forEach { doc -> add(doc.toJson()) }
                })
            }
            sendRequest("add", payload)
//            log.debug("addDocuments added {} doc(s)", docs.size)
        } catch (e: Exception) {
            log.warn("addDocuments failed for {} doc(s)", docs.size, e)
        }
    }

    suspend fun deleteDocument(id: String) = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext
        try {
            val payload = buildJsonObject { put("id_to_delete", id) }
            sendRequest("delete", payload)
        } catch (e: Exception) {
            log.warn("deleteDocument failed", e)
        }
    }

    suspend fun commit() = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext
        try {
            sendRequest("commit", buildJsonObject { })
        } catch (e: Exception) {
            log.warn("commit failed", e)
        }
    }

    suspend fun status(): IndexStatus = withContext(Dispatchers.IO) {
        if (!isRunning) return@withContext IndexStatus(0, 0)
        try {
            val resp = sendRequest("status", buildJsonObject { })
            IndexStatus(
                numDocs = resp["num_docs"]?.jsonPrimitive?.longOrNull ?: 0,
                opstamp = resp["opstamp"]?.jsonPrimitive?.longOrNull ?: 0
            )
        } catch (e: Exception) {
            log.warn("status failed", e)
            IndexStatus(0, 0)
        }
    }

    private suspend fun sendRequest(op: String, payload: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val ch = channel ?: error("Indexer not running")
                val rd = reader ?: error("Indexer reader not available")
                val reqId = "req-${System.nanoTime()}"
                val request = buildJsonObject {
                    put("id", reqId)
                    put("op", op)
                    put("payload", payload)
                }
                val requestBytes = (request.toString() + "\n").toByteArray(StandardCharsets.UTF_8)

                ch.write(ByteBuffer.wrap(requestBytes))

                val line = rd.readLine() ?: error("Indexer connection closed")
                val resp = Json.parseToJsonElement(line).jsonObject
                if (resp["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                    val err = resp["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error"
                    error("Indexer error for '$op': $err")
                }
                resp["payload"]?.jsonObject ?: buildJsonObject { }
            }
        }

    private fun waitForSocket(socketFile: Path): SocketChannel? {
        for (i in 0 until SOCKET_CONNECT_RETRIES) {
            if (Files.exists(socketFile)) {
                try {
                    val addr = UnixDomainSocketAddress.of(socketFile)
                    val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
                    ch.connect(addr)
                    ch.configureBlocking(true)
                    return ch
                } catch (e: Exception) {
                    try { Thread.sleep(SOCKET_RETRY_DELAY_MS) } catch (_: InterruptedException) {}
                }
            }
            try { Thread.sleep(SOCKET_RETRY_DELAY_MS) } catch (_: InterruptedException) {}
        }
        return null
    }

    private fun projectHash(projectPath: String): String {
        val canonical = Path.of(projectPath).toAbsolutePath().normalize().toString()
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun resolveIndexDir(hash: String): Path {
        val dataHome = System.getenv("XDG_DATA_HOME")
            ?: "${System.getProperty("user.home")}/.local/share"
        return Path.of(dataHome, "tritium", "index", hash)
    }

    private fun resolveSocketPath(hash: String): Path {
        return Path.of(System.getProperty("java.io.tmpdir"), "tritium-idx-$hash.sock")
    }

    private fun resolveBinary(): String? {
        val exeName = "indexer"
        return listOfNotNull(
            File("tools/indexer/target/release/$exeName").takeIf { it.isFile() }?.absolutePath,
            File("tools/indexer/target/debug/$exeName").takeIf { it.isFile() }?.absolutePath,
            try {
                val app = System.getProperty("jpackage.app-path")
                if (app != null) File(app).parentFile?.let { File(it, exeName).absolutePath } else null
            } catch (_: Exception) { null },
            exeName
        ).firstOrNull()
    }

    private fun JsonObject.toSearchResult() = SearchResult(
        id = get("id")?.jsonPrimitive?.contentOrNull.orEmpty(),
        kind = get("kind")?.jsonPrimitive?.contentOrNull.orEmpty(),
        name = get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
        detail = get("detail")?.jsonPrimitive?.contentOrNull.orEmpty(),
        path = get("path")?.jsonPrimitive?.contentOrNull.orEmpty(),
        modId = get("mod_id")?.jsonPrimitive?.contentOrNull.orEmpty(),
        sourceLine = get("source_line")?.jsonPrimitive?.longOrNull ?: 0L,
        outputId = get("output_id")?.jsonPrimitive?.contentOrNull,
        inputIds = get("input_ids")?.jsonPrimitive?.contentOrNull,
        recipeType = get("recipe_type")?.jsonPrimitive?.contentOrNull,
        sourceKind = get("source_kind")?.jsonPrimitive?.contentOrNull,
        score = get("score")?.jsonPrimitive?.floatOrNull ?: 0f
    )

    private fun IndexableDocument.toJson(): JsonObject = buildJsonObject {
        put("id", this@toJson.id)
        put("kind", this@toJson.kind)
        put("name", this@toJson.name)
        put("name_exact", this@toJson.nameExact)
        put("detail", this@toJson.detail)
        put("path", this@toJson.path)
        put("mod_id", this@toJson.modId)
        put("tags", this@toJson.tags)
        put("mtime", this@toJson.mtime)
        this@toJson.outputId?.let { put("output_id", it) }
        this@toJson.inputIds?.let { put("input_ids", it) }
        this@toJson.recipeType?.let { put("recipe_type", it) }
        this@toJson.sourceKind?.let { put("source_kind", it) }
        this@toJson.sourceLine?.let { put("source_line", it) }
    }
}
