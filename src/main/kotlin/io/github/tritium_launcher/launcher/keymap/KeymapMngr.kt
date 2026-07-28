/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.keymap

import io.github.tritium_launcher.api.TConstants
import io.github.tritium_launcher.api.fromTR
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.keymap.*
import io.github.tritium_launcher.api.platform.Platform
import io.qt.gui.QKeySequence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object KeymapMngr {

    private val keymaps   = mutableMapOf<String, Keymap>()
    private val _activeKeymap = MutableStateFlow(Keymap("__empty__", "empty"))
    val activeKeymapFlow: StateFlow<Keymap> = _activeKeymap.asStateFlow()
    var activeKeymap: Keymap
        get() = _activeKeymap.value
        private set(value) { _activeKeymap.value = value }
    private val declaredDefaults = mutableMapOf<ActionId, MutableList<KeyBinding>>()
    private val persistedPath = fromTR(TConstants.Dirs.SETTINGS, "keymap.json")

    fun register(keymap: Keymap) {
        keymaps[keymap.id] = keymap
    }

    fun getAll(): List<Keymap> = keymaps.values.toList()

    fun get(id: String): Keymap? = keymaps[id]

    fun declareDefault(actionId: ActionId, binding: KeyBinding) {
        declaredDefaults.getOrPut(actionId) { mutableListOf() }.add(binding)
    }

    fun declareDefault(actionId: ActionId, bindings: List<KeyBinding>) {
        declaredDefaults.getOrPut(actionId) { mutableListOf() }.addAll(bindings)
    }

    fun declaredActionIds(): Set<ActionId> = declaredDefaults.keys

    fun bindingsFor(actionId: ActionId, keymap: Keymap = activeKeymap): List<KeyBinding> =
        keymap.getBindings(actionId)

    fun activeLocalOverridesAsStrings(): Map<ActionId, List<String>> =
        activeKeymap.localOverrides().mapValues { (_, bindings) ->
            bindings.map { it.displayString() }
        }

    fun applyOverridesFromStrings(
        overrides: Map<ActionId, List<String>>
    ) {
        val parsed = overrides.mapValues { (_, displayStrings) ->
            displayStrings.mapNotNull { parseBindingString(it) }
        }
        
        saveActiveCustomOverrides(parsed)
    }

    fun activate(id: String) {
        val keymap = keymaps[id] ?: return
        activeKeymap = keymap
        ActionRegistry.syncShortcuts(keymap)
        persistCurrent()
    }

    /** Call once at startup after all built-in keymaps have been registered. */
    fun initPlatformDefault() {
        ensureBuiltinKeymaps()
        val defaultId = if (Platform.isMacOS) "tritium.mac" else "tritium.default"
        activate(defaultId)
    }

    fun initWithPersistence() {
        ensureBuiltinKeymaps()
        if (!restorePersisted()) {
            initPlatformDefault()
            persistCurrent()
        }
    }

    fun reloadWithPersistence() {
        val declared = declaredDefaults.toMap()
        keymaps.clear()
        activeKeymap = Keymap("__empty__", "empty")
        declaredDefaults.clear()
        declared.forEach { (id, bindings) ->
            declaredDefaults[id] = bindings.toMutableList()
        }
        initWithPersistence()
    }

    fun findConflicts(
        actionId: ActionId,
        bindings: List<KeyBinding>,
        keymap: Keymap = activeKeymap
    ): Map<ActionId, List<KeyBinding>> {
        val actionGroups = ActionRegistry.focusGroups(actionId)
        return keymap.allActions()
            .filter { (otherId, _) -> otherId != actionId }
            .filter { (otherId, _) ->
                actionGroups.intersect(ActionRegistry.focusGroups(otherId)).isNotEmpty()
            }
            .mapValues { (_, otherBindings) ->
                otherBindings.filter { it in bindings }
            }
            .filter { (_, conflicts) -> conflicts.isNotEmpty() }
    }

    private val reservedMnemonics: Set<Keystroke> = setOf(
        Keystroke.alt(io.qt.core.Qt.Key.Key_F.value()),  // &File
        Keystroke.alt(io.qt.core.Qt.Key.Key_E.value()),  // &Edit
        Keystroke.alt(io.qt.core.Qt.Key.Key_V.value()),  // &View
        Keystroke.alt(io.qt.core.Qt.Key.Key_M.value()),  // &Modpack
        Keystroke.alt(io.qt.core.Qt.Key.Key_H.value()),  // &Help
    )

    fun wouldConflictWithMnemonic(binding: KeyBinding): Boolean {
        if (binding !is KeyBinding.Single) return false
        return binding.stroke in reservedMnemonics
    }

    @Serializable
    data class KeymapSnapshot(
        val id: String,
        val displayName: String,
        val parentId: String?,
        // actionId → list of display strings, e.g. ["Ctrl+S", "Ctrl+K Ctrl+S"]
        val overrides: Map<String, List<String>>
    )

    @Serializable
    private data class PersistedKeymapState(
        val activeKeymapId: String,
        val customKeymaps: List<KeymapSnapshot> = emptyList()
    )

    fun save(keymap: Keymap, path: VPath) {
        val snapshot = KeymapSnapshot(
            id          = keymap.id,
            displayName = keymap.displayName,
            parentId    = keymap.parent?.id,
            overrides   = keymap.localOverrides().mapValues { (_, bindings) ->
                bindings.map { it.displayString() }
            }
        )
        path.writeBytes(Json.encodeToString(KeymapSnapshot.serializer(), snapshot).toByteArray())
    }

    fun load(path: VPath): Keymap? {
        val snapshot = runCatching {
            Json.decodeFromString(KeymapSnapshot.serializer(), path.readTextOr(""))
        }.getOrNull() ?: return null

        val parent = snapshot.parentId?.let { keymaps[it] }
        val keymap = Keymap(snapshot.id, snapshot.displayName, parent, false)

        val overrides = snapshot.overrides.mapValues { (_, displayStrings) ->
            displayStrings.mapNotNull { parseBindingString(it) }
        }
        keymap.applyOverrides(overrides)

        register(keymap)
        activate(keymap.id)
        return keymap
    }

    private fun persistCurrent() {
        val snapshots = keymaps.values
            .filter { !it.isBuiltin }
            .map { keymap ->
                KeymapSnapshot(
                    id = keymap.id,
                    displayName = keymap.displayName,
                    parentId = keymap.parent?.id,
                    overrides = keymap.localOverrides().mapValues { (_, bindings) ->
                        bindings.map { it.displayString() }
                    }
                )
            }
        val state = PersistedKeymapState(
            activeKeymapId = activeKeymap.id,
            customKeymaps = snapshots
        )
        persistedPath.writeBytes(
            Json.encodeToString(PersistedKeymapState.serializer(), state).toByteArray()
        )
    }

    private fun restorePersisted(): Boolean {
        if (!persistedPath.exists()) return false
        val state = runCatching {
            Json.decodeFromString(PersistedKeymapState.serializer(), persistedPath.readTextOr(""))
        }.getOrNull() ?: return false

        state.customKeymaps.forEach { snap ->
            val parent = snap.parentId?.let { keymaps[it] }
            val keymap = Keymap(snap.id, snap.displayName, parent, isBuiltin = false)
            val overrides = snap.overrides.mapValues { (_, displayStrings) ->
                displayStrings.mapNotNull { parseBindingString(it) }
            }
            keymap.applyOverrides(overrides)
            register(keymap)
        }

        if (get(state.activeKeymapId) == null) return false
        activate(state.activeKeymapId)
        return true
    }

    fun parseBindingString(s: String): KeyBinding? {
        // "Ctrl+K Ctrl+F" → Chord; "Ctrl+S" → Single; "-" → None
        return runCatching {
            val normalized = s.trim()
            if (normalized == "-" || normalized.isEmpty()) return KeyBinding.None
            val parts = when {
                normalized.contains(",") -> normalized.split(",").map { it.trim() }
                normalized.contains("  ") -> normalized.split("  ").map { it.trim() }
                else -> normalized.split(" ").filter { it.isNotBlank() }
            }
            if (parts.size == 2) {
                KeyBinding.Chord(parseKeystroke(parts[0]), parseKeystroke(parts[1]))
            } else if (normalized.contains("Mouse", ignoreCase = true)) {
                KeyBinding.Mouse(parseMouseStroke(normalized))
            } else {
                KeyBinding.Single(parseKeystroke(normalized))
            }
        }.getOrNull()
    }

    fun sequencesFor(actionId: ActionId, keymap: Keymap = activeKeymap): List<QKeySequence> =
        keymap.getBindings(actionId).flatMap { it.toQKeySequences() }

    private fun ensureBuiltinKeymaps() {
        if ("tritium.default" !in keymaps) {
            val default = Keymap("tritium.default", "Tritium Default", isBuiltin = true)
            declaredDefaults.forEach { (actionId, bindings) ->
                default.setBindings(actionId, bindings)
            }
            register(default)
        }
        if ("tritium.mac" !in keymaps) {
            val mac = Keymap("tritium.mac", "Tritium Mac", parent = keymaps["tritium.default"], isBuiltin = true)
            register(mac)
        }
    }

    fun saveActiveCustomOverrides(overrides: Map<ActionId, List<KeyBinding>>) {
        val current = activeKeymap
        val target = if (current.isBuiltin) {
            val userKeymap = Keymap(
                id = "tritium.user",
                displayName = "Tritium User",
                parent = current,
                isBuiltin = false
            )
            register(userKeymap)
            userKeymap
        } else current

        val normalized = overrides.mapValues { (actionId, bindings) ->
            bindings.filter { binding ->
                when (binding) {
                    is KeyBinding.Mouse -> ActionRegistry.allows(actionId, ShortcutKind.Mouse)
                    else -> ActionRegistry.allows(actionId, ShortcutKind.Keyboard)
                }
            }
        }
        target.applyOverrides(normalized)
        activate(target.id)
        persistCurrent()
    }

    private fun parseKeystroke(s: String): Keystroke {
        var mods = 0
        var remaining = s
        if (remaining.startsWith("Ctrl+"))  { mods = mods or io.qt.core.Qt.KeyboardModifier.ControlModifier.value(); remaining = remaining.removePrefix("Ctrl+")  }
        if (remaining.startsWith("Shift+")) { mods = mods or io.qt.core.Qt.KeyboardModifier.ShiftModifier.value();   remaining = remaining.removePrefix("Shift+") }
        if (remaining.startsWith("Alt+"))   { mods = mods or io.qt.core.Qt.KeyboardModifier.AltModifier.value();     remaining = remaining.removePrefix("Alt+")   }
        if (remaining.startsWith("Meta+"))  { mods = mods or io.qt.core.Qt.KeyboardModifier.MetaModifier.value();    remaining = remaining.removePrefix("Meta+")  }
        val key = QKeySequence(remaining).get(0).key().value()
        return Keystroke(key, mods)
    }

    private fun parseMouseStroke(s: String): MouseStroke {
        var mods = 0
        var remaining = s
        if (remaining.startsWith("Ctrl+"))  { mods = mods or io.qt.core.Qt.KeyboardModifier.ControlModifier.value(); remaining = remaining.removePrefix("Ctrl+")  }
        if (remaining.startsWith("Shift+")) { mods = mods or io.qt.core.Qt.KeyboardModifier.ShiftModifier.value();   remaining = remaining.removePrefix("Shift+") }
        if (remaining.startsWith("Alt+"))   { mods = mods or io.qt.core.Qt.KeyboardModifier.AltModifier.value();     remaining = remaining.removePrefix("Alt+")   }
        if (remaining.startsWith("Meta+"))  { mods = mods or io.qt.core.Qt.KeyboardModifier.MetaModifier.value();    remaining = remaining.removePrefix("Meta+")  }

        val button = when (val normalized = remaining.trim().lowercase()) {
            "mouseleft" -> io.qt.core.Qt.MouseButton.LeftButton.value()
            "mouseright" -> io.qt.core.Qt.MouseButton.RightButton.value()
            "mousemiddle" -> io.qt.core.Qt.MouseButton.MiddleButton.value()
            "mouseback" -> io.qt.core.Qt.MouseButton.BackButton.value()
            "mouseforward" -> io.qt.core.Qt.MouseButton.ForwardButton.value()
            else -> normalized.removePrefix("mousebutton").toIntOrNull()
                ?: throw IllegalArgumentException("Unsupported mouse binding: $s")
        }
        return MouseStroke(button, mods)
    }
}
