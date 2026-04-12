package io.github.tritium_launcher.launcher.core.modloader.fabric

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LoaderInfo(
    val version: String,
    val maven: String? = null,
    val build: Int? = null,
    val stable: Boolean? = null,
    val separator: String? = null
)

@Serializable
data class LoaderCompatibility(
    val loader: LoaderInfo,
    val intermediary: JsonElement? = null
)