package io.github.tritium_launcher.launcher.ui.project.editor.panes

import io.github.tritium_launcher.launcher.core.project.ProjectBase
import io.github.tritium_launcher.launcher.core.source.ModDependencyRef
import io.github.tritium_launcher.launcher.core.source.ModDetails
import io.github.tritium_launcher.launcher.core.source.ModSearchResult
import io.github.tritium_launcher.launcher.core.source.ModVersionOption
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
    val status: QueueStatus
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
        val detailsCache = ConcurrentHashMap<String, ModDetails>()
        val versionsCache = ConcurrentHashMap<String, List<ModVersionOption>>()
        val iconCache = ConcurrentHashMap<String, QIcon>()
        val dominantColorCache = ConcurrentHashMap<String, Triple<Int, Int, Int>>()
        val queuedDownloads = Collections.synchronizedMap(linkedMapOf<String, QueuedDownload>())
        val manuallyQueuedIds = Collections.synchronizedSet(linkedSetOf<String>())
        val queuedDetailIds = ConcurrentHashMap.newKeySet<String>()
        val resultsCache = Collections.synchronizedMap(linkedMapOf<String, ModSearchResult>())

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
