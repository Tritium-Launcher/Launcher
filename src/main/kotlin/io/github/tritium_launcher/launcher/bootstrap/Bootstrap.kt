/**
 * Host bootstrap helpers for starting and stopping the core service container.
 *
 * Builds the registry core module, discovers extension modules (ServiceLoader and extension
 * directory), starts Koin, and then freezes registries to prevent late registrations.
 */
package io.github.tritium_launcher.launcher.bootstrap

import io.github.tritium_launcher.launcher.appInstance
import io.github.tritium_launcher.launcher.extension.ExtensionDirectoryLoader
import io.github.tritium_launcher.launcher.extension.ExtensionLoader
import io.github.tritium_launcher.launcher.extension.ExtensionStateManager
import io.github.tritium_launcher.launcher.extension.core.CoreExtension
import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.keymap.*
import io.github.tritium_launcher.launcher.registry.RegistryMngr
import io.github.tritium_launcher.launcher.settings.SettingValueChangedEvent
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.github.tritium_launcher.launcher.ui.logging.LogDialogMngr
import io.github.tritium_launcher.launcher.ui.theme.ThemeMngr
import io.ktor.utils.io.core.*
import io.qt.core.Qt
import io.qt.gui.QTextCursor
import io.qt.widgets.QApplication
import io.qt.widgets.QTextEdit
import io.qt.widgets.QWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.logger.slf4jLogger

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
internal fun startHost(loadExtDir: VPath): List<Closeable> {
    val core = listOf(registryCoreModule)
    val loaders = mutableListOf<Closeable>()

    val discovered = ExtensionLoader.discover()
    val dirResult = ExtensionDirectoryLoader.loadFrom(loadExtDir)
    loaders += dirResult.loaders

    val allExtensions = discovered + dirResult.extensions + CoreExtension
    ExtensionLoader.allExtensions = allExtensions

    val extState = ExtensionStateManager.load()
    val enabledExtensions = allExtensions.filter { it.isBuiltin || extState.getOrDefault(it.namespace, true) }
    val extModules = enabledExtensions.flatMap { it.modules }

    startKoin {
        slf4jLogger()
        modules(core + extModules)
    }

    val registryMngr = GlobalContext.get().get<RegistryMngr>()
    registryMngr.freezeAll()

    return loaders
}

/**
 * Stops the host container and closes any extension class loaders.
 */
internal fun stopHost(loaders: List<Closeable> = emptyList()) {
    loaders.forEach { it.close() }
    stopKoin()
}

internal fun startSettings() {
    fun applyKeymapOverrides(e: SettingValueChangedEvent<*>) {
        val raw = (e.newValue as? String)?.trim().orEmpty()
        if(raw.isBlank()) return
        runCatching { Json.decodeFromString(
            MapSerializer(String.serializer(), ListSerializer(String.serializer())),
            raw
        ) }.onSuccess { overrides ->
            KeymapMngr.applyOverridesFromStrings(overrides)
        }
    }

    CoroutineScope(Dispatchers.Main).launch {
        SettingsMngr.events.collect { e ->
            if(e !is SettingValueChangedEvent<*>) return@collect
            when(e.node.key) {
                CoreSettingKeys.UiBackgroundImage -> ThemeMngr.refresh()
                CoreSettingKeys.KeymapActionsOverview -> applyKeymapOverrides(e)
            }
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
