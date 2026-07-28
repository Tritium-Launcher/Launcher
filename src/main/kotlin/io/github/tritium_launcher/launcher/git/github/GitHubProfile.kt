/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.git.github

data class GitHubProfile(
    val id: String,
    val login: String,
    val name: String?,
    val avatarUrl: String?
)
