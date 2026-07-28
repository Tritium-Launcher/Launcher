/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

/**
 * Host bootstrap helpers for starting and stopping the core service container.
 *
 * Builds the registry core module, discovers extension modules (ServiceLoader and extension
 * directory), starts Koin, and then freezes registries to prevent late registrations.
 */
package io.github.tritium_launcher.launcher.bootstrap

import io.github.tritium_launcher.api.TConstants
import io.github.tritium_launcher.api.core.TritiumEvent
import io.github.tritium_launcher.api.core.onEvent
import io.github.tritium_launcher.api.extension.Extension
import io.github.tritium_launcher.api.extension.ExtensionStateMngr
import io.github.tritium_launcher.api.keymap.KeyBinding
import io.github.tritium_launcher.api.keymap.Keystroke
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.registry.RegistryMngr
import io.github.tritium_launcher.launcher.appInstance
import io.github.tritium_launcher.launcher.extension.ExtensionDirectoryLoader
import io.github.tritium_launcher.launcher.extension.ExtensionLoader
import io.github.tritium_launcher.launcher.extension.core.CoreExtension
import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.keymap.*
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.logging.LogDialogMngr
import io.github.tritium_launcher.launcher.ui.project.editor.treesitter.TreeSitterService
import io.github.tritium_launcher.launcher.ui.search.SearchEverywhereDialog
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.github.tritium_launcher.launcher.ui.widgets.constructor_functions.qWidget
import io.ktor.utils.io.core.*
import io.qt.core.QMetaObject
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QCursor
import io.qt.gui.QTextCursor
import io.qt.widgets.QApplication
import io.qt.widgets.QTextEdit
import io.qt.widgets.QWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.logger.slf4jLogger

private object Bootstrap
private val logger = logger(Bootstrap::class)

private val registryCoreModule = module {
    single { RegistryMngr }
    single { SettingsMngr }
}

/**
 * Starts the host container and returns class loaders for directory-based extensions.
 *
 * @param loadExtDir Directory containing extension jars to load via [ExtensionDirectoryLoader].
 * @return Closeables for extension class loaders; pass to [stopHost] to release resources.
 */
internal fun startHost(): List<Closeable> {
    val core = listOf(registryCoreModule)
    val loaders = mutableListOf<Closeable>()

    val discovered = ExtensionLoader.discover()
    val dirResult = ExtensionDirectoryLoader.loadFrom(TConstants.EXT_DIR)
    loaders += dirResult.loaders

    val allExtensions = (discovered + dirResult.extensions + CoreExtension)
        .distinctBy { it.namespace }
    ExtensionLoader.allExtensions = allExtensions

    val extState = ExtensionStateMngr.load()
    val enabledExtensions = allExtensions.filter { it.isBuiltin || extState.getOrDefault(it.namespace, true) }

    enabledExtensions.forEach { ext ->
        runCatching {
            ext.onRegister()
            logger(Extension::class).info("Loaded Extension '{}'", ext.namespace)
        }.onFailure { logger.error("Extension '{}' failed during bootstrap", ext.namespace, it) }
    }

    val extModules = enabledExtensions.flatMap { it.modules }

    startKoin {
        slf4jLogger()
        modules(core + extModules)
    }

    val registryMngr = GlobalContext.get().get<RegistryMngr>()
    registryMngr.freezeAll()

    TreeSitterService.init()

    enabledExtensions.forEach { ext ->
        runCatching { ext.onEnable() }
            .onFailure { logger.error("Extension '{}' failed during enable", ext.namespace, it) }
    }

    return loaders
}

/**
 * Stops the host container and closes any extension class loaders.
 */
internal fun stopHost(loaders: List<Closeable> = emptyList()) {
    ExtensionLoader.allExtensions.reversed().forEach { ext ->
        runCatching { ext.onDisable() }
            .onFailure { logger.error("Extension '{}' failed during disable", ext.namespace, it) }
    }
    loaders.forEach { it.close() }
    stopKoin()
}

internal fun startSettings() {
    fun applyKeymapOverrides(e: TritiumEvent.SettingChanged) {
        val raw = (e.newValue as? String)?.trim().orEmpty()
        if(raw.isBlank()) return
        runCatching { Json.decodeFromString(
            MapSerializer(String.serializer(), ListSerializer(String.serializer())),
            raw
        ) }.onSuccess { overrides ->
            KeymapMngr.applyOverridesFromStrings(overrides)
        }
    }

    CoroutineScope(Dispatchers.Main).onEvent<TritiumEvent.SettingChanged> { e ->
        when ("${e.namespace}:${e.nodeKey}") {
            CoreSettingKeys.UiBackgroundImage.toString() -> ThemeMngr.refresh()
            CoreSettingKeys.KeymapActionsOverview.toString() -> applyKeymapOverrides(e)
        }
    }
}

