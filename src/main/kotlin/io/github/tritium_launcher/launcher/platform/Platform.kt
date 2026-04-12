package io.github.tritium_launcher.launcher.platform

import io.github.tritium_launcher.launcher.mainLogger
import io.github.tritium_launcher.launcher.toURI
import kotlinx.io.IOException
import org.slf4j.Logger
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit

enum class Platform {
    Windows, MacOSX, Linux, Unknown;

    companion object {
        val name: String = System.getProperty("os.name")
        val userHome: String = System.getProperty("user.home")
        val userName: String = System.getProperty("user.name")
        val tempDir: File = File(System.getProperty("java.io.tmpdir"))
        val version: String = System.getProperty("os.version", "unknown")
        val arch: String = System.getProperty("os.arch", "unknown")

        val current = detectCurrentPlatform(name)

        val isWindows = current == Windows
        val isMacOS   = current == MacOSX
        val isLinux   = current == Linux

        fun openBrowser(url: String): Boolean {
            try {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(url.toURI())
                    return true
                }
            } catch (t: Throwable) {
                mainLogger.warn("Failed to open browser with AWT", t)
            }

            try {
                when(current) {
                    Windows -> {
                        val url = listOf("rundll32", "url.dll,FileProtocolHandler", url)
                        val process = runAndLogProcess(url)
                        if(!process) return false
                    }
                    MacOSX -> {
                        val url = listOf("open", url)
                        val process = runAndLogProcess(url)
                        if(!process) return false
                    }
                    else -> {
                        val candidates = listOf(
                            listOf("/usr/bin/xdg-open", url),
                            listOf("gio", "open", url)
                        )
                        candidates.forEach { cmd ->
                            if(runAndLogProcess(cmd)) {
                                return true
                            }
                        }
                        return false
                    }
                }
            } catch (e: IOException) {
                throw IllegalStateException("Failed to open browser for URL: $url", e)
            }

            return false
        }

        private fun runAndLogProcess(cmd: List<String>): Boolean {
            try {
                val commandName = cmd.firstOrNull().orEmpty()
                mainLogger.info("Running external command: {}", commandName)
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                val exit = if(finished) proc.exitValue() else -1
                mainLogger.info("External command '{}' exited with code {}", commandName, exit)
                if (exit != 0 && output.isNotBlank()) {
                    mainLogger.debug("External command '{}' returned output ({} chars)", commandName, output.length)
                }
                return exit == 0
            } catch (e: IOException) {
                val commandName = cmd.firstOrNull().orEmpty()
                mainLogger.warn("Exception running external command '{}'", commandName, e)
                return false
            }
        }

        internal fun printSystemDetails(logger: Logger) {
            logger.info("=== SYSTEM ===")
            logger.info("OS: $current")
            logger.info("ARCH: $arch")
            logger.info("VERSION: $version")
            logger.info("=== SYSTEM ===")
        }

        /**
         * Resolve the current platform from [osName]
         */
        private fun detectCurrentPlatform(osName: String?): Platform {
            val normalized = osName.orEmpty().trim().lowercase()
            return when {
                normalized.startsWith("windows") -> Windows
                normalized.startsWith("mac")
                        || normalized.contains("os x")
                        || normalized.contains("darwin") -> MacOSX
                normalized.startsWith("linux")
                        || normalized.contains("nix")
                        || normalized.contains("nux")
                        || normalized.contains("aix") -> Linux
                else -> Unknown
            }
        }

        fun String.redactUserHome(): String = replace(userHome, "~/")
        fun String.redactUserName(): String = replace(userName, "****")
    }

    override fun toString(): String {
        return when(this) {
            Windows -> "Windows"
            MacOSX  -> "MacOSX"
            Linux   -> "Linux"
            Unknown -> "Unknown"
        }
    }
}
