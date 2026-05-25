package io.github.tritium_launcher.launcher.core.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReleaseType {
    @SerialName("alpha")
    ALPHA,

    @SerialName("beta")
    BETA,

    @SerialName("release")
    RELEASE
}
