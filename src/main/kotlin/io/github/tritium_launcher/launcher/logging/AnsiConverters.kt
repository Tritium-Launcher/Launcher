package io.github.tritium_launcher.launcher.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Emits an ANSI foreground color escape based on log level.
 */
class AnsiLevelPrefixColorConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent?): String {
        val level = event?.level ?: return ""
        return when (level.levelInt) {
            Level.ERROR_INT -> "\u001B[31m"
            Level.WARN_INT -> "\u001B[33m"
            Level.INFO_INT -> "\u001B[92m"
            Level.DEBUG_INT -> "\u001B[36m"
            Level.TRACE_INT -> "\u001B[34m"
            else -> ""
        }
    }
}

/**
 * Resets ANSI formatting.
 */
class AnsiResetConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent?): String = "\u001B[0m"
}
