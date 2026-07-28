/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.theme

import io.github.tritium_launcher.api.*
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.io.VPathWatcher
import io.github.tritium_launcher.api.io.VWatchEvent
import io.github.tritium_launcher.api.io.watch
import io.github.tritium_launcher.api.settings.CategoryPath
import io.github.tritium_launcher.api.settings.NamespacedId
import io.github.tritium_launcher.api.settings.SettingNode
import io.github.tritium_launcher.api.theme.ThemeFile
import io.github.tritium_launcher.api.theme.ThemeType
import io.github.tritium_launcher.launcher.genThemeSchema
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.dashboard.ThemesPanel
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.bundledThemes
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.defaultLightTheme
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.defaultTheme
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.disabledColor
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.generateSchema
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.iconCache
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.loadIconFromReference
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.schemaFile
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.themesWithOwnColors
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr.themesWithOwnIcons
import io.qt.core.QByteArray
import io.qt.core.QRectF
import io.qt.core.QSize
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.svg.QSvgRenderer
import io.qt.widgets.QApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import kotlin.math.ceil
import kotlin.math.min
import kotlin.text.Charsets.UTF_8

/**
 * Theme manager for loading, applying, and watching theme files.
 *
 * Colors and icons are independently selectable: [currentColorThemeId] controls palette/QSS rendering,
 * [currentIconSetId] controls which icon files are loaded. A single [io.github.tritium_launcher.api.theme.ThemeFile] can contribute to both
 * (e.g. `default` provides both colors and icons) or just one.
 *
 * ## Fallback chain (colors)
 * active color theme → type-appropriate fallback (dark → `default`, light → `light.json`)
 * → `defaultTheme` → hard-coded `#FF00FF`
 *
 * ## Fallback chain (icons)
 * active icon set's `icons` map → `defaultTheme.icons` → classpath/filesystem search
 * (see [getPixmap] and [loadIconFromReference]).
 */
object ThemeMngr {
    /** Currently selected color theme — controls QPalette, QSS, and TColors resolution. */
    private val _currentColorThemeId = MutableStateFlow("")
    val currentColorThemeId: StateFlow<String> = _currentColorThemeId.asStateFlow()
    val currentColorThemeIdValue: String
        get() = _currentColorThemeId.value

    /** Currently selected icon set — controls which icon files are loaded via [getPixmap]. */
    private val _currentIconSetId = MutableStateFlow("")
    val currentIconSetId: StateFlow<String> = _currentIconSetId.asStateFlow()
    val currentIconSetIdValue: String
        get() = _currentIconSetId.value

    /** Convenience combined signal — emits `"colors:{id}|icons:{id}"` when either changes. */
    private val _currentThemeId = MutableStateFlow("")
    val currentThemeId: StateFlow<String> = _currentThemeId.asStateFlow()
    val currentThemeIdValue: String
        get() = _currentThemeId.value

    /** All loaded themes (bundled + user), keyed by [io.github.tritium_launcher.api.theme.ThemeMeta.id], post-merge. */
    private val themes = mutableMapOf<String, ThemeFile>()

    /** Snapshot of the bundled-only themes, used as fallback when a user theme is deleted. */
    private val bundledThemes = mutableMapOf<String, ThemeFile>()

    /**
     * Theme IDs whose raw file declared a non-empty `colors` block.
     * Used by [availableColorThemeIds] — after merge every valid theme has every color,
     * so we need this to know which ones *originally* owned their colors.
     */
    private val themesWithOwnColors = mutableSetOf<String>()

    /**
     * Theme IDs whose raw file declared a non-empty `icons` block.
     * Used by [availableIconSetIds] — same rationale as [themesWithOwnColors].
     */
    private val themesWithOwnIcons = mutableSetOf<String>()

    /** Maps user-theme file paths to their theme ID. */
    private val pathToId = mutableMapOf<VPath, String>()

    /** Maps theme ID back to its source file path (used by [loadIconFromReference] for file resolution). */
    private val idToSourcePath = ConcurrentHashMap<String, VPath>()

    /** The hard-coded fallback theme (must always exist at `/themes/default.json`). */
    private lateinit var defaultTheme: ThemeFile

    /** Optional light fallback (`/themes/light.json`) — used when the active color theme is [io.github.tritium_launcher.api.theme.ThemeType.Light]. */
    private var defaultLightTheme: ThemeFile? = null

    /** User themes directory (`~/tritium/themes/`). */
    val userThemesDir: VPath = fromTR("themes")

    /** Schema file written when the `-gts` CLI flag is passed. */
    private val schemaFile: VPath = userThemesDir.resolve("schema.json")

    /** Filesystem watcher on [userThemesDir] for live theme reloading. */
    private var themeWatcher: VPathWatcher? = null

    /**
     * Icon pixmap cache.
     * Key is [io.github.tritium_launcher.api.Quadruple]`(fileReference, iconSetId, physWidth, physHeight)`.
     */
    private const val MAX_CACHE_ENTRIES = 500
    private val iconCache: MutableMap<Quadruple<String, String, Int, Int>, QPixmap> = Collections.synchronizedMap(
        object : LinkedHashMap<Quadruple<String, String, Int, Int>, QPixmap>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Quadruple<String, String, Int, Int>, QPixmap>): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    private val colorThemeSetting: SettingNode<String> by lazy {
        val cat = CategoryPath.root(NamespacedId("tritium", "theme"))
        if (SettingsMngr.findCategory(cat) == null) {
            SettingsMngr.category("tritium", "theme") { title = "Theme" }
        }
        SettingsMngr.widget("tritium", cat, "selected_color_theme") {
            title = "Selected Color Theme"
            defaultValue = defaultTheme.meta.id
            serializer = String.serializer()
            widgetFactory = { ctx -> colorThemeSettingWidget(ctx) }
        }
    }
    private val iconSetSetting: SettingNode<String> by lazy {
        val cat = CategoryPath.root(NamespacedId("tritium", "theme"))
        if (SettingsMngr.findCategory(cat) == null) {
            SettingsMngr.category("tritium", "theme") { title = "Theme" }
        }
        SettingsMngr.widget("tritium", cat, "selected_icon_set") {
            title = "Selected Icon Set"
            defaultValue = defaultTheme.meta.id
            serializer = String.serializer()
            widgetFactory = { ctx -> iconSetSettingWidget(ctx) }
        }
    }

