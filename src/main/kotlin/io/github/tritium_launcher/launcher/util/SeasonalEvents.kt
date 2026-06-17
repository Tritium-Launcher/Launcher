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
