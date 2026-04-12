package io.github.tritium_launcher.launcher.registry

/**
 * Marker for registry entries that expose an [id].
 */
interface Registrable {
    val id: String
}
