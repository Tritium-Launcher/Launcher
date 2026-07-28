/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.treesitter.grammar

import io.github.treesitter.ktreesitter.Language
import java.io.File.createTempFile

object TreeSitterJavascript {
    private const val LIB_NAME = "ktreesitter-javascript"

    private val language: Language by lazy { loadLanguage() }

    fun language(): Language = language

    private fun loadLanguage(): Language {
        loadLibrary()
        return Language(tree_sitter_javascript())
    }

    private fun loadLibrary() {
        try {
            System.loadLibrary(LIB_NAME)
        } catch (_: UnsatisfiedLinkError) {
            @Suppress("UnsafeDynamicallyLoadedCode")
            System.load(libPath() ?: throw UnsatisfiedLinkError(
                "Cannot find $LIB_NAME in java.library.path or classpath resources"
            ))
        }
    }



    @JvmStatic
    private external fun tree_sitter_javascript(): Long

    private fun libPath(): String? {
        val osName = System.getProperty("os.name")!!.lowercase()
        val archName = System.getProperty("os.arch")!!.lowercase()
        val prefix: String
        val ext: String
        val os: String
        when {
            "windows" in osName -> { ext = "dll"; os = "windows"; prefix = "" }
            "linux" in osName -> { ext = "so"; os = "linux"; prefix = "lib" }
            "mac" in osName -> { ext = "dylib"; os = "macos"; prefix = "lib" }
            else -> throw UnsupportedOperationException("Unsupported OS: $osName")
        }
        val arch = when {
            "amd64" in archName || "x86_64" in archName -> "x64"
            "aarch64" in archName || "arm64" in archName -> "aarch64"
            else -> throw UnsupportedOperationException("Unsupported arch: $archName")
        }
        val libPath = "/lib/$os/$arch/$prefix$LIB_NAME.$ext"
        val libUrl = javaClass.getResource(libPath) ?: return null
        return createTempFile(prefix + LIB_NAME, ".$ext").apply {
            writeBytes(libUrl.openStream().use { it.readAllBytes() })
            deleteOnExit()
        }.path
    }
}
