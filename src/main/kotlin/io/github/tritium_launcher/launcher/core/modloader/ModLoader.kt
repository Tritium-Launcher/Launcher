package io.github.tritium_launcher.launcher.core.modloader

import io.github.tritium_launcher.launcher.TConstants
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.Registrable
import io.qt.gui.QPixmap
import kotlinx.serialization.json.JsonObject
import java.net.URI

/**
 * Open class for ModLoader implementations.
 *
 * Tritium will support NeoForge and Fabric. Support for other Mod Loaders may be added in the future.
 *
 * Additionally, Plugins will be able to add their own ModLoader implementations.
 */
abstract class ModLoader: Registrable {
    abstract override val id: String
    abstract val displayName: String
    abstract val repository: URI
    abstract val oldestVersion: String
    abstract val icon: QPixmap
    abstract val order: Int

    /**
     * Download and cache a loader installer or runtime for [version].
     */
    abstract suspend fun download(version: String): Boolean

    /**
     * Remove cached data for [version].
     */
    abstract suspend fun uninstall(version: String): Boolean

    /**
     * Fetch all known loader versions.
     */
    abstract suspend fun getVersions(): List<String>

    /**
     * Return loader versions compatible with a Minecraft version.
     */
    abstract suspend fun getCompatibleVersions(mcVersion: String): List<String>

    /**
     * Check whether [version] is available locally.
     */
    abstract fun isInstalled(version: String): Boolean

    /**
     * Return all locally installed versions.
     */
    abstract fun getInstalled(): List<String>

    /**
     * Return the latest available loader version.
     */
    abstract suspend fun getLatest(): String?

    /**
     * Build the download URI for a loader version.
     */
    abstract suspend fun getDownloadUri(version: String): URI?

    /**
     * Update a loader version by uninstalling and re-downloading.
     */
    abstract suspend fun update(version: String): Boolean

    /**
     * Install the loader into a project instance directory.
     *
     * @param version Loader version.
     * @param mcVersion Minecraft version for the instance.
     * @param targetDir Instance root directory.
     */
    abstract suspend fun installClient(version: String, mcVersion: String, targetDir: VPath): Boolean

    /**
     * Return a version patch JSON that can be merged into the base Minecraft version JSON.
     *
     * The patch may include keys like `libraries`, `mainClass`, and `arguments`.
     */
    abstract suspend fun getVersionPatch(version: String, mcVersion: String, targetDir: VPath): JsonObject?

    /**
     * Adjust the classpath for a loader before launch.
     */
    open fun prepareLaunchClasspath(context: LaunchContext, classpath: MutableList<String>) = Unit

    /**
     * Adjust JVM arguments for a loader before launch.
     */
    open fun prepareLaunchJvmArgs(
        context: LaunchContext,
        classpath: List<String>,
        jvmArgs: MutableList<String>
    ) = Unit

    /**
     * Whether the launcher should strip Minecraft client artifacts from module path lists.
     */
    open fun shouldStripMinecraftClientArtifacts(context: LaunchContext): Boolean = true

    companion object {
        const val INSTALL_DIR = TConstants.Dirs.LOADERS
    }

    override fun toString(): String = displayName
}

/**
 * Inputs provided to loader launch hooks.
 */
data class LaunchContext(
    val projectDir: VPath,
    val mcVersion: String,
    val loaderVersion: String,
    val mergedId: String,
    val versionJson: JsonObject
)
