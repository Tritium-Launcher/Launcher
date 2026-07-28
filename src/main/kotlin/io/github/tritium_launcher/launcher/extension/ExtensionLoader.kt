/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.extension

import io.github.tritium_launcher.api.extension.Extension
import io.github.tritium_launcher.api.logger
import io.qt.core.QByteArray
import io.qt.gui.QIcon
import io.qt.gui.QPixmap
import org.koin.core.module.Module
import java.util.*

object ExtensionLoader {
    private val logger = logger()
    private var cachedClasspath: List<Extension>? = null

    var allExtensions: List<Extension> = emptyList()
        internal set

    fun discover(): List<Extension> {
        if (cachedClasspath == null) {
            val classLoader = Thread.currentThread().contextClassLoader
                ?: Extension::class.java.classLoader

            cachedClasspath = ServiceLoader.load(Extension::class.java, classLoader)
                .iterator()
                .asSequence()
                .toList()

            logger.info(
                "Discovered {} extensions: {}",
                cachedClasspath!!.size,
                cachedClasspath!!.joinToString { it.javaClass.simpleName }
            )
        }
        return cachedClasspath!!
    }

    fun discoveredModules(): List<Module> = discover().flatMap { it.modules }

    fun loadExtensionIcon(ext: Extension): QIcon? {
        val paths = listOf("extensions/${ext.namespace}/tr-icon.png", "tr-icon.png")

        for (path in paths) {
            val stream = ext.javaClass.classLoader
                .getResourceAsStream(path) ?: continue
            return runCatching {
                val bytes = stream.readBytes()
                val pixmap = QPixmap()
                pixmap.loadFromData(QByteArray(bytes))
                QIcon(pixmap)
            }.getOrNull() ?: continue
        }

        return null
    }
}
