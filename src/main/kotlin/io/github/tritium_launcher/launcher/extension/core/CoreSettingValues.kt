package io.github.tritium_launcher.launcher.extension.core

import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.settings.NamespacedId
import io.github.tritium_launcher.launcher.settings.SettingsMngr

private val WINDOW_SIZE_REGEX = Regex("^([1-9][0-9]{0,4})x([1-9][0-9]{0,4})$")

/**
 * Core settings keys consumed by built-in runtime code.
 */
internal object CoreSettingKeys {
    val GitPath: NamespacedId = NamespacedId("tritium", "git.path")
    val CloseDashboardOnProjectOpen: NamespacedId = NamespacedId("tritium", "projects.close_dashboard_on_open")
    val CloseGameOnExit: NamespacedId = NamespacedId("tritium", "app.close_game_on_exit")
    val ProjectOpenWindowPrompt: NamespacedId = NamespacedId("tritium", "projects.open_window_prompt")
    val ProjectOpenWindowDefault: NamespacedId = NamespacedId("tritium", "projects.open_window_default")
    val CloseProjectConfirmation: NamespacedId = NamespacedId("tritium", "projects.close_confirmation")
    val ModpackJvmArgs: NamespacedId = NamespacedId("tritium", "modpack.mc_args")
    val ModpackMemoryMb: NamespacedId = NamespacedId("tritium", "modpack.mc_memory_mb")
    val DashboardWindowSize: NamespacedId = NamespacedId("tritium", "ui.dashboard.window_size")
    val ProjectWindowDefaultSize: NamespacedId = NamespacedId("tritium", "ui.project_window.default_size")
    val GameLaunchMaximized: NamespacedId = NamespacedId("tritium", "game.maximized")
    val GameDefaultResolution: NamespacedId = NamespacedId("tritium", "game.default_resolution")
    val IncludePreReleaseMinecraftVersions: NamespacedId = NamespacedId("tritium", "minecraft.include_prerelease_versions")
    val JavaPath8: NamespacedId = NamespacedId("tritium", "java.path.8")
    val JavaPath17: NamespacedId = NamespacedId("tritium", "java.path.17")
    val JavaPath21: NamespacedId = NamespacedId("tritium", "java.path.21")
    val JavaPath25: NamespacedId = NamespacedId("tritium", "java.path.25")
    val CompanionWsHost: NamespacedId = NamespacedId("tritium", "companion.ws.host")
    val CompanionWsPort: NamespacedId = NamespacedId("tritium", "companion.ws.port")
}

/**
 * Typed readers for core settings values with safe fallbacks.
 */
internal object CoreSettingValues {
    private val logger = logger()

    enum class CloseGameOnExitPolicy {
        Never,
        Ask,
        Always
    }

    enum class ProjectOpenPromptMode {
        Never,
        Always
    }

    enum class ProjectOpenDefaultTarget {
        Current,
        New
    }

    enum class CloseProjectConfirmationPolicy {
        Never,
        Ask
    }

    /**
     * Whether dashboard should close when opening a project window.
     */
    fun closeDashboardOnProjectOpen(): Boolean =
        (SettingsMngr.currentValueOrNull(CoreSettingKeys.CloseDashboardOnProjectOpen) as? Boolean) ?: true

    /**
     * Controls whether running game processes are closed when Tritium exits.
     */
    fun closeGameOnExitPolicy(): CloseGameOnExitPolicy {
        return parseEnumSetting(
            key = CoreSettingKeys.CloseGameOnExit,
            fallback = CloseGameOnExitPolicy.Never,
            mapping = mapOf(
                "never" to CloseGameOnExitPolicy.Never,
                "ask" to CloseGameOnExitPolicy.Ask,
                "always" to CloseGameOnExitPolicy.Always
            )
        )
    }

    /**
     * Controls whether project opening should ask for current/new window target.
     */
    fun projectOpenPromptMode(): ProjectOpenPromptMode {
        return parseEnumSetting(
            key = CoreSettingKeys.ProjectOpenWindowPrompt,
            fallback = ProjectOpenPromptMode.Always,
            mapping = mapOf(
                "always" to ProjectOpenPromptMode.Always,
                "never" to ProjectOpenPromptMode.Never
            )
        )
    }

    /**
     * Default target used when project-open prompting is disabled.
     */
    fun projectOpenDefaultTarget(): ProjectOpenDefaultTarget {
        return parseEnumSetting(
            key = CoreSettingKeys.ProjectOpenWindowDefault,
            fallback = ProjectOpenDefaultTarget.Current,
            mapping = mapOf(
                "current" to ProjectOpenDefaultTarget.Current,
                "new" to ProjectOpenDefaultTarget.New
            )
        )
    }

    /**
     * Controls whether closing a project window asks for confirmation.
     */
    fun closeProjectConfirmationPolicy(): CloseProjectConfirmationPolicy {
        return parseEnumSetting(
            key = CoreSettingKeys.CloseProjectConfirmation,
            fallback = CloseProjectConfirmationPolicy.Never,
            mapping = mapOf(
                "never" to CloseProjectConfirmationPolicy.Never,
                "ask" to CloseProjectConfirmationPolicy.Ask
            )
        )
    }