internal fun startKeymap() {
     fun resolveFocusGroupFromWidgetTree(): String? {
        var node: QWidget? = QApplication.focusWidget()
        while (node != null) {
            val property = node.property("keymapFocusGroup")?.toString()?.trim()
            if (!property.isNullOrBlank()) return property
            node = node.parentWidget()
        }
        return null
    }

    KeymapBootstrap.initializeDefaults()
    ActionRegistry.register(
        id = "logs.open_dialog",
        label = "Open Log Viewer",
    ) {
        LogDialogMngr.openDialog()
    }
    KeymapMngr.declareDefault(
        "logs.open_dialog",
        KeyBinding.Single(Keystroke.ctrlShift(Qt.Key.Key_I.value()))
    )
    ActionRegistry.register(
        id = "search.open_everywhere",
        label = "Search Everywhere",
    ) {
        SearchEverywhereDialog.open()
    }
    KeymapMngr.declareDefault(
        "search.open_everywhere",
        KeyBinding.Single(Keystroke.ctrl(Qt.Key.Key_Space.value()))
    )
    var highlightWidget: QWidget? = null
    var highlightOverlay: QWidget = qWidget().apply {
        setStyleSheet("background: rgba(0, 170, 255, 40); border: 2px solid #00aaff;")
        setAttribute(Qt.WidgetAttribute.WA_TransparentForMouseEvents, true)
        hide()
    }
    var highlightTimer: QTimer? = null
    ActionRegistry.registerHandler(
        id = "screenshot.hovered_widget",
        allowKeyboardShortcuts = true,
        focusGroups = setOf(KeymapFocusMngr.GLOBAL),
        executesOnRelease = true,
        pressHandler = {
            val timer = QTimer().apply { interval = 50 }
            timer.timeout.connect(QMetaObject.Slot0 {
                val current = QApplication.widgetAt(QCursor.pos())
                val prev = highlightWidget
                if (current !== prev) {
                    if (current != null) {
                        highlightOverlay.setParent(current)
                        highlightOverlay.setGeometry(current.rect())
                        highlightOverlay.raise()
                    }
                    highlightWidget = current
                }
                if (highlightWidget != null) {
                    highlightOverlay.show()
                }
            })
            timer.start()
            highlightTimer = timer
            val widget = QApplication.widgetAt(QCursor.pos())
            if (widget != null) {
                highlightOverlay.setParent(widget)
                highlightOverlay.setGeometry(widget.rect())
                highlightOverlay.raise()
                highlightOverlay.show()
            }
            highlightWidget = widget
        },
        handler = {
            highlightTimer?.stop()
            highlightTimer = null
            highlightOverlay.hide()
            highlightWidget = null
            val widget = QApplication.widgetAt(QCursor.pos())
            if (widget != null) {
                val pixmap = widget.grab()
                QApplication.clipboard()?.setImage(pixmap.toImage())
                logger.info(widget.objectName)
            }
        }
    )
    KeymapMngr.declareDefault(
        "screenshot.hovered_widget",
        KeyBinding.Single(Keystroke.plain(Qt.Key.Key_F8.value()))
    )
    ActionRegistry.registerHandler(
        id = "editor.start_new_line",
        allowKeyboardShortcuts = true,
        focusGroups = setOf("editor")
    ) {
        (QApplication.focusWidget() as? QTextEdit)?.let { edit ->
            val cursor = edit.textCursor()
            cursor.movePosition(QTextCursor.MoveOperation.EndOfLine)
            edit.setTextCursor(cursor)
            edit.insertPlainText("\n")
        }
    }
    KeymapMngr.declareDefault(
        "editor.start_new_line",
        KeyBinding.Single(Keystroke(Qt.Key.Key_Return.value(), Qt.KeyboardModifier.ShiftModifier.value()))
    )
    val keymapDispatcher = KeymapDispatcher(ActionRegistry)
    appInstance?.installEventFilter(keymapDispatcher)
    KeymapFocusMngr.registerResolver("qt.focus_widget") {
        resolveFocusGroupFromWidgetTree()
    }
    KeymapMngr.initWithPersistence()
}
