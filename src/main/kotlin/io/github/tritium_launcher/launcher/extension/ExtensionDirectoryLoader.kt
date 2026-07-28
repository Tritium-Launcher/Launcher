/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.extension

import io.github.tritium_launcher.api.extension.Extension
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.ktor.utils.io.core.*
import org.koin.core.module.Module
import java.net.URLClassLoader
import java.util.*

internal object ExtensionDirectoryLoader {
    private val logger = logger()

    data class Result(val modules: List<Module>, val extensions: List<Extension>, val loaders: List<Closeable>)

    fun loadFrom(dir: VPath): Result {
        if(!dir.exists() || !dir.isDir()) {
            logger.warn("Extension directory does not exist or is not a directory: {}", dir)
            return Result(emptyList(), emptyList(), emptyList())
        }

        val modules = mutableListOf<Module>()
        val extensions = mutableListOf<Extension>()
        val loaders = mutableListOf<Closeable>()

        val jars = dir.listFiles { f -> f.isFile() && f.hasExtension("jar") }
        logger.warn("Found {} jar(s) in extension directory: {}", jars.size, jars.joinToString { it.fileName() })


        dir.listFiles { f -> f.isFile() && f.hasExtension("jar") }.forEach { jar ->
            val url = jar.toFileUriEncoded().toURL()
            val loader = URLClassLoader(
                arrayOf(url),
                Thread.currentThread().contextClassLoader ?: Extension::class.java.classLoader
            )
            try {
                val sl = ServiceLoader.load(Extension::class.java, loader)
                val found = sl.iterator().asSequence().toList()
                extensions += found
                found.forEach { modules += it.modules }
                loaders += loader
            } catch (t: Throwable) {
                logger.error("Failed to load extension from {}", jar, t)
                loader.close()
            }
        }

        return Result(modules, extensions, loaders)
    }
}
