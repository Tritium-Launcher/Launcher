package io.github.tritium_launcher.launcher.core.source.modrinth.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Status {
    @SerialName("listed")
    LISTED,

    @SerialName("archived")
    ARCHIVED,

    @SerialName("draft")
    DRAFT,

    @SerialName("unlisted")
    UNLISTED,

    @SerialName("scheduled")
    SCHEDULED,

    @SerialName("unknown")
    UNKNOWN;
}
