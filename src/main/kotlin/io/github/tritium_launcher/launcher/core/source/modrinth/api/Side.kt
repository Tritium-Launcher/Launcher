package io.github.tritium_launcher.launcher.core.source.modrinth.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Side {
    @SerialName("required")
    REQUIRED,

    @SerialName("optional")
    OPTIONAL,

    @SerialName("unsupported")
    UNSUPPORTED,

    @SerialName("unknown")
    UNKNOWN
}
