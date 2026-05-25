package io.github.tritium_launcher.launcher.ui.project

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/**
 * Custom serializer for ByteArray that stores data as an array of integers (0-255).
 * This is more robust than the default serializer which might throw on unsigned values
 * or use a format that varies between platforms/versions.
 */
object SafeByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = ListSerializer(Int.serializer()).descriptor

    override fun serialize(encoder: Encoder, value: ByteArray) {
        val ints = value.map { it.toInt() and 0xFF }
        encoder.encodeSerializableValue(ListSerializer(Int.serializer()), ints)
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        val ints = decoder.decodeSerializableValue(ListSerializer(Int.serializer()))
        return ByteArray(ints.size) { ints[it].toByte() }
    }
}

/**
 * Stored Values for a Project used for restoration
 */
@Serializable
data class ProjectUIState(
    val tabMode: String = "SINGLE_ROW",
    val openFiles: List<String> = emptyList(),
    val sidePanels: List<SidePanelState> = emptyList(),
    val projectFilesActiveViewId: String = "project_files",
    val projectFilesViewStates: List<ProjectFilesViewState> = emptyList(),
    val projectFilesExpandedPaths: List<String> = emptyList(),
    val projectFilesSelectedPath: String? = null,
    @Serializable(with = SafeByteArraySerializer::class)
    val mainWindowState: ByteArray? = null,
    @Serializable(with = SafeByteArraySerializer::class)
    val mainWindowGeometry: ByteArray? = null,
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

        fun fromParts(
            tabMode: String,
            openFiles: List<String>,
            sidePanels: List<SidePanelState>,
            projectFilesActiveViewId: String,
            projectFilesViewStates: List<ProjectFilesViewState>,
            projectFilesExpandedPaths: List<String>,
            projectFilesSelectedPath: String?,
            state: ByteArray?,
            geom: ByteArray?
        ): ProjectUIState {
            return ProjectUIState(
                tabMode = tabMode,
                openFiles = openFiles,
                sidePanels = sidePanels,
                projectFilesActiveViewId = projectFilesActiveViewId,
                projectFilesViewStates = projectFilesViewStates,
                projectFilesExpandedPaths = projectFilesExpandedPaths,
                projectFilesSelectedPath = projectFilesSelectedPath,
                mainWindowState = state,
                mainWindowGeometry = geom
            )
        }

        /**
         * Parses persisted UI state robustly using SafeByteArraySerializer.
         */
        fun parseOrNull(text: String): ProjectUIState? {
            return try {
                parser.decodeFromString<ProjectUIState>(text)
            } catch (t: Throwable) {
                // Fallback for extremely old or malformed payloads
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProjectUIState) return false

        if (tabMode != other.tabMode) return false
        if (openFiles != other.openFiles) return false
        if (sidePanels != other.sidePanels) return false
        if (projectFilesActiveViewId != other.projectFilesActiveViewId) return false
        if (projectFilesViewStates != other.projectFilesViewStates) return false
        if (projectFilesExpandedPaths != other.projectFilesExpandedPaths) return false
        if (projectFilesSelectedPath != other.projectFilesSelectedPath) return false
        if (!(mainWindowState contentEquals other.mainWindowState)) return false
        if (!(mainWindowGeometry contentEquals other.mainWindowGeometry)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tabMode.hashCode()
        result = 31 * result + openFiles.hashCode()
        result = 31 * result + sidePanels.hashCode()
        result = 31 * result + projectFilesActiveViewId.hashCode()
        result = 31 * result + projectFilesViewStates.hashCode()
        result = 31 * result + projectFilesExpandedPaths.hashCode()
        result = 31 * result + (projectFilesSelectedPath?.hashCode() ?: 0)
        result = 31 * result + (mainWindowState?.contentHashCode() ?: 0)
        result = 31 * result + (mainWindowGeometry?.contentHashCode() ?: 0)
        return result
    }
}
