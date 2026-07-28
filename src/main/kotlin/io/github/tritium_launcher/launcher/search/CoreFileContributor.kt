/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.search

import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.search.IndexableDocument
import io.github.tritium_launcher.api.search.SearchIndexContributor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

class CoreFileContributor : SearchIndexContributor {
    override val id = "core_file"
    override val producedKinds = setOf("file")
    override val kindDisplayNames = mapOf("file" to "Files")

    private val log = logger()
    private val excludedDirs = setOf(".git", "build", "run", "logs", "crash-reports", "node_modules", ".gradle", "target")

    override suspend fun contributeDocuments(projectPath: String): Flow<IndexableDocument> =
        callbackFlow {
            val root = File(projectPath)
            val basePath = root.toPath().toAbsolutePath().normalize()

            withContext(Dispatchers.IO) {
                walkTree(basePath) { file ->
                    val relPath = basePath.relativize(file)
                    val kind = if (isConfigPath(relPath.toString())) "config" else "file"
                    val relativePath = relPath.toString().replace('\\', '/')

                    trySend(
                        IndexableDocument(
                            id = "file:$relativePath",
                            kind = kind,
                            name = file.fileName.toString(),
                            detail = relativePath,
                            path = file.toAbsolutePath().toString(),
                            mtime = file.toFile().lastModified()
                        )
                    )
                }
            }

            close()
            awaitClose { }
        }.buffer(Channel.UNLIMITED)

    override suspend fun contributeFile(projectPath: String, filePath: String): Flow<IndexableDocument>? {
        val file = File(filePath)
        if (!file.isFile()) return null
        val basePath = File(projectPath).toPath().toAbsolutePath().normalize()
        val relPath = try { basePath.relativize(file.toPath()) } catch (_: Exception) { return null }
        val kind = if (isConfigPath(relPath.toString())) "config" else "file"
        val relativePath = relPath.toString().replace('\\', '/')

        val doc = IndexableDocument(
            id = "file:$relativePath",
            kind = kind,
            name = file.name,
            detail = relativePath,
            path = file.absolutePath,
            mtime = file.lastModified()
        )
        return callbackFlow {
            trySend(doc)
            close()
            awaitClose { }
        }.buffer(Channel.UNLIMITED)
    }

    private fun walkTree(root: java.nio.file.Path, action: (java.nio.file.Path) -> Unit) {
        java.nio.file.Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                val name = dir.fileName?.toString() ?: return java.nio.file.FileVisitResult.CONTINUE
                if (name in excludedDirs || name.startsWith(".")) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                action(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun isConfigPath(relativePath: String): Boolean {
        return relativePath.startsWith("config/") || relativePath.startsWith("defaultconfigs/")
    }
}
