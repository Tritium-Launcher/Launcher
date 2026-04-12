package io.github.tritium_launcher.launcher.ui.theme

import io.github.tritium_launcher.launcher.genThemeSchema
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.io.VPathWatcher
import io.github.tritium_launcher.launcher.io.VWatchEvent
import io.github.tritium_launcher.launcher.io.watch
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.qs
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.qt.core.QByteArray
import io.qt.core.QRectF
import io.qt.core.Qt
import io.qt.gui.*
import io.qt.svg.QSvgRenderer
import io.qt.widgets.QApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarFile
import java.util.prefs.Preferences
import kotlin.math.ceil
import kotlin.text.Charsets.UTF_8

/**
 * Theme manager for loading, applying, and watching theme files.
 *
 * Handles bundled and user themes, persists the selected theme, applies palette and stylesheets,
 * and provides icon/color lookup helpers for UI components.
 */
object ThemeMngr {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var currentThemeId: String = ""
        private set

    private val themes = mutableMapOf<String, ThemeFile>()
    private val bundledThemes = mutableMapOf<String, ThemeFile>()
    private val pathToId = mutableMapOf<VPath, String>()
    private val idToSourcePath = ConcurrentHashMap<String, VPath>()
    private lateinit var defaultTheme: ThemeFile
    private var defaultLightTheme: ThemeFile? = null
    val userThemesDir: VPath = VPath.get("themes").toAbsolute()
    private val schemaFile: VPath = userThemesDir.resolve("schema.json")
    private var themeWatcher: VPathWatcher? = null

    private val iconCache = ConcurrentHashMap<Quadruple<String, String, Int, Int>, QIcon>()


    // TODO: Eventually move this to whatever the main Settings system will be
    private val prefs: Preferences = Preferences.userRoot().node("/tritium")
    private const val PREF_SELECTED_THEME = "selectedThemeId"

    private val json = Json { prettyPrint = true }
    internal val logger = logger()