    /**
     * Optional extra JVM argument string for modpack launches.
     */
    fun modpackJvmArgs(): String? = readOptionalText(CoreSettingKeys.ModpackJvmArgs)

    /**
     * Default modpack memory allocation in megabytes.
     */
    fun modpackMemoryMb(): Int {
        val fallback = 6144
        val raw = readOptionalText(CoreSettingKeys.ModpackMemoryMb) ?: return fallback
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed !in 512..262_144) {
            logger.warn("Invalid memory value '{}' for {}. Falling back to {}", raw, CoreSettingKeys.ModpackMemoryMb, fallback)
            return fallback
        }
        return parsed
    }

    /**
     * Fixed dashboard window size.
     */
    fun dashboardWindowSize(): Pair<Int, Int> =
        parseWindowSize(CoreSettingKeys.DashboardWindowSize, 650, 400)

    /**
     * Default project window size when no saved geometry exists.
     */
    fun projectWindowDefaultSize(): Pair<Int, Int> =
        parseWindowSize(CoreSettingKeys.ProjectWindowDefaultSize, 1280, 720)

    /**
     * Whether game launch should prefer maximized window behavior.
     */
    fun gameLaunchMaximized(): Boolean =
        (SettingsMngr.currentValueOrNull(CoreSettingKeys.GameLaunchMaximized) as? Boolean) ?: false

    /**
     * Default WIDTHxHEIGHT resolution used by game launch token replacement.
     */
    fun gameLaunchResolution(): Pair<Int, Int> =
        parseWindowSize(CoreSettingKeys.GameDefaultResolution, 1280, 720)

    /**
     * Whether Minecraft snapshot/pre-release/RC versions should be included in version lists.
     */
    fun includePreReleaseMinecraftVersions(): Boolean =
        (SettingsMngr.currentValueOrNull(CoreSettingKeys.IncludePreReleaseMinecraftVersions) as? Boolean) ?: false

    /**
     * Configured Java path for Minecraft 1.16.5 and below.
     */
    fun javaPath8(): String? = readOptionalText(CoreSettingKeys.JavaPath8)

    /**
     * Configured Java path for Minecraft 1.17 to 1.20.
     */
    fun javaPath17(): String? = readOptionalText(CoreSettingKeys.JavaPath17)

    /**
     * Configured Java path for Minecraft 1.21 to 1.21.11.
     */
    fun javaPath21(): String? = readOptionalText(CoreSettingKeys.JavaPath21)

    /**
     * Configured Java path for Minecraft 26.*.
     */
    fun javaPath25(): String? = readOptionalText(CoreSettingKeys.JavaPath25)

    /**
     * Returns the configured Java path for the requested major runtime.
     */
    fun javaPathForMajor(major: Int): String? = when (major) {
        8 -> javaPath8()
        17 -> javaPath17()
        21 -> javaPath21()
        25 -> javaPath25()
        else -> null
    }

    /**
     * Hostname used by Tritium when connecting to the Companion websocket.
     */
    fun companionWsHost(): String =
        readOptionalText(CoreSettingKeys.CompanionWsHost) ?: "127.0.0.1"

    /**
     * Port used by Tritium and the Companion websocket bridge.
     */
    fun companionWsPort(): Int {
        val fallback = 38765
        val raw = readOptionalText(CoreSettingKeys.CompanionWsPort) ?: return fallback
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed !in 1..65535) {
            logger.warn("Invalid websocket port '{}' for {}. Falling back to {}", raw, CoreSettingKeys.CompanionWsPort, fallback)
            return fallback
        }
        return parsed
    }

    private fun parseWindowSize(key: NamespacedId, fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> {
        val raw = (SettingsMngr.currentValueOrNull(key) as? String)?.trim().orEmpty()
        if (raw.isEmpty()) return fallbackWidth to fallbackHeight

        val match = WINDOW_SIZE_REGEX.matchEntire(raw)
        if (match == null) {
            logger.warn("Invalid window size '{}' for {}. Falling back to {}x{}", raw, key, fallbackWidth, fallbackHeight)
            return fallbackWidth to fallbackHeight
        }

        val width = match.groupValues[1].toIntOrNull()
        val height = match.groupValues[2].toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) {
            logger.warn("Invalid window size '{}' for {}. Falling back to {}x{}", raw, key, fallbackWidth, fallbackHeight)
            return fallbackWidth to fallbackHeight
        }
        return width to height
    }

    private fun readOptionalText(key: NamespacedId): String? {
        val raw = (SettingsMngr.currentValueOrNull(key) as? String)?.trim().orEmpty()
        return raw.takeIf { it.isNotBlank() }
    }

    private fun <T> parseEnumSetting(
        key: NamespacedId,
        fallback: T,
        mapping: Map<String, T>
    ): T {
        val raw = readOptionalText(key)?.lowercase() ?: return fallback
        return mapping[raw] ?: run {
            logger.warn("Invalid setting value '{}' for {}. Falling back to {}", raw, key, fallback)
            fallback
        }
    }
}
