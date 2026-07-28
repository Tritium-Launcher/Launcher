/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.font

import io.github.tritium_launcher.api.logger
import io.qt.gui.QFont
import io.qt.gui.QFontDatabase

object FontMngr {
    private val logger = logger()

    private var _defaultFontFamily: String? = null
    private var _monoFontFamily: String? = null
    private var _initialized = false
    private var _systemFontFamilies: List<String>? = null

    val isInitialized: Boolean get() = _initialized

    val defaultFontFamily: String
        get() = _defaultFontFamily
            ?: error("FontMngr not initialized. Call FontMngr.init() first.")

    val monoFontFamily: String
        get() = _monoFontFamily
            ?: error("FontMngr not initialized. Call FontMngr.init() first.")

    fun init() {
        if (_initialized) return

        _defaultFontFamily = loadFont("/fonts/Inter/InterVariable.ttf")
        if (_defaultFontFamily != null) {
            logger.info("Loaded Inter variable font: {}", _defaultFontFamily)
        } else {
            loadFont("/fonts/Inter/Inter.ttc")?.let { family ->
                _defaultFontFamily = family
                logger.info("Loaded Inter TTC font: {}", family)
            }
        }

        _monoFontFamily = loadFont("/fonts/JetBrainsMonoNL-Regular.ttf")
            ?: loadFont("/fonts/JetBrainsMono-Regular.ttf")
        if (_monoFontFamily != null) {
            logger.info("Loaded JetBrains Mono font: {}", _monoFontFamily)
        }

        _initialized = true
    }

    fun defaultFont(size: Int = 10): QFont {
        val family = _defaultFontFamily
        return if (family != null) QFont(family, size) else QFont("sans-serif", size)
    }

    fun availableFontFamilies(): List<String> {
        val builtin = listOfNotNull(_defaultFontFamily, _monoFontFamily)
        val system = _systemFontFamilies ?: run {
            try {
                QFontDatabase.families().also { _systemFontFamilies = it }
            } catch (e: Exception) {
                logger.warn("Failed to query system fonts", e)
                emptyList<String>().also { _systemFontFamilies = it }
            }
        }
        return (builtin + system).distinct().sorted()
    }
}