    fun init() {
        logger.info("Initializing Theme Manager...")
        try {
            if (!userThemesDir.exists()) userThemesDir.mkdirs()

            loadDefault()
            loadBundledThemes()
            loadUserThemes()
            restorePersistedSelectionIfAny()

            if (currentThemeId.isBlank()) {
                val chosen = when {
                    themes.containsKey(defaultTheme.meta.id) -> defaultTheme.meta.id
                    themes.isNotEmpty() -> themes.keys.first()
                    else -> defaultTheme.meta.id
                }
                setTheme(chosen)
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

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    fun loadDefault() {
        val resStream: InputStream = this::class.java.getResourceAsStream("/themes/default.json")
            ?: throw IllegalStateException("Missing bundled default theme at /themes/default.json")
        defaultTheme = ThemeLoader.loadFromStream(resStream).also { validateTheme(it) }
        themes[defaultTheme.meta.id] = defaultTheme

        // Optional bundled light fallback
        try {
            this::class.java.getResourceAsStream("/themes/light.json")?.use { s ->
                val light = ThemeLoader.loadFromStream(s).also { validateTheme(it) }
                defaultLightTheme = light
                themes.putIfAbsent(light.meta.id, light)
            }
        } catch (t: Throwable) {
            logger.warn("Failed to load bundled light theme fallback: {}", t.message)
        }
    }

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

                        logger.info("Loaded user theme '${theme.meta.id} from ${childV.fileName()}")
                    } catch (e: Exception) {
                        logger.warn("Skipping invalid theme file ${childV.fileName()}", e)
                    }
                }

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
        } catch (e: Exception) {
            logger.error("Error loading user themes", e)
        }
    }

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

    fun setTheme(id: String) {
        val theme = themes[id] ?: run {
            logger.error("Requested theme '{}' not found", id)
            defaultTheme
        }
        iconCache.clear()
        currentThemeId = theme.meta.id
        applyTheme(theme)
        persistSelectedThemeId(currentThemeId)

        val snapshot = ArrayList(listeners)
        runOnGuiThread {
            for(l in snapshot) {
                try { l() } catch (t: Throwable) { logger.warn("Theme listener failed: {}", t.message) }
            }
        }
    }

    private fun applyTheme(theme: ThemeFile) {
        runOnGuiThread {
            applyPalette(theme)
            applyStylesheets(theme)
        }
    }

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

    private fun colorOf(theme: ThemeFile, key: String): String? = theme.colors[key]

    private fun resolveColorHex(theme: ThemeFile, fallback: ThemeFile, key: String, hardFallback: String): String {
        return colorOf(theme, key)
            ?: fallback.colors[key]
            ?: defaultTheme.colors[key]
            ?: hardFallback
    }

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

    private fun resolveColor(theme: ThemeFile, key: String): QColor? {
        val fallback = defaultForType(theme.meta.type) ?: defaultTheme
        val hex = colorOf(theme, key) ?: fallback.colors[key] ?: defaultTheme.colors[key]
        return hex?.let {
            try { QColor(it) } catch (_: Exception) { null }
        }
    }

    private fun disabledColor(color: QColor, type: ThemeType): QColor {
        val c = QColor(color)
        val alpha = (c.alpha() * 0.6).toInt().coerceAtLeast(30)
        c.setAlpha(alpha)
        return when(type) {
            ThemeType.Dark -> c.lighter(130)
            ThemeType.Light -> c.darker(130)
        }
    }

    fun getIcon(iconKey: String, width: Int? = null, height: Int? = null, dpr: Double = 1.0): QIcon? {
        val theme = themes[currentThemeId] ?: defaultTheme
        val mapping = theme.icons[iconKey] ?: return null

        val baseW = width ?: 16
        val baseH = height ?: baseW

        val w = ceil(baseW * dpr).toInt().coerceAtLeast(1)
        val h = ceil(baseH * dpr).toInt().coerceAtLeast(1)

        val cacheKey = Quadruple(mapping, theme.meta.id, w, h)
        return iconCache[cacheKey] ?: loadIconFromReference(mapping, theme, baseW, baseH, dpr)?.also {
            iconCache[cacheKey] = it
        }
    }

    fun getColorHex(key: String): String? {
        val active = themes[currentThemeId] ?: themes.values.firstOrNull() ?: defaultTheme
        val fromActive = active.colors[key]
        if(!fromActive.isNullOrBlank()) return fromActive
        val typeFallback = defaultForType(active.meta.type)
        val fromType = typeFallback?.colors?.get(key)
        if(!fromType.isNullOrBlank()) return fromType
        val fromDefault = defaultTheme.colors[key]
        return fromDefault?.takeIf { it.isNotBlank() }
    }

    private fun defaultForType(type: ThemeType): ThemeFile? = when(type) {
        ThemeType.Dark -> defaultTheme
        ThemeType.Light -> defaultLightTheme ?: defaultTheme
    }

    fun getQColor(key: String): QColor? {
        val hex = getColorHex(key) ?: return null
        return try {
            QColor(hex)
        } catch (_: Exception) {
            logger.warn("Invalid color value for key '{}': {}", key, hex)
            null
        }
    }

    private fun loadIconFromReference(ref: String, theme: ThemeFile, baseW: Int, baseH: Int, dpr: Double = 1.0): QIcon? {
        try {
            val physW = ceil(baseW * dpr).toInt().coerceAtLeast(1)
            val physH = ceil(baseH * dpr).toInt().coerceAtLeast(1)

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
                var svgText = String(raw, UTF_8)

                val patternDollar = Regex("\\$\\{([^}]+)}")
                svgText = svgText.replace(patternDollar) { match ->
                    val key = match.groupValues[1]
                    theme.colors[key] ?: match.value
                }

                val patternToken = Regex("#TOKEN_([A-Za-z0-9_]+)")
                svgText = svgText.replace(patternToken) { match ->
                    val name = match.groupValues[1]
                    val keyTry1 = "Icon.$name"
                    theme.colors[keyTry1] ?: theme.colors[name] ?: match.value
                }

                if (svgText.contains("currentColor")) {
                    val currentColor = theme.colors["Icon.current"]
                        ?: theme.colors["Icon.primary"]
                        ?: theme.colors["Icon.Primary"]
                    if (currentColor != null) {
                        val svgOpenTagRegex = Regex("<svg([^>]*)>", RegexOption.IGNORE_CASE)
                        val svgOpenMatch = svgOpenTagRegex.find(svgText)
                        if (svgOpenMatch != null) {
                            val attrs = svgOpenMatch.groupValues[1]
                            val styleRegex = Regex("style\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

                            val newAttrs = if (styleRegex.containsMatchIn(attrs)) {
                                attrs.replace(styleRegex) { innerMatch ->
                                    val existing = innerMatch.groupValues[1]
                                    val updated = if (existing.trim()
                                            .endsWith(";")
                                    ) "$existing color: $currentColor" else "$existing; color: $currentColor"
                                    "style=\"$updated\""
                                }
                            } else {
                                val trimmed = attrs.trimEnd()
                                if (trimmed.isEmpty()) " style=\"color: $currentColor\"" else "$trimmed style=\"color: $currentColor\""
                            }

                            svgText = svgText.replaceRange(svgOpenMatch.range, "<svg$newAttrs>")
                        } else {
                            logger.warn(
                                "SVG contains 'currentColor' but <svg> open tag was not located for ref '{}'",
                                ref
                            )
                        }
                    }
                }

                val renderer = QSvgRenderer(QByteArray(svgText.toByteArray(UTF_8)))

                val pix = QPixmap(physW, physH)
                pix.fill(Qt.GlobalColor.transparent)
                val painter = QPainter(pix)
                try {
                    renderer.render(painter, QRectF(0.0, 0.0, physW.toDouble(), physH.toDouble()))
                } finally {
                    painter.end()
                }

                return QIcon(pix)
            } else {
                val pix = QPixmap()
                val loaded = pix.loadFromData(raw)
                if (!loaded) {
                    logger.warn("Raster icon failed to load from data for ref '{}'", ref)
                    return null
                }
                val finalPix = if(physW > 0 && physH > 0) {
                    pix.scaled(qs(physW, physH), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation)
                } else pix

                return QIcon(finalPix)
            }
        } catch (e: Exception) {
            logger.error("Failed to load icon reference '$ref': ${e.message}")
            return null
        }
    }

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

                                val base = theme.meta.base?.let { themes[it] }
                                themes[theme.meta.id] = ThemeLoader.merge(base, theme)

                                logger.info("Loaded user theme '${theme.meta.id}' from '$fileName'")
                                listeners.forEach { it() }
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

                                val base = theme.meta.base?.let { themes[it] }
                                themes[theme.meta.id] = ThemeLoader.merge(base, theme)

                                logger.info("Loaded user theme from '$fileName': '${theme.meta.id}'")
                                listeners.forEach { it() }
                            } catch (e: Exception) {
                                logger.error("Error loading created theme '$fileName'", e)
                            }
                        }

                        VWatchEvent.Kind.Delete -> {
                            try {
                                val removedId = pathToId.remove(fileV)
                                if(removedId != null) {
                                    val removedType = themes[removedId]?.meta?.type
                                    val bundled = bundledThemes[removedId]
                                    if(bundled != null) {
                                        themes[removedId] = bundled
                                        logger.info("User theme removed, restored bundled them '$removedId'")
                                    } else {
                                        themes.remove(removedId)
                                        logger.info("User theme removed, no bundled fallback removed. '$removedId'")
                                    }

                                    if(removedId == currentThemeId) {
                                        val toApply = themes[removedId] ?: defaultForType(removedType ?: ThemeType.Dark) ?: defaultTheme
                                        applyTheme(toApply)
                                        currentThemeId = toApply.meta.id
                                        listeners.forEach { it() }
                                    } else {
                                        listeners.forEach { it() }
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
                                listeners.forEach { it() }
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

    private fun validateTheme(theme: ThemeFile) {
        val id = theme.meta.id
        val name = theme.meta.name
        if (id.isBlank()) throw IllegalArgumentException("Theme meta.id must not be blank")
        if (name.isBlank()) throw IllegalArgumentException("Theme meta.name must not be blank")
    }

    private fun generateSchema(condition: Boolean) {
        if (!condition) return
        try {
            val discoveredColorKeys = mutableSetOf<String>()
            val discoveredIconKeys = mutableSetOf<String>()

            discoveredColorKeys += defaultTheme.colors.keys
            discoveredIconKeys += defaultTheme.icons.keys

            for (t in themes.values) {
                discoveredColorKeys += t.colors.keys
                discoveredIconKeys += t.icons.keys
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

    private fun buildPropertiesForDescriptor(descriptor: SerialDescriptor): JsonObject {
        val properties = mutableMapOf<String, JsonElement>()
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val child = descriptor.getElementDescriptor(i)
            properties[name] = schemaForDescriptor(child)
        }
        return JsonObject(properties)
    }

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

    fun availableThemeIds(): List<String> = themes.keys.toList()

    fun getThemeName(id: String): String? = themes[id]?.meta?.name
    fun getThemeType(id: String): ThemeType? = themes[id]?.meta?.type

    fun getThemeColorHex(id: String, key: String): String? {
        val theme = themes[id] ?: return null
        val fallback = defaultForType(theme.meta.type) ?: defaultTheme
        return theme.colors[key]
            ?: fallback.colors[key]
            ?: defaultTheme.colors[key]
    }

    private fun persistSelectedThemeId(id: String) {
        try {
            prefs.put(PREF_SELECTED_THEME, id)
            prefs.flush()
            logger.debug("Persisted selected theme id='{}'", id)
        } catch (e: Exception) {
            logger.warn("Failed to persist selected theme id='{}': {}", id, e.message)
        }
    }

    private fun loadPersistedThemeId(): String? = try {
        prefs.get(PREF_SELECTED_THEME, null)
    } catch (e: Exception) {
        logger.warn("Failed to read persisted selected theme id: {}", e.message)
        null
    }

    private fun restorePersistedSelectionIfAny() {
        val persisted = loadPersistedThemeId()
        if(!persisted.isNullOrBlank() && themes.containsKey(persisted)) {
            try {
                setTheme(persisted)
                logger.info("Restored persisted theme id='{}'", persisted)
            } catch (e: Exception) {
                logger.warn("Failed to restore persisted theme id='{}': {}", persisted, e.message)
            }
        }
    }

    private data class Quadruple<A,B,C,D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
    )
}
