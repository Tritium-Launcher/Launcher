/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.util

import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues.seasonalEventsEnabled
import java.time.LocalDate
import java.time.Month

object SeasonalEvents {

    fun isPrideMonth(): Boolean {
        if (!seasonalEventsEnabled) return false
        val now = LocalDate.now()
        return now.month == Month.JUNE
    }
}
