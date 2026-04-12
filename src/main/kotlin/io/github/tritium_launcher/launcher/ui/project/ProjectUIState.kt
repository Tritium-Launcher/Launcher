package io.github.tritium_launcher.launcher.ui.project

import kotlinx.serialization.Serializable

@Serializable
@ConsistentCopyVisibility
data class ProjectUIState internal constructor(
    val tabMode: String = "SINGLE_ROW",
    val openFiles: List<String> = emptyList(),
    val sidePanels: List<SidePanelState> = emptyList(),
    val projectFilesExpandedPaths: List<String> = emptyList(),
    val projectFilesSelectedPath: String? = null,
    val mainWindowState: ByteArray? = null,
    val mainWindowGeometry: ByteArray? = null,
) {
    @Serializable
    data class SidePanelState(
        val id: String,
        val area: String,
        val visible: Boolean
    )

    companion object {
        fun fromParts(
            tabMode: String,
            openFiles: List<String>,
            sidePanels: List<SidePanelState>,
            projectFilesExpandedPaths: List<String>,
            projectFilesSelectedPath: String?,
            state: ByteArray?,
            geom: ByteArray?
        ): ProjectUIState {
            return ProjectUIState(
                tabMode = tabMode,
                openFiles = openFiles,
                sidePanels = sidePanels,
                projectFilesExpandedPaths = projectFilesExpandedPaths,
                projectFilesSelectedPath = projectFilesSelectedPath,
                mainWindowState = state,
                mainWindowGeometry = geom
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProjectUIState) return false

        if (tabMode != other.tabMode) return false
        if (openFiles != other.openFiles) return false
        if (sidePanels != other.sidePanels) return false
        if (projectFilesExpandedPaths != other.projectFilesExpandedPaths) return false
        if (projectFilesSelectedPath != other.projectFilesSelectedPath) return false
        if (!mainWindowState.contentEquals(other.mainWindowState)) return false
        if (!mainWindowGeometry.contentEquals(other.mainWindowGeometry)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tabMode.hashCode()
        result = 31 * result + openFiles.hashCode()
        result = 31 * result + sidePanels.hashCode()
        result = 31 * result + projectFilesExpandedPaths.hashCode()
        result = 31 * result + (projectFilesSelectedPath?.hashCode() ?: 0)
        result = 31 * result + (mainWindowState?.contentHashCode() ?: 0)
        result = 31 * result + (mainWindowGeometry?.contentHashCode() ?: 0)
        return result
    }
}
