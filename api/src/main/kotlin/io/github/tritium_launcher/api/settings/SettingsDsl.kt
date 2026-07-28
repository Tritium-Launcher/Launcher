/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.settings

/**
 * DSL marker for settings registration builders.
 *
 * @see io.github.tritium_launcher.launcher.settings.CategoryBuilder
 * @see io.github.tritium_launcher.launcher.settings.ToggleBuilder
 * @see io.github.tritium_launcher.launcher.settings.TextBuilder
 * @see io.github.tritium_launcher.launcher.settings.CommentBuilder
 * @see io.github.tritium_launcher.launcher.settings.WidgetBuilder
 */
@DslMarker
annotation class SettingsDsl

