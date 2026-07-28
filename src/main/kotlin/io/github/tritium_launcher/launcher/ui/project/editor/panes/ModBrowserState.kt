/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.modpack.ModDependencyRef
import io.github.tritium_launcher.api.modpack.ModDetails
import io.github.tritium_launcher.api.modpack.ModSearchResult
import io.github.tritium_launcher.api.modpack.ModVersionOption
import io.qt.gui.QIcon
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class QueuedDownload(
    val projectId: String,
    val title: String,
    val versionId: String,
    val versionLabel: String,
    val iconUrl: String?,
    val dependencies: List<ModDependencyRef>,
    val status: QueueStatus,
    val requiresManualDownload: Boolean = false,
    val projectUrl: String? = null,
    val fileHash: String? = null,
)

data class QueueStatus(
    val missingDependencies: List<String> = emptyList(),
    val incompatibleWith: List<String> = emptyList()
)

object ModBrowserState {
    private val projectStates = ConcurrentHashMap<String, BrowserState>()

    fun forProject(project: ProjectBase): BrowserState {
        val key = project.projectDir.toAbsolute().toString()
        return projectStates.getOrPut(key) { BrowserState() }
    }

    fun clearProject(project: ProjectBase) {
        projectStates.remove(project.projectDir.toAbsolute().toString())
    }

    class BrowserState {
        val detailsCache: MutableMap<String, ModDetails> = Collections.synchronizedMap(
            object : LinkedHashMap<String, ModDetails>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ModDetails>): Boolean = size > 256
            }
        )
        val versionsCache: MutableMap<String, List<ModVersionOption>> = Collections.synchronizedMap(
            object : LinkedHashMap<String, List<ModVersionOption>>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ModVersionOption>>): Boolean = size > 256
            }
        )
        val iconCache: MutableMap<String, QIcon> = Collections.synchronizedMap(
            object : LinkedHashMap<String, QIcon>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QIcon>): Boolean = size > 128
            }
        )
        val dominantColorCache: MutableMap<String, Triple<Int, Int, Int>> = Collections.synchronizedMap(
            object : LinkedHashMap<String, Triple<Int, Int, Int>>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<Int, Int, Int>>): Boolean = size > 256
            }
        )
        val queuedDownloads = Collections.synchronizedMap(linkedMapOf<String, QueuedDownload>())
        val manuallyQueuedIds = Collections.synchronizedSet(linkedSetOf<String>())
        val queuedDetailIds = ConcurrentHashMap.newKeySet<String>()
        val resultsCache: MutableMap<String, ModSearchResult> = Collections.synchronizedMap(
            object : LinkedHashMap<String, ModSearchResult>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ModSearchResult>): Boolean = size > 64
            }
        )

        fun clearSearchState() {
            resultsCache.clear()
            queuedDetailIds.clear()
        }

        fun clearAll() {
            detailsCache.clear()
            versionsCache.clear()
            iconCache.clear()
            dominantColorCache.clear()
            queuedDownloads.clear()
            manuallyQueuedIds.clear()
            queuedDetailIds.clear()
            resultsCache.clear()
        }
    }
}
