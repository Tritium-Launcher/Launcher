package io.github.tritium_launcher.launcher.core.source.modrinth.api

import kotlinx.serialization.Serializable

@Serializable
data class DonationUrl(
    val id: String,
    val platform: String,
    val url: String
)