    private val json = Json { prettyPrint = true }
    internal val logger = logger()

    /**
     * Initialize the Theme Manager.
     *
     * Steps:
     * - Load Default theme.
     * - Load Bundled themes.
     * - Load User themes.
     * - Restore selected theme from [SettingsMngr]. If blank, sets active Theme as default theme.
     * - Generate Theme JSON schema, if enabled.
     * - Start watching User Themes directory
     */
    fun init() {
        logger.info("Initializing Theme Manager...")
        try {
            if (!userThemesDir.exists()) userThemesDir.mkdirs()

            loadDefault()
            loadBundledThemes()
            loadUserThemes()

            SettingsMngr.currentValue(colorThemeSetting).value.takeIf { it.isNotBlank() && themes.containsKey(it) }?.let { setColorTheme(it) }
            SettingsMngr.currentValue(iconSetSetting).value.takeIf { it.isNotBlank() && themes.containsKey(it) }?.let { setIconSet(it) }

            if (_currentColorThemeId.value.isBlank()) {
                val chosen = when {
                    themes.containsKey(defaultTheme.meta.id) -> defaultTheme.meta.id
                    themes.isNotEmpty() -> themes.keys.first()
                    else -> defaultTheme.meta.id
                }
                setTheme(chosen)
            }

            if (_currentIconSetId.value.isBlank()) {
                _currentIconSetId.value = _currentColorThemeId.value
            }

            generateSchema(genThemeSchema)

            startWatcherThread()

            logger.info("Found themes:")
            themes.keys.toList().forEach { t ->
                logger.info("$t\n")
            }
            logger.info("Finished initializing Theme Manager.")
        } catch (e: Exception) {
            logger.error("Theme Manager init failed", e)
            try {
                setTheme(defaultTheme.meta.id)
            } catch (_: Exception) {}
        }
    }

    /**
     * Loads Default theme
     */
    fun loadDefault() {
        val resStream: InputStream = this::class.java.getResourceAsStream("/themes/default.json")
            ?: throw IllegalStateException("Missing bundled default theme at /themes/default.json")
        defaultTheme = ThemeLoader.loadFromStream(resStream).also { validateTheme(it) }
        themes[defaultTheme.meta.id] = defaultTheme
        if (defaultTheme.colors.isNotEmpty()) themesWithOwnColors += defaultTheme.meta.id
        if (defaultTheme.icons.isNotEmpty()) themesWithOwnIcons += defaultTheme.meta.id

        // Optional bundled light fallback
        try {
            this::class.java.getResourceAsStream("/themes/light.json")?.use { s ->
                val light = ThemeLoader.loadFromStream(s).also { validateTheme(it) }
                defaultLightTheme = light
                themes.putIfAbsent(light.meta.id, light)
                if (light.colors.isNotEmpty()) themesWithOwnColors += light.meta.id
                if (light.icons.isNotEmpty()) themesWithOwnIcons += light.meta.id
            }
        } catch (t: Throwable) {
            logger.warn("Failed to load bundled light theme fallback: {}", t.message)
        }
    }

