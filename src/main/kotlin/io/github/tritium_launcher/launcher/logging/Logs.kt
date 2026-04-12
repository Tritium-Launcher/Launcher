package io.github.tritium_launcher.launcher.logging

import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.sanitizeForLogs
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream

/**
 * Central log file writer for Tritium.
 *
 * Uses `~/tritium/logs/tritium.log` as the active log file, compresses previous logs at launch,
 * and keeps a limited history.
 */
object Logs {
    private const val CURRENT_LOG_FILE_NAME = "tritium.log"
    private const val MAX_LOG_FILES = 20
    private val ARCHIVE_NAME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private var prepared = false

    private val logsDirPath: VPath
        get() = fromTR("logs").toAbsolute()
    private val currentLogPath: VPath
        get() = logsDirPath.resolve(CURRENT_LOG_FILE_NAME)

    /**
     * Prepares the log directory for the current app launch.
     *
     * Existing `tritium.log` is rotated into a timestamped archive, legacy `.log` archives are
     * gzipped, and retention is enforced.
     */
    fun prepareForLaunch() {
        synchronized(lock) {
            if (prepared) return
            runCatching {
                logsDirPath.mkdirs()
                rotateCurrentLogLocked()
                compressLegacyLogsLocked()
                enforceRetentionLocked()
            }.onFailure {
                System.err.println("Tritium log preparation failed: ${it.message}")
            }
            prepared = true
        }
    }

    /**
     * Appends a rendered log entry to the active log file and notifies listeners.
     */
    fun append(entry: String) {
        val sanitizedEntry = entry.sanitizeForLogs()
        prepareForLaunch()
        synchronized(lock) {
            runCatching {
                currentLogPath.outputStream(
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE
                ).use { out ->
                    out.write(sanitizedEntry.toByteArray(Charsets.UTF_8))
                }
            }.onFailure {
                System.err.println("Tritium log append failed: ${it.message}")
            }
        }

        listeners.forEach { listener ->
            runCatching { listener(sanitizedEntry) }
        }
    }

    /**
     * Read the active log file
     */
    fun readCurrentLog(): String {
        prepareForLaunch()
        return synchronized(lock) {
            runCatching {
                if (!currentLogPath.exists()) return@synchronized ""
                currentLogPath.readTextOrNull().orEmpty()
            }.getOrDefault("")
        }
    }

    /**
     * Subscribes to live appended log entries.
     *
     * @return Function that unsubscribes the listener.
     */
    fun addEntryListener(listener: (String) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /**
     * Returns the absolute active log file path as a string.
     */
    fun currentLogFilePath(): String = currentLogPath.toString()

    private fun rotateCurrentLogLocked() {
        if (!currentLogPath.isFile()) return
        val hasData = runCatching { (currentLogPath.sizeOrNull() ?: 0L) > 0L }.getOrDefault(false)
        if (!hasData) return

        val archivePlainPath = nextArchivePlainPathLocked()
        val moved = runCatching {
            Files.move(currentLogPath.toJPath(), archivePlainPath.toJPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
        if (!moved) {
            runCatching {
                Files.copy(currentLogPath.toJPath(), archivePlainPath.toJPath(), StandardCopyOption.REPLACE_EXISTING)
                currentLogPath.delete()
            }
        }
    }

    private fun compressLegacyLogsLocked() {
        val files = listLogFilesLocked()
        val plainLogs = files.filter { path ->
            val name = path.fileName()
            name != CURRENT_LOG_FILE_NAME && name.endsWith(".log", ignoreCase = true)
        }
        plainLogs.forEach { plain ->
            val gzPath = plain.parent().resolve("${plain.fileName()}.gz")
            val compressed = runCatching {
                plain.inputStream().use { input ->
                    gzPath.outputStream(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                    ).use { rawOut ->
                        GZIPOutputStream(BufferedOutputStream(rawOut)).use { gzip ->
                            input.copyTo(gzip)
                        }
                    }
                }
                plain.delete()
            }.isSuccess

            if (!compressed) {
                runCatching { gzPath.delete() }
            }
        }
    }

    private fun enforceRetentionLocked() {
        val maxArchives = (MAX_LOG_FILES - 1).coerceAtLeast(0)
        val archives = listLogFilesLocked()
            .filter { path ->
                val name = path.fileName()
                name != CURRENT_LOG_FILE_NAME && (name.endsWith(".log.gz", ignoreCase = true) || name.endsWith(".log", ignoreCase = true))
            }
            .sortedByDescending { safeLastModifiedMillis(it) }

        archives.drop(maxArchives).forEach { old ->
            runCatching { old.delete() }
        }
    }

    private fun listLogFilesLocked(): List<VPath> {
        if (!logsDirPath.isDir()) return emptyList()
        return logsDirPath.list().filter { it.isFile() }
    }

    private fun safeLastModifiedMillis(path: VPath): Long {
        return runCatching { Files.getLastModifiedTime(path.toJPath()).toMillis() }
            .getOrDefault(Long.MIN_VALUE)
    }

    private fun nextArchivePlainPathLocked(): VPath {
        val stamp = LocalDateTime.now().format(ARCHIVE_NAME_FMT)
        var attempt = 0
        while (true) {
            val suffix = if (attempt == 0) "" else "-$attempt"
            val candidate = logsDirPath.resolve("tritium-$stamp$suffix.log")
            if (!candidate.exists()) return candidate
            attempt++
        }
    }
}

/**
 * Logback appender that writes rendered events to [Logs].
 */
class LogAppender : AppenderBase<ILoggingEvent>() {
    private val patternLayout = PatternLayout()

    override fun start() {
        val ctx = context ?: return
        patternLayout.context = ctx
        patternLayout.pattern = "[%d{HH:mm:ss}] [%thread/%level] [%logger]: %msg%n%ex"
        patternLayout.start()
        Logs.prepareForLaunch()
        super.start()
    }

    override fun append(eventObject: ILoggingEvent?) {
        if (!isStarted || eventObject == null) return
        val rendered = patternLayout.doLayout(eventObject)
        Logs.append(rendered)
    }

    override fun stop() {
        patternLayout.stop()
        super.stop()
    }
}
