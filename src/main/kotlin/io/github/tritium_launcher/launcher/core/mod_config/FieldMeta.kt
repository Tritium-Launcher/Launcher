package io.github.tritium_launcher.launcher.core.mod_config

data class FieldMeta(
    val description: String = "",
    val default: String?    = null,
    val min: Double?        = null,
    val max: Double?        = null
)