    /**
     * Load theme files from `~/tritium/themes/`. Each valid JSON file is parsed, tracked in
     * [themesWithOwnColors]/[themesWithOwnIcons], then merged with its base theme if one is declared.
     */
    private fun loadUserThemes() {
        try {
            val defaultId = defaultTheme.meta.id
            if(!themes.containsKey(defaultId)) themes[defaultId] = defaultTheme

            pathToId.clear()
            idToSourcePath.clear()

            userThemesDir.list()
                .filter { child ->
                    child.isFile() && child.fileName().lowercase().endsWith(".json")
                }
                .forEach { childV ->
                    try {
                        val theme = ThemeLoader.loadFromFile(childV)

                        validateTheme(theme)

                        themes[theme.meta.id] = theme
                        pathToId[childV] = theme.meta.id
                        idToSourcePath[theme.meta.id] = childV
                        if (theme.colors.isNotEmpty()) themesWithOwnColors += theme.meta.id
                        if (theme.icons.isNotEmpty()) themesWithOwnIcons += theme.meta.id

                        logger.info("Loaded user theme '${theme.meta.id} from ${childV.fileName()}")
                    } catch (e: Exception) {
                        logger.warn("Skipping invalid theme file ${childV.fileName()}", e)
                    }
                }

            synchronized(themes) {
                val copy = HashMap(themes)
                for ((id, theme) in copy) {
                    val baseId = theme.meta.base
                    if (baseId != null && themes.containsKey(baseId)) {
                        val base = themes[baseId]
                        if (base != null) {
                            val merged = ThemeLoader.merge(base, theme)
                            themes[id] = merged
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error loading user themes", e)
        }
    }

    /**
     * Load themes bundled in the classpath under `/themes/` (excluding `default.json` which is
     * loaded separately by [loadDefault]).
     *
     * Supports both file-based and JAR-based classpath resources.
     * Bundled themes are kept in [bundledThemes] as a fallback when a user theme with the same
     * ID is deleted.
     */
    private fun loadBundledThemes() {
        val pref = "themes/"
        val clazz = this::class.java
        val dirUrl = clazz.getResource("/$pref")

        if (dirUrl == null) {
            logger.debug("No bundled themes directory found on classpath at '/{}'", pref)
            return
        }

        try {
            when (dirUrl.protocol) {
                "file" -> {
                    try {
                        val dir = VPath.get(dirUrl.toURI())
                        if (dir.exists() && dir.isDir()) {
                            dir.listFiles { f -> f.isFile() && f.hasExtension("json") }.forEach { f ->
                                try {
                                    if (f.isFileName("default.json")) return@forEach
                                    val theme = ThemeLoader.loadFromFile(f)
                                    validateTheme(theme)
                                    bundledThemes[theme.meta.id] = theme
                                    themes.putIfAbsent(theme.meta.id, theme)
                                    if (theme.colors.isNotEmpty()) themesWithOwnColors += theme.meta.id
                                    if (theme.icons.isNotEmpty()) themesWithOwnIcons += theme.meta.id
                                    logger.info("Loaded bundled theme from file: ${f.fileName()} (id='${theme.meta.id}')")
                                } catch (e: Exception) {
                                    logger.info("Skipping invalid bundled theme file ${f.fileName()}: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn("Could not load bundled themes from file:// resource: ${e.message}")
                    }
                }

                "jar" -> {
                    try {
                        val urlStr = dirUrl.toString()
                        val jarPathPart = urlStr.substringAfter("jar:").substringBefore("!/")
                        val jarPath =
                            URLDecoder.decode(jarPathPart.removePrefix("file:"), StandardCharsets.UTF_8.name())
                        JarFile(jarPath).use { jar ->
                            val entries = jar.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                val name = entry.name
                                if (name.startsWith(pref) && name.endsWith(".json")) {
                                    if (name.equals("$pref/default.json")) continue
                                    val resStream = clazz.getResourceAsStream("/$name")
                                    if (resStream != null) {
                                        try {
                                            val theme = ThemeLoader.loadFromStream(resStream)
                                            validateTheme(theme)
                                            bundledThemes[theme.meta.id] = theme
                                            themes.putIfAbsent(theme.meta.id, theme)
                                            if (theme.colors.isNotEmpty()) themesWithOwnColors += theme.meta.id
                                            if (theme.icons.isNotEmpty()) themesWithOwnIcons += theme.meta.id
                                            logger.info("Loaded bundled theme from JAR resource: $name (id='${theme.meta.id}')")
                                        } catch (ex: Exception) {
                                            logger.warn("Skipping invalid bundled theme resource $name: ${ex.message}")
                                        } finally {
                                            resStream.close()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        logger.warn("Could not enumerate bundled themes inside jar: ${ex.message}")
                    }
                }

                else -> {
                    try {
                        val resources = clazz.classLoader.getResources(pref)
                        while (resources.hasMoreElements()) {
                            val url = resources.nextElement()
                            logger.debug("Found classpath resource for themes: {}", url)
                        }
                    } catch (ex: Exception) {
                        logger.warn("Failed to enumerate bundled themes via classloader: ${ex.message}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("::loadBundledThemesFromResources failed", e)
        }
    }

    /**
     * Set both color theme and icon set to the same ID.
     * Equivalent to calling [setColorTheme] and [setIconSet] with the same argument.
     */
    fun setTheme(id: String) {
        setColorTheme(id)
        setIconSet(id)
    }

    /**
     * Switch the active color theme. Applies QPalette and QSS stylesheet from the theme's
     * colors, and stores the selection via [SettingsMngr].
     *
     * The icon set is unaffected — call [setIconSet] separately to change it.
     */
    fun setColorTheme(id: String) {
        val theme = themes[id] ?: run {
            logger.error("Requested color theme '{}' not found", id)
            return
        }
        _currentColorThemeId.value = theme.meta.id
        _currentThemeId.value = "colors:${theme.meta.id}|icons:${_currentIconSetId.value}"
        runOnGuiThread {
            applyPalette(theme)
            applyStylesheets(theme)
        }
        SettingsMngr.updateValue(colorThemeSetting, theme.meta.id)
    }

    /**
     * Switch the active icon set. Evicts the icon cache for the previous set, pre-warps
     * icons for the new set, and stores the selection via [SettingsMngr].
     *
     * The color theme is unaffected — call [setColorTheme] separately to change it.
     */
    fun setIconSet(id: String) {
        val theme = themes[id] ?: run {
            logger.error("Requested icon set '{}' not found", id)
            return
        }
        val oldId = _currentIconSetId.value
        if (oldId.isNotBlank()) {
            synchronized(iconCache) {
                iconCache.keys.removeIf { it.second == oldId }
            }
        }
        _currentIconSetId.value = theme.meta.id
        _currentThemeId.value = "colors:${_currentColorThemeId.value}|icons:${theme.meta.id}"
        loadThemeIcons()
        SettingsMngr.updateValue(iconSetSetting, theme.meta.id)
    }

    /**
     * Map Qt [QPalette.ColorRole]s from the theme's color keys with derived fallbacks.
     * Disabled color groups get reduced alpha via [disabledColor].
     */
    private fun applyPalette(theme: ThemeFile) {
        val base = QApplication.palette()
        val pal = QPalette(base)
        val type = theme.meta.type

        fun resolved(key: String): QColor? = resolveColor(theme, key)
        fun setRole(role: QPalette.ColorRole, color: QColor?) {
            if (color == null) return
            pal.setColor(QPalette.ColorGroup.Active, role, color)
            pal.setColor(QPalette.ColorGroup.Inactive, role, color)
            pal.setColor(QPalette.ColorGroup.Disabled, role, disabledColor(color, type))
        }

        val surface0 = resolved("Surface0")
        val surface1 = resolved("Surface1") ?: surface0
        val text = resolved("Text")
        val button = resolved("Button") ?: surface1 ?: surface0
        val selectedUI = resolved("SelectedUI") ?: resolved("Accent")
        val selectedText = resolved("SelectedText") ?: text
        val accent = resolved("Accent")
        val placeholder = resolved("Subtext") ?: text?.darker(125)
        val tooltipBg = resolved("Tooltip") ?: surface1 ?: surface0
        val warning = resolved("Warning") ?: accent?.lighter(110)
        val success = resolved("Success") ?: accent

        // Derived fallbacks to cover all roles
        val window = surface0 ?: surface1
        val baseBg = surface1 ?: surface0
        val altBase = surface0 ?: surface1
        val shadow = surface0?.darker(150) ?: base.color(QPalette.ColorRole.Shadow)
        val mid = surface0 ?: surface1 ?: base.color(QPalette.ColorRole.Mid)
        val dark = surface0?.darker(120) ?: base.color(QPalette.ColorRole.Dark)
        val light = button?.lighter(120) ?: surface1?.lighter(120) ?: base.color(QPalette.ColorRole.Light)
        val midlight = button?.lighter(110) ?: surface1?.lighter(110) ?: base.color(QPalette.ColorRole.Midlight)

        setRole(QPalette.ColorRole.Window, window ?: pal.color(QPalette.ColorRole.Window))
        setRole(QPalette.ColorRole.WindowText, text ?: pal.color(QPalette.ColorRole.WindowText))

        setRole(QPalette.ColorRole.Base, baseBg ?: pal.color(QPalette.ColorRole.Base))
        setRole(QPalette.ColorRole.AlternateBase, altBase ?: pal.color(QPalette.ColorRole.AlternateBase))

        setRole(QPalette.ColorRole.ToolTipBase, tooltipBg ?: pal.color(QPalette.ColorRole.ToolTipBase))
        setRole(QPalette.ColorRole.ToolTipText, text ?: pal.color(QPalette.ColorRole.ToolTipText))

        setRole(QPalette.ColorRole.Text, text ?: pal.color(QPalette.ColorRole.Text))
        setRole(QPalette.ColorRole.PlaceholderText, placeholder ?: pal.color(QPalette.ColorRole.PlaceholderText))

        setRole(QPalette.ColorRole.Button, button ?: pal.color(QPalette.ColorRole.Button))
        setRole(QPalette.ColorRole.ButtonText, text ?: pal.color(QPalette.ColorRole.ButtonText))

        setRole(QPalette.ColorRole.Light, light ?: pal.color(QPalette.ColorRole.Light))
        setRole(QPalette.ColorRole.Midlight, midlight ?: pal.color(QPalette.ColorRole.Midlight))
        setRole(QPalette.ColorRole.Mid, mid)
        setRole(QPalette.ColorRole.Dark, dark ?: pal.color(QPalette.ColorRole.Dark))
        setRole(QPalette.ColorRole.Shadow, shadow)

        setRole(QPalette.ColorRole.Highlight, selectedUI ?: pal.color(QPalette.ColorRole.Highlight))
        setRole(QPalette.ColorRole.HighlightedText, selectedText ?: pal.color(QPalette.ColorRole.HighlightedText))

        setRole(QPalette.ColorRole.Link, accent ?: pal.color(QPalette.ColorRole.Link))
        setRole(QPalette.ColorRole.LinkVisited, accent?.darker(115) ?: warning ?: pal.color(QPalette.ColorRole.LinkVisited))

        // Secondary mappings for clarity
        setRole(QPalette.ColorRole.BrightText, success ?: text ?: pal.color(QPalette.ColorRole.BrightText))
        setRole(QPalette.ColorRole.ToolTipText, text ?: pal.color(QPalette.ColorRole.ToolTipText))
        // Keep additional semantic colors available via palette brushes for custom widgets
        setRole(QPalette.ColorRole.Highlight, selectedUI ?: warning ?: accent ?: pal.color(QPalette.ColorRole.Highlight))

        QApplication.setPalette(pal)
    }

    /**
     * Apply Theme values to Stylesheets
     */
    private fun applyStylesheets(theme: ThemeFile) {
        try {
            val fallback = defaultForType(theme.meta.type) ?: defaultTheme
            val baseStyles = buildBaseWidgetStylesheet(theme, fallback)
            val compiledTheme = theme.stylesheets.values.joinToString("\n") { tpl ->
                tpl.replace(Regex("\\$\\{([^}]+)}")) { m ->
                    val key = m.groupValues[1]
                    colorOf(theme, key) ?: fallback.colors[key] ?: defaultTheme.colors[key] ?: "#FF00FF"
                }
            }
            val compiled = if (compiledTheme.isBlank()) baseStyles else "$baseStyles\n$compiledTheme"
            QApplication.instance()?.styleSheet = compiled
        } catch (e: Exception) {
            logger.error("Failed to apply stylesheet for theme '{}': {}", theme.meta.id, e.message)
        }
    }

    /**
     * Get Colors from specified Theme
     */
    private fun colorOf(theme: ThemeFile, key: String): String? = theme.colors[key]

    /**
     * Gets Hex values from Theme colors
     */
    private fun resolveColorHex(theme: ThemeFile, fallback: ThemeFile, key: String, hardFallback: String): String {
        return colorOf(theme, key)
            ?: fallback.colors[key]
            ?: defaultTheme.colors[key]
            ?: hardFallback
    }

    /**
     * Builds a global stylesheet for some Widgets
     */
    private fun buildBaseWidgetStylesheet(theme: ThemeFile, fallback: ThemeFile): String {
        val surface1 = resolveColorHex(theme, fallback, "Surface1", "#303030")
        val text = resolveColorHex(theme, fallback, "Text", "#F5F5F5")
        val subtext = resolveColorHex(theme, fallback, "Subtext", text)
        val selectedUi = resolveColorHex(
            theme,
            fallback,
            "SelectedUI",
            resolveColorHex(theme, fallback, "Accent", "#2E436E")
        )
        val selectedText = resolveColorHex(theme, fallback, "SelectedText", text)

        val lineEditBg = colorOf(theme, "LineEdit.Bg")
            ?: fallback.colors["LineEdit.Bg"]
            ?: defaultTheme.colors["LineEdit.Bg"]
            ?: surface1
        val lineEditFg = colorOf(theme, "LineEdit.Fg")
            ?: fallback.colors["LineEdit.Fg"]
            ?: defaultTheme.colors["LineEdit.Fg"]
            ?: text

        return """
            QLineEdit,
            QTextEdit,
            QPlainTextEdit {
                background-color: $lineEditBg;
                color: $lineEditFg;
                selection-background-color: $selectedUi;
                selection-color: $selectedText;
            }

            QLineEdit {
                placeholder-text-color: $subtext;
            }
        """.trimIndent()
    }

    /**
     * Get a [QColor] from a Theme, using the color's key
     */
    private fun resolveColor(theme: ThemeFile, key: String): QColor? {
        val fallback = defaultForType(theme.meta.type) ?: defaultTheme
        val hex = colorOf(theme, key) ?: fallback.colors[key] ?: defaultTheme.colors[key]
        return hex?.let {
            try { QColor(it) } catch (_: Exception) { null }
        }
    }

    /**
     * Reduce alpha and lighten/darken for the [QPalette.ColorGroup.Disabled] state.
     */
    private fun disabledColor(color: QColor, type: ThemeType): QColor {
        val c = QColor(color)
        val alpha = (c.alpha() * 0.6).toInt().coerceAtLeast(30)
        c.setAlpha(alpha)
        return when(type) {
            ThemeType.Dark -> c.lighter(130)
            ThemeType.Light -> c.darker(130)
        }
    }

    /**
     * Load an icon pixmap from the active icon set.
     *
     * Resolution chain:
     * 1. Look up [iconKey] in the active icon set's `icons` map.
     * 2. If missing, fall back to [defaultTheme]'s `icons` map.
     * 3. If still missing, return `null`.
     *
     * Results are cached in [iconCache] keyed by `(fileReference, iconSetId, w, h)`.
     *
     * @param iconKey  Logical icon key.
     * @param width  Desired width in device-independent pixels.
     * @param height  Desired height in device-independent pixels.
     * @param dpr  Device pixel ratio for HiDPI.
     */
    fun getPixmap(iconKey: String, width: Int? = null, height: Int? = null, dpr: Double = 1.0): QPixmap? {
        val iconTheme = themes[_currentIconSetId.value] ?: defaultTheme
        val mapping = iconTheme.icons[iconKey] ?: defaultTheme.icons[iconKey] ?: return null

        val baseW = width ?: 16
        val baseH = height ?: baseW

        val w = ceil(baseW * dpr).toInt().coerceAtLeast(1)
        val h = ceil(baseH * dpr).toInt().coerceAtLeast(1)

        val cacheKey = Quadruple(mapping, _currentIconSetId.value, w, h)
        synchronized(iconCache) {
            iconCache[cacheKey]?.let { return it }
            val pix = loadIconFromReference(mapping, iconTheme, w, h, dpr) ?: return null
            iconCache[cacheKey] = pix
            return pix
        }
    }

    fun getIcon(iconKey: String, width: Int? = null, height: Int? = null, dpr: Double = 1.0): QIcon? {
        return getPixmap(iconKey, width, height, dpr)?.let { QIcon(it) }
    }

    /**
     * Resolve a color hex string from the active color theme.
     *
     * Fallback chain:
     * 1. Active color theme's `colors` map.
     * 2. Type-appropriate fallback.
     * 3. `defaultTheme`'s `colors` map.
     * 4. `null`.
     */
    fun getColorHex(key: String): TCol? {
        val active = themes[_currentColorThemeId.value] ?: defaultTheme
        val fromActive = active.colors[key]
        if(!fromActive.isNullOrBlank()) return TCol(fromActive)
        val typeFallback = defaultForType(active.meta.type)
        val fromType = typeFallback?.colors?.get(key)
        if(!fromType.isNullOrBlank()) return TCol(fromType)
        val fromDefault = defaultTheme.colors[key]
        return fromDefault?.takeIf { it.isNotBlank() }?.let { TCol(it) }
    }

    /**
     * Return the appropriate fallback [ThemeFile] based on [ThemeType].
     * Dark themes fall back to [defaultTheme], light themes fall back to [defaultLightTheme].
     */
    private fun defaultForType(type: ThemeType): ThemeFile? = when(type) {
        ThemeType.Dark -> defaultTheme
        ThemeType.Light -> defaultLightTheme ?: defaultTheme
    }

    /**
     * Resolve a [QColor] from the active color theme.
     */
    fun getQColor(key: String): QColor? {
        val hex = getColorHex(key) ?: return null
        return try {
            QColor(hex.value)
        } catch (_: Exception) {
            logger.warn("Invalid color value for key '{}': {}", key, hex)
            null
        }
    }

    /**
     * Resolve an icon file reference to a [QPixmap].
     *
     * Search order for relative paths:
     * 1. Theme's own source directory.
     * 2. Theme's `icons/` subdirectory.
     * 3. User themes directory (`~/tritium/themes/`).
     * 4. Base theme's directory and `icons/` subdirectory.
     * 5. Classpath `/themes/{themeId}/`.
     * 6. Classpath `/themes/{defaultThemeId}/`.
     * 7. Bare classpath root.
     *
     * @param ref  Icon file path as declared in the theme's `icons` map.
     * @param theme  The icon set's [ThemeFile].
     * @param physW  Desired physical pixel width.
     * @param physH  Desired physical pixel height.
     * @param dpr  Device pixel ratio.
     */
    private fun loadIconFromReference(ref: String, theme: ThemeFile, physW: Int, physH: Int, dpr: Double = 1.0): QPixmap? {
        try {

            val tried = mutableListOf<String>()
            val candidates = mutableListOf<InputStream?>()

            fun tryClasspath(path: String) {
                tried += "classpath:$path"
                candidates += this::class.java.getResourceAsStream(path)
            }
            fun tryClasspathN(path: String) {
                tried += "classpath-no-slash:$path"
                candidates += this::class.java.classLoader.getResourceAsStream(path)
            }
            fun tryFs(path: VPath) {
                tried += "file:$path"
                candidates += if(path.exists() && path.isFile()) {
                    path.inputStream()
                } else {
                    null
                }
            }

            // Classpath
            if(ref.startsWith("resource:")) {
                val r = ref.removePrefix("resource:")
                tryClasspath(if(r.startsWith("/")) r else "/$r")
                tryClasspathN(r.removePrefix("/"))
            } else if(ref.startsWith("/")) {
                // Absolute path
                try {
                    tryFs(VPath.get(ref))
                } catch (_: Throwable) {}
            } else {
                // Try Theme parent dir first, then userThemesDir, then classpath
                val themeSource = idToSourcePath[theme.meta.id]
                if(themeSource != null) {
                    val themeDir = themeSource.parent()
                    try {
                        tryFs(themeDir.resolve(ref))
                    } catch (_: Throwable) {}

                    try {
                        tryFs(themeDir.resolve("icons").resolve(ref.removePrefix("icons/")))
                    } catch (_: Throwable) {}
                }

                try {
                    tryFs(userThemesDir.resolve(ref))
                } catch (_: Throwable) {}

                val baseId = theme.meta.base
                if (baseId != null) {
                    val baseSource = idToSourcePath[baseId]
                    if (baseSource != null) {
                        val baseDir = baseSource.parent()
                        try {
                            tryFs(baseDir.resolve(ref))
                        } catch (_: Throwable) {}
                        try {
                            tryFs(baseDir.resolve("icons").resolve(ref.removePrefix("icons/")))
                        } catch (_: Throwable) {}
                    }
                    tryClasspath("/themes/$baseId/$ref")
                    tryClasspathN("themes/$baseId/$ref")
                }

                tryClasspath("/themes/${theme.meta.id}/$ref")
                tryClasspathN("themes/${theme.meta.id}/$ref")

                if (theme.meta.id != defaultTheme.meta.id && baseId != defaultTheme.meta.id) {
                    tryClasspath("/themes/${defaultTheme.meta.id}/$ref")
                    tryClasspathN("themes/${defaultTheme.meta.id}/$ref")
                }

                tryClasspath("/$ref")
                tryClasspathN(ref)
            }

            val stream = candidates.firstOrNull { it != null } ?: run {
                logger.warn("Icon reference '$ref' not found (no candidate source)")
                return null
            }

            val raw = stream.use { it.readBytes() }
            val peek = String(raw, 0, minOf(raw.size, 512), UTF_8).lowercase()
            val isSvg = peek.contains("<svg")

            if (isSvg) {
                val renderer = QSvgRenderer(QByteArray(raw))
                val srcSize  = renderer.defaultSize()
                val fit = fitRect(srcSize, physW, physH)
                val pix = QPixmap(qs(physW, physH))
                pix.fill(Qt.GlobalColor.transparent)
                val painter = QPainter(pix)
                renderer.render(painter, fit)
                painter.end()
                pix.setDevicePixelRatio(dpr)
                return pix
            } else {
                val pix = QPixmap()
                val loaded = pix.loadFromData(raw)
                if (!loaded) {
                    logger.warn("Raster icon failed to load from state for ref '{}'", ref)
                    return null
                }
                val finalPix = if(physW > 0 && physH > 0) {
                    pix.scaled(qs(physW, physH), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
                } else pix
                try { finalPix.setDevicePixelRatio(dpr) } catch (_: Throwable) {}

                return finalPix
            }
        } catch (e: Exception) {
            logger.error("Failed to load icon reference '$ref': ${e.message}")
            return null
        }
    }

    /**
     * Compute a centered, aspect-ratio-preserving rectangle within [targetW]×[targetH].
     */
    private fun fitRect(sourceSize: QSize, targetW: Int, targetH: Int): QRectF {
        val srcW = sourceSize.width().takeIf { it > 0 } ?: targetW
        val srcH = sourceSize.height().takeIf { it > 0 } ?: targetH
        val scale = min(targetW.toDouble() / srcW.toDouble(), targetH.toDouble() / srcH.toDouble())
        val drawW = srcW * scale
        val drawH = srcH * scale
        val x = (targetW - drawW) / 2.0
        val y = (targetH - drawH) / 2.0
        return QRectF(x, y, drawW, drawH)
    }

    /**
     * Start watching [userThemesDir] for theme file changes.
     * Handles Create/Modify/Delete/Overflow events — (re)loads themes, merges inheritance,
     * updates tracking sets, and re-applies if the active selection was affected.
     * Runs on a background thread; operations that touch the GUI are deferred to the main thread.
     */
    private fun startWatcherThread() {
        try { themeWatcher?.close() } catch (_: Exception) {}
        themeWatcher = try {
            userThemesDir.watch({ e ->
                try {
                    val fileV = e.path
                    val fileName = fileV.fileName()

                    when(e.kind) {
                        VWatchEvent.Kind.Create -> {
                            try {
                                val theme = ThemeLoader.loadFromFile(fileV)
                                validateTheme(theme)
                                themes[theme.meta.id] = theme
                                pathToId[fileV] = theme.meta.id
                                if (theme.colors.isNotEmpty()) themesWithOwnColors += theme.meta.id
                                if (theme.icons.isNotEmpty()) themesWithOwnIcons += theme.meta.id

                                val base = theme.meta.base?.let { themes[it] }
                                themes[theme.meta.id] = ThemeLoader.merge(base, theme)

                                logger.info("Loaded user theme '${theme.meta.id}' from '$fileName'")
                            } catch (e: Exception) {
                                logger.error("Exception loading created theme '$fileName'", e)
                            }
                        }

                        VWatchEvent.Kind.Modify -> {
                            try {
                                val theme = ThemeLoader.loadFromFile(fileV)
                                validateTheme(theme)
                                themes[theme.meta.id] = theme
                                pathToId[fileV] = theme.meta.id
                                if (theme.colors.isNotEmpty()) themesWithOwnColors += theme.meta.id
                                if (theme.icons.isNotEmpty()) themesWithOwnIcons += theme.meta.id

                                val base = theme.meta.base?.let { themes[it] }
                                themes[theme.meta.id] = ThemeLoader.merge(base, theme)

                                logger.info("Loaded user theme from '$fileName': '${theme.meta.id}'")
                            } catch (e: Exception) {
                                logger.error("Error loading created theme '$fileName'", e)
                            }
                        }

                        VWatchEvent.Kind.Delete -> {
                            try {
                                val removedId = pathToId.remove(fileV)
                                if(removedId != null) {
                                    themesWithOwnColors.remove(removedId)
                                    themesWithOwnIcons.remove(removedId)
                                    val removedType = themes[removedId]?.meta?.type
                                    val bundled = bundledThemes[removedId]
                                    if(bundled != null) {
                                        themes[removedId] = bundled
                                        if (bundled.colors.isNotEmpty()) themesWithOwnColors += bundled.meta.id
                                        if (bundled.icons.isNotEmpty()) themesWithOwnIcons += bundled.meta.id
                                        logger.info("User theme removed, restored bundled them '$removedId'")
                                    } else {
                                        themes.remove(removedId)
                                        logger.info("User theme removed, no bundled fallback removed. '$removedId'")
                                    }

                                    if(removedId == _currentColorThemeId.value || removedId == _currentIconSetId.value) {
                                        val toApply = themes[removedId] ?: defaultForType(removedType ?: ThemeType.Dark) ?: defaultTheme
                                        if (removedId == _currentColorThemeId.value) {
                                            setColorTheme(toApply.meta.id)
                                        }
                                        if (removedId == _currentIconSetId.value) {
                                            setIconSet(toApply.meta.id)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logger.error("Exception handling deleted theme '$fileName'", e)
                            }
                        }

                        VWatchEvent.Kind.Overflow -> {
                            logger.warn("Theme watcher overflow for directory '$userThemesDir'")

                            try {
                                loadUserThemes()
                            } catch (e: Exception) {
                                logger.error("Failed to rescan themes after overflow", e)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    logger.error("Unhandled exception in theme watcher callback", t)
                }
            })
        } catch (e: Exception) {
            logger.error("Failed to start theme watcher on '$userThemesDir'", e)
            null
        }
    }

    /**
     * Validate a loaded theme. Throws if [io.github.tritium_launcher.api.theme.ThemeMeta.id] or [io.github.tritium_launcher.api.theme.ThemeMeta.name] is blank.
     */
    private fun validateTheme(theme: ThemeFile) {
        val id = theme.meta.id
        val name = theme.meta.name
        if (id.isBlank()) throw IllegalArgumentException("Theme meta.id must not be blank")
        if (name.isBlank()) throw IllegalArgumentException("Theme meta.name must not be blank")
    }

    /**
     * Generate a JSON Schema file for the theme system at [schemaFile].
     * Only writes if [condition] is true
     * Discovers all color and icon keys from all loaded themes.
     *
     * TODO: Replace with GitHub actions
     */
    private fun generateSchema(condition: Boolean) {
        if (!condition) return
        try {
            val discoveredColorKeys = mutableSetOf<String>()
            val discoveredIconKeys = mutableSetOf<String>()

            discoveredColorKeys += defaultTheme.colors.keys
            discoveredIconKeys += defaultTheme.icons.keys

            for ((_, colors, icons) in themes.values) {
                discoveredColorKeys += colors.keys
                discoveredIconKeys += icons.keys
            }

            val colorValueSchema = buildJsonObject {
                put("type", JsonPrimitive("string"))
                put(
                    "pattern",
                    JsonPrimitive("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$|^rgba?\\(\\s*\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}(?:\\s*,\\s*(0|1|0?\\.\\d+))?\\s*\\)$")
                )
            }

            val colorsProperties = discoveredColorKeys.sorted().associateWith { colorValueSchema as JsonElement }
            val colorsObjectSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(colorsProperties))
                put("additionalProperties", JsonPrimitive(true))
            }

            val iconValueSchema = buildJsonObject { put("type", JsonPrimitive("string")) }
            val iconsProperties = discoveredIconKeys.sorted().associateWith { iconValueSchema as JsonElement }
            val iconsObjectSchema = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(iconsProperties))
                put("additionalProperties", JsonPrimitive(true))
            }

            val baseProps = buildPropertiesForDescriptor(ThemeFile.serializer().descriptor).toMutableMap()
            baseProps["colors"] = colorsObjectSchema
            baseProps["icons"] = iconsObjectSchema

            val schemaRoot = buildJsonObject {
                put("\$schema", JsonPrimitive("http://json-schema.org/draft-07/schema#"))
                put("title", JsonPrimitive("Tritium Theme Schema"))
                put("type", JsonPrimitive("object"))
                put("properties", JsonObject(baseProps))
                put("required", JsonArray(listOf(JsonPrimitive("meta"))))
                put("additionalProperties", JsonPrimitive(false))
            }

            userThemesDir.mkdirs()
            val bytes = json.encodeToString(JsonObject.serializer(), schemaRoot).toByteArray()
            schemaFile.writeBytesAtomic(bytes)
            logger.info("Wrote theme schema to $schemaFile with ${discoveredColorKeys.size} color keys and ${discoveredIconKeys.size} icon keys")
        } catch (e: Exception) {
            logger.error("Failed to generate/write theme schema", e)
        }
    }

    /**
     * Build Descriptor properties for JSON Schema
     *
     * @see generateSchema
     */
    private fun buildPropertiesForDescriptor(descriptor: SerialDescriptor): JsonObject {
        val properties = mutableMapOf<String, JsonElement>()
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val child = descriptor.getElementDescriptor(i)
            properties[name] = schemaForDescriptor(child)
        }
        return JsonObject(properties)
    }

    /**
     * Creates a [JsonElement] for theme schema
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun schemaForDescriptor(descriptor: SerialDescriptor): JsonElement {
        return when (val kind = descriptor.kind) {
            is PrimitiveKind -> when (kind) {
                PrimitiveKind.BOOLEAN -> JsonObject(mapOf("type" to JsonPrimitive("boolean")))
                PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                    JsonObject(mapOf("type" to JsonPrimitive("integer")))

                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                    JsonObject(mapOf("type" to JsonPrimitive("number")))

                PrimitiveKind.CHAR, PrimitiveKind.STRING ->
                    JsonObject(mapOf("type" to JsonPrimitive("string")))

            }

            is StructureKind -> when (kind) {
                StructureKind.LIST -> {
                    val elementDesc = descriptor.getElementDescriptor(0)
                    JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to schemaForDescriptor(elementDesc)))
                }

                StructureKind.MAP -> {
                    val valueDesc = descriptor.getElementDescriptor(1)
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "additionalProperties" to schemaForDescriptor(valueDesc)
                        )
                    )
                }

                StructureKind.CLASS, StructureKind.OBJECT -> {
                    val props = mutableMapOf<String, JsonElement>()
                    val required = mutableListOf<JsonElement>()
                    for (i in 0 until descriptor.elementsCount) {
                        val nm = descriptor.getElementName(i)
                        val d = descriptor.getElementDescriptor(i)
                        props[nm] = schemaForDescriptor(d)
                        val isOptional = descriptor.isElementOptional(i)
                        val isNullable = d.isNullable
                        if (!isOptional && !isNullable) required.add(JsonPrimitive(nm))
                    }
                    val map = mutableMapOf(
                        "type" to JsonPrimitive("object"),
                        "properties" to JsonObject(props)
                    )
                    if (required.isNotEmpty()) map["required"] = JsonArray(required)
                    JsonObject(map)
                }

            }

            is PolymorphicKind -> {
                JsonObject(mapOf("type" to JsonPrimitive("object")))
            }

            else -> {
                if (kind == SerialKind.ENUM) {
                    val choices = descriptor.elementNames.map { JsonPrimitive(it) }
                    JsonObject(mapOf("type" to JsonPrimitive("string"), "enum" to JsonArray(choices)))
                } else {
                    JsonObject(mapOf("type" to JsonPrimitive("string")))
                }
            }
        }
    }

    /**
     * Set up the icon cache for the current icon set by loading all icons at common sizes.
     */
    fun loadThemeIcons(sizes: List<Int> = listOf(16, 32), dpr: Double = 1.0) {
        val theme = themes[_currentIconSetId.value] ?: return
        if (theme.icons.isEmpty()) return
        Thread {
            for (key in theme.icons.keys) {
                for (size in sizes) {
                    getPixmap(key, size, size, dpr)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    /** Returns all loaded theme IDs. */
    fun availableThemeIds(): List<String> = themes.keys.toList()

    /** Returns IDs of themes that originally declared a `colors` block (pre-merge). */
    fun availableColorThemeIds(): List<String> = themes.keys.filter { it in themesWithOwnColors }

    /** Returns IDs of themes that originally declared an `icons` block (pre-merge). */
    fun availableIconSetIds(): List<String> = themes.keys.filter { it in themesWithOwnIcons }

    /**
     * Re-apply the current selections from scratch.
     * Re-applies QPalette + QSS from the active color theme, evicts and re-warps the icon
     * cache from the active icon set. Useful after [QApplication.setStyle] or external
     * settings changes that may have reset the palette.
     */
    fun refresh() {
        val colorTheme = themes[_currentColorThemeId.value] ?: defaultTheme
        val iconTheme = themes[_currentIconSetId.value] ?: defaultTheme
        runOnGuiThread {
            applyPalette(colorTheme)
            applyStylesheets(colorTheme)
        }
        val oldIconId = _currentIconSetId.value
        synchronized(iconCache) {
            iconCache.keys.removeIf { it.second == oldIconId }
        }
        _currentIconSetId.value = iconTheme.meta.id
        _currentThemeId.value = "colors:${_currentColorThemeId.value}|icons:${iconTheme.meta.id}"
        loadThemeIcons()
    }

    /** Look up a theme's display name by ID. */
    fun getThemeName(id: String): String? = themes[id]?.meta?.name

    /** Look up a theme's [ThemeType] by ID. */
    fun getThemeType(id: String): ThemeType? = themes[id]?.meta?.type

    /**
     * Resolve a color hex value from a specific theme (not necessarily the active one).
     * Used by [ThemesPanel] to render color swatches for each theme in the picker.
     *
     * Fallback chain: requested theme → type-appropriate fallback → default.
     */
    fun getThemeColorHex(id: String, key: String): String? {
        val theme = themes[id] ?: return null
        val fallback = defaultForType(theme.meta.type) ?: defaultTheme
        return theme.colors[key]
            ?: fallback.colors[key]
            ?: defaultTheme.colors[key]
    }
}
