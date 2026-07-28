/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.modpack

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
