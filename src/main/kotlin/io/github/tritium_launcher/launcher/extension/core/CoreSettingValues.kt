package io.github.tritium_launcher.launcher.extension.core

import io.github.tritium_launcher.launcher.font.FontMngr
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.settings.*

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
    val ModpackJvmArgs: NamespacedId = NamespacedId("tritium", "source.mc_args")
    val ModpackMemoryMb: NamespacedId = NamespacedId("tritium", "source.mc_memory_mb")
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
    val EditorAutoSave: NamespacedId = NamespacedId("tritium", "editor.auto_save")
    val EditorAutoSaveInterval: NamespacedId = NamespacedId("tritium", "editor.auto_save_interval")
    val EditorUnsavedIndicatorIntensity: NamespacedId = NamespacedId("tritium", "editor.unsaved_indicator_intensity")
    val EditorRainbowBrackets: NamespacedId = NamespacedId("tritium", "editor.rainbow_brackets")
    val ProjectFilesConfigSort: NamespacedId = NamespacedId("tritium", "projects.files.config_sort")
    val UiGameTooltipStyle: NamespacedId = NamespacedId("tritium", "ui.tooltip_style")
    val UiAnimateScrolling: NamespacedId = NamespacedId("tritium", "ui.animate_scrolling")
    val SeasonalEventsEnabled: NamespacedId = NamespacedId("tritium", "ui.seasonal_events")
    val UiBackgroundImage: NamespacedId = NamespacedId("tritium", "ui.background_image")
    val KeymapActionsOverview: NamespacedId = NamespacedId("tritium", "keymap.actions_overview")

    val GlobalFont: NamespacedId = NamespacedId("tritium", "ui.global_font")
    val EditorFont: NamespacedId = NamespacedId("tritium", "ui.editor_font")
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

    enum class UnsavedIndicatorIntensity {
        Low,
        High
    }

    enum class ProjectFilesConfigSortMode {
        Alphabetical,
        FileType
    }

    /**
     * Optional background image path applied globally to main windows.
     */
    val uiBackgroundImage by optionalTextSetting(CoreSettingKeys.UiBackgroundImage)

    /**
     * Whether auto-save is enabled for modified editor files.
     */
    val editorAutoSave by setting(CoreSettingKeys.EditorAutoSave, false)

    /**
     * Interval in seconds for editor auto-save.
     */
    fun editorAutoSaveInterval(): Int {
        val fallback = 60
        val raw = readOptionalText(CoreSettingKeys.EditorAutoSaveInterval) ?: return fallback
        return raw.toIntOrNull()?.coerceIn(1, 86400) ?: fallback
    }

    /**
     * Intensity of the unsaved changes indicator in editor tabs.
     */
    val editorUnsavedIndicatorIntensity by enumSetting(
        key = CoreSettingKeys.EditorUnsavedIndicatorIntensity,
        fallback = UnsavedIndicatorIntensity.Low,
        mapping = mapOf(
            "low" to UnsavedIndicatorIntensity.Low,
            "high" to UnsavedIndicatorIntensity.High
        )
    )

    /**
     * Whether rainbow brackets are enabled in code editors.
     */
    val editorRainbowBrackets by setting(CoreSettingKeys.EditorRainbowBrackets, false)

    /**
     * Sort mode used for the project's /config directory in the files tree.
     */
    val projectFilesConfigSortMode by enumSetting(
        key = CoreSettingKeys.ProjectFilesConfigSort,
        fallback = ProjectFilesConfigSortMode.Alphabetical,
        mapping = mapOf(
            "alphabetical" to ProjectFilesConfigSortMode.Alphabetical,
            "file_type" to ProjectFilesConfigSortMode.FileType
        )
    )

    /**
     * Whether Tritium tooltips should be styled like MC tooltips, or QT default.
     */
    val uiGameTooltipStyle by setting(CoreSettingKeys.UiGameTooltipStyle, true)

    /**
     * Whether wheel-driven scrolling should animate across scrollable UI.
     */
    val uiAnimateScrolling by setting(CoreSettingKeys.UiAnimateScrolling, true)

    /**
     * Whether seasonal events changes are active.
     */
    val seasonalEventsEnabled by setting(CoreSettingKeys.SeasonalEventsEnabled, true)

    /**
     * Whether dashboard should close when opening a project window.
     */
    val closeDashboardOnProjectOpen by setting(CoreSettingKeys.CloseDashboardOnProjectOpen, true)

    /**
     * Controls whether running game processes are closed when Tritium exits.
     */
    val closeGameOnExitPolicy by enumSetting(
        key = CoreSettingKeys.CloseGameOnExit,
        fallback = CloseGameOnExitPolicy.Never,
        mapping = mapOf(
            "never" to CloseGameOnExitPolicy.Never,
            "ask" to CloseGameOnExitPolicy.Ask,
            "always" to CloseGameOnExitPolicy.Always
        )
    )

    /**
     * Controls whether project opening should ask for current/new window target.
     */
    val projectOpenPromptMode by enumSetting(
        key = CoreSettingKeys.ProjectOpenWindowPrompt,
        fallback = ProjectOpenPromptMode.Always,
        mapping = mapOf(
            "always" to ProjectOpenPromptMode.Always,
            "never" to ProjectOpenPromptMode.Never
        )
    )

    /**
     * Default target used when project-open prompting is disabled.
     */
    val projectOpenDefaultTarget by enumSetting(
        key = CoreSettingKeys.ProjectOpenWindowDefault,
        fallback = ProjectOpenDefaultTarget.Current,
        mapping = mapOf(
            "current" to ProjectOpenDefaultTarget.Current,
            "new" to ProjectOpenDefaultTarget.New
        )
    )

    /**
     * Controls whether closing a project window asks for confirmation.
     */
    val closeProjectConfirmationPolicy by enumSetting(
        key = CoreSettingKeys.CloseProjectConfirmation,
        fallback = CloseProjectConfirmationPolicy.Never,
        mapping = mapOf(
            "never" to CloseProjectConfirmationPolicy.Never,
            "ask" to CloseProjectConfirmationPolicy.Ask
        )
    )

    /**
     * Optional extra JVM argument string for source launches.
     */
    val modpackJvmArgs by optionalTextSetting(CoreSettingKeys.ModpackJvmArgs)

    /**
     * Default source memory allocation in megabytes.
     */
    fun modpackMemoryMb(): Int {
        val fallback = 6144
        val raw = (SettingsMngr.currentValueOrNull(CoreSettingKeys.ModpackMemoryMb) as? String)?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
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
    val gameLaunchMaximized by setting(CoreSettingKeys.GameLaunchMaximized, false)

    /**
     * Default WIDTHxHEIGHT resolution used by game launch token replacement.
     */
    fun gameLaunchResolution(): Pair<Int, Int> =
        parseWindowSize(CoreSettingKeys.GameDefaultResolution, 1280, 720)

    /**
     * Whether Minecraft snapshot/pre-release/RC versions should be included in version lists.
     */
    val includePreReleaseMinecraftVersions by setting(CoreSettingKeys.IncludePreReleaseMinecraftVersions, false)

    /**
     * Configured Java path for Minecraft 1.16.5 and below.
     */
    val javaPath8 by optionalTextSetting(CoreSettingKeys.JavaPath8)

    /**
     * Configured Java path for Minecraft 1.17 to 1.20.
     */
    val javaPath17 by optionalTextSetting(CoreSettingKeys.JavaPath17)

    /**
     * Configured Java path for Minecraft 1.21 to 1.21.11.
     */
    val javaPath21 by optionalTextSetting(CoreSettingKeys.JavaPath21)

    /**
     * Configured Java path for Minecraft 26.*.
     */
    val javaPath25 by optionalTextSetting(CoreSettingKeys.JavaPath25)

    /**
     * Returns the configured Java path for the requested major runtime.
     */
    fun javaPathForMajor(major: Int): String? = when (major) {
        8 -> javaPath8
        17 -> javaPath17
        21 -> javaPath21
        25 -> javaPath25
        else -> null
    }

    /**
     * Hostname used by Tritium when connecting to the Companion websocket.
     */
    val companionWsHost by setting(CoreSettingKeys.CompanionWsHost, "127.0.0.1") {
        (it as? String)?.trim()?.takeIf { s -> s.isNotBlank() }
    }

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

    private val FONT_SETTING_REGEX = Regex("^(.*)\\|([1-9][0-9]{0,2})$")

    private fun parseFontSetting(key: NamespacedId, defaultFamily: String, defaultSize: Int): Pair<String, Int> {
        val raw = readOptionalText(key) ?: return defaultFamily to defaultSize
        val match = FONT_SETTING_REGEX.matchEntire(raw)
        if (match == null) {
            logger.warn("Invalid font setting '{}' for {}. Falling back to {}|{}", raw, key, defaultFamily, defaultSize)
            return defaultFamily to defaultSize
        }
        val family = match.groupValues[1].takeIf { it.isNotBlank() } ?: defaultFamily
        return family to match.groupValues[2].toInt()
    }

    private fun encodeFontSetting(family: String, size: Int): String = "$family|$size"

    fun globalFont(): Pair<String, Int> = parseFontSetting(
        CoreSettingKeys.GlobalFont, FontMngr.defaultFontFamily, 10
    )

    fun editorFont(): Pair<String, Int> = parseFontSetting(
        CoreSettingKeys.EditorFont, FontMngr.monoFontFamily, 10
    )

    private fun parseWindowSize(key: NamespacedId, fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> {
        val raw = readOptionalText(key).orEmpty()
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
}
