/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Stored Values for a Project used for restoration
 */
@Serializable
data class ProjectUIState(
    val tabMode: String = "SINGLE_ROW",
    val openFiles: List<String> = emptyList(),
    val sidePanels: List<SidePanelState> = emptyList(),
    val projectFilesActiveViewId: String = "project",
    val projectFilesViewStates: List<ProjectFilesViewState> = emptyList(),
    val projectFilesExpandedPaths: List<String> = emptyList(),
    val projectFilesSelectedPath: String? = null,
    val mainWindowState: ByteArray? = null,
    val windowX: Int? = null,
    val windowY: Int? = null,
    val windowWidth: Int? = null,
    val windowHeight: Int? = null,
    val windowMaximized: Boolean = false,
    val windowScreenName: String? = null,
) {
    @Serializable
    data class SidePanelState(
        val id: String,
        val area: String,
        val visible: Boolean
    )

    @Serializable
    data class ProjectFilesViewState(
        val viewId: String,
        val expandedPaths: List<String> = emptyList(),
        val selectedPath: String? = null
    )

    companion object {
        private val parser = Json { 
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /**
         * Parses persisted UI state robustly using SafeByteArraySerializer.
         */
        fun parseOrNull(text: String): ProjectUIState? {
            return try {
                parser.decodeFromString<ProjectUIState>(text)
            } catch (_: Throwable) {
                // Fallback for ancient or malformed payloads
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProjectUIState

        if (windowX != other.windowX) return false
        if (windowY != other.windowY) return false
        if (windowWidth != other.windowWidth) return false
        if (windowHeight != other.windowHeight) return false
        if (windowMaximized != other.windowMaximized) return false
        if (tabMode != other.tabMode) return false
        if (openFiles != other.openFiles) return false
        if (sidePanels != other.sidePanels) return false
        if (projectFilesActiveViewId != other.projectFilesActiveViewId) return false
        if (projectFilesViewStates != other.projectFilesViewStates) return false
        if (projectFilesExpandedPaths != other.projectFilesExpandedPaths) return false
        if (projectFilesSelectedPath != other.projectFilesSelectedPath) return false
        if (!mainWindowState.contentEquals(other.mainWindowState)) return false
        if (windowScreenName != other.windowScreenName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = windowX ?: 0
        result = 31 * result + (windowY ?: 0)
        result = 31 * result + (windowWidth ?: 0)
        result = 31 * result + (windowHeight ?: 0)
        result = 31 * result + windowMaximized.hashCode()
        result = 31 * result + tabMode.hashCode()
        result = 31 * result + openFiles.hashCode()
        result = 31 * result + sidePanels.hashCode()
        result = 31 * result + projectFilesActiveViewId.hashCode()
        result = 31 * result + projectFilesViewStates.hashCode()
        result = 31 * result + projectFilesExpandedPaths.hashCode()
        result = 31 * result + (projectFilesSelectedPath?.hashCode() ?: 0)
        result = 31 * result + (mainWindowState?.contentHashCode() ?: 0)
        result = 31 * result + (windowScreenName?.hashCode() ?: 0)
        return result
    }
}
