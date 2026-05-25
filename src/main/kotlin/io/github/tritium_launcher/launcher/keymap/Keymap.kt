package io.github.tritium_launcher.launcher.keymap

class Keymap(
    val id: String,
    val displayName: String,
    val parent: Keymap? = null,
    val isBuiltin: Boolean = false
) {
    private val bindings = mutableMapOf<ActionId, MutableList<KeyBinding>>()

    fun getBindings(actionId: ActionId): List<KeyBinding> {
        val local = bindings[actionId]
        if (local != null) {
            if (local.any { it is KeyBinding.None }) return emptyList()
            return local
        }
        return parent?.getBindings(actionId) ?: emptyList()
    }

    fun resolveAction(stroke: Keystroke): ActionId? {
        bindings.forEach { (id, keys) ->
            if (keys.any { it is KeyBinding.None }) return@forEach
            if (keys.filterIsInstance<KeyBinding.Single>().any { it.stroke == stroke }) {
                return id
            }
        }

        val parentActionId = parent?.resolveAction(stroke) ?: return null

        val local = bindings[parentActionId]
        if (local != null) {
            if (local.isEmpty() || local.any { it is KeyBinding.None }) {
                return null
            }
            return null
        }

        return parentActionId
    }

    fun resolveChordAction(first: Keystroke, second: Keystroke): ActionId? {
        bindings.forEach { (id, keys) ->
            if (keys.any { it is KeyBinding.None }) return@forEach
            if (keys.filterIsInstance<KeyBinding.Chord>().any { it.first == first && it.second == second }) {
                return id
            }
        }
        val parentActionId = parent?.resolveChordAction(first, second) ?: return null
        val local = bindings[parentActionId]
        if (local != null) {
            if (local.isEmpty() || local.any { it is KeyBinding.None }) return null
            return null
        }
        return parentActionId
    }

    fun resolveMouseAction(stroke: MouseStroke): ActionId? {
        bindings.forEach { (id, keys) ->
            if (keys.any { it is KeyBinding.None }) return@forEach
            if (keys.filterIsInstance<KeyBinding.Mouse>().any { it.stroke == stroke }) {
                return id
            }
        }
        val parentActionId = parent?.resolveMouseAction(stroke) ?: return null
        val local = bindings[parentActionId]
        if (local != null) {
            if (local.isEmpty() || local.any { it is KeyBinding.None }) return null
            return null
        }
        return parentActionId
    }

    fun isChordPrefix(stroke: Keystroke): Boolean {
        val localMatch = bindings.values.flatten()
            .filterIsInstance<KeyBinding.Chord>()
            .any { it.first == stroke }
        return localMatch || (parent?.isChordPrefix(stroke) == true)
    }

    fun allActions(): Map<ActionId, List<KeyBinding>> {
        val merged = parent?.allActions()?.toMutableMap() ?: mutableMapOf()
        bindings.forEach { (id, keys) -> merged[id] = keys }
        return merged
    }

    fun setBindings(actionId: ActionId, keys: List<KeyBinding>) {
        bindings[actionId] = keys.toMutableList()
    }

    fun addBinding(actionId: ActionId, key: KeyBinding) {
        bindings.getOrPut(actionId) { mutableListOf() }.add(key)
    }

    fun removeBindings(actionId: ActionId) {
        bindings[actionId] = mutableListOf()
    }

    fun localOverrides(): Map<ActionId, List<KeyBinding>> = bindings.toMap()

    fun applyOverrides(overrides: Map<ActionId, List<KeyBinding>>) {
        bindings.clear()
        overrides.forEach { (id, keys) -> bindings[id] = keys.toMutableList() }
    }
}
