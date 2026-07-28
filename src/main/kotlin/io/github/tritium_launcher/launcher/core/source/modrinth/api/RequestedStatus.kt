/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.source.modrinth.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RequestedStatus {
    @SerialName("listed")
    LISTED,

    @SerialName("archived")
    ARCHIVED,

    @SerialName("draft")
    DRAFT,

    @SerialName("unlisted")
    UNLISTED
}
