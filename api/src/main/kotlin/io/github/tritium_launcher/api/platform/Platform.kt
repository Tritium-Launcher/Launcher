/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.platform

import io.github.tritium_launcher.api.io.VPath
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.lang.ProcessBuilder.Redirect.DISCARD
import java.net.URI
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

        private val logger = LoggerFactory.getLogger(Platform::class.java)

        /**
         * @throws IOException
         */
        fun openBrowser(url: String): Boolean {
            try {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(URI(url))
                    return true
                }
            } catch (t: Throwable) {
                logger.warn("Failed to open browser with AWT", t)
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
                            listOf("xdg-open", url),
                            listOf("gio", "open", url),
                            listOf("kioclient5", "exec", url),
                            listOf("kioclient", "exec", url),
                            listOf("kde-open5", url),
                            listOf("kde-open", url)
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

        fun linuxTrash(path: VPath): Boolean {
            return current == Linux && try {
                val process = ProcessBuilder("gio", "trash", path.toAbsoluteString())
                    .redirectError(DISCARD)
                    .redirectOutput(DISCARD)
                    .start()

                val exited = process.waitFor(5, TimeUnit.SECONDS)
                exited && process.exitValue() == 0
            } catch (_: Exception) {
                false
            }
        }

        /**
         * @throws IOException
         */
        fun openFile(file: File): Boolean = openFile(file.path)

        /**
         * @throws IOException
         */
        fun openFile(file: VPath): Boolean = openFile(file.toString())

        /**
         * @throws IOException
         */
        fun openFile(path: String): Boolean {
            try {
                when(current) {
                    Windows -> {
                        val start = listOf("start", path)
                        val process = runAndLogProcess(start)
                        if(!process) return false
                    }
                    MacOSX -> {
                        val open = listOf("open", path)
                        val process = runAndLogProcess(open)
                        if(!process) return false
                    }
                    else -> {
                        val candidates = listOf(
                            listOf("/usr/bin/xdg-open", path),
                            listOf("xdg-open", path),
                            listOf("gio", "open", path),
                            listOf("kioclient5", "exec", path),
                            listOf("kioclient", "exec", path),
                            listOf("kde-open5", path),
                            listOf("kde-open", path)
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
                throw IllegalStateException("Failed to open file for: $path", e)
            }

            return false
        }

        /**
         * Resolve the path to the os-helper binary bundled with Tritium.
         * Returns null if not found.
         */
        fun resolveOsHelper(): String? {
            val exeName = if (isWindows) "os-helper.exe" else "os-helper"
            return listOfNotNull(
                File("tools/os-helper/target/release/$exeName").takeIf { it.isFile() }?.absolutePath,
                File("tools/os-helper/target/debug/$exeName").takeIf { it.isFile() }?.absolutePath,
                try {
                    val app = System.getProperty("jpackage.app-path")
                    if (app != null) File(app).parentFile?.let { File(it, exeName).absolutePath } else null
                } catch (_: Exception) { null },
                exeName
            ).firstOrNull()
        }

        /**
         * Run an external process and return whether it exited successfully.
         */
        fun runProcess(cmd: List<String>): Boolean {
            return runAndLogProcess(cmd)
        }

        private fun runAndLogProcess(cmd: List<String>): Boolean {
            try {
                val commandName = cmd.joinToString(" ")
                logger.info("Running external command: {}", commandName)
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                val exit = if(finished) proc.exitValue() else -1
                logger.info("External command '{}' exited with code {}", commandName, exit)
                if (exit != 0 && output.isNotBlank()) {
                    logger.debug("External command '{}' returned '{}'", commandName, output)
                }
                return exit == 0
            } catch (e: IOException) {
                val commandName = cmd.firstOrNull().orEmpty()
                logger.warn("Exception running external command '{}'", commandName, e)
                return false
            }
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
