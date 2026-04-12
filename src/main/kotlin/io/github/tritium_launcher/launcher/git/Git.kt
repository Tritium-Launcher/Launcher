package io.github.tritium_launcher.launcher.git

import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.git.Git.findGitExecutable
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.platform.Platform
import io.github.tritium_launcher.launcher.settings.SettingNode
import io.github.tritium_launcher.launcher.settings.SettingValidation
import io.github.tritium_launcher.launcher.settings.SettingsMngr

/**
 * General entrypoint for Git-related actions.
 */
object Git {
    internal val logger = logger()
    private val gitPathSettingKey = CoreSettingKeys.GitPath
    var gitExecExists: Boolean = false
    private var gitPath: VPath? = null

    fun findGitExecutable(): VPath? {
        val cmd = if(Platform.isWindows) {
            arrayOf("where", "git")
        } else {
            arrayOf("which", "git")
        }

        return try {
            val process = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()

            val candidates = output.lineSequence()
                .map { it.trim().trim('"') }
                .filter { it.isNotBlank() }
                .filterNot { it.startsWith("INFO:", ignoreCase = true) }
                .mapNotNull { line ->
                    runCatching { VPath.parse(line).toAbsolute() }
                        .getOrNull()
                }
                .toList()

            val found = candidates.firstOrNull { it.exists() && it.isFile() }
            if (found != null) {
                logger.info("Found Git executable: {}", found)
                return found
            }

            if (exit == 0 && candidates.isNotEmpty()) {
                val fallback = candidates.first()
                logger.warn("Git command resolved, but executable path could not be validated on disk: {}", fallback)
                fallback
            } else {
                logger.warn("Git is not installed")
                null
            }
        } catch (e: Exception) {
            logger.warn("Error finding Git Executable", e)
            null
        }
    }

    internal fun init() {
        resolveGitExecutable()
    }

    /**
     * Initialize a git repository in the given directory.
     * @return true on success, false otherwise.
     */
    fun initRepo(dir: VPath): Boolean {
        val git = resolveGitExecutable()
        if(git == null) {
            logger.warn("Cannot init repo; git executable not found")
            return false
        }
        val folder = dir.toJFile()
        val folderPath = dir.toAbsolute().toString()
        return try {
            val pb = ProcessBuilder(git.toAbsolute().toString(), "init")
                .directory(folder)
                .redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if(exit != 0) {
                logger.warn("git init failed (exit $exit): $output")
                false
            } else {
                logger.info("Initialized git repo at {}", folderPath)
                true
            }
        } catch (t: Throwable) {
            logger.warn("Failed to init git repo at {}", folderPath, t)
            false
        }
    }

    /**
     * Resolves the best available git executable path and refreshes cached availability state.
     *
     * @return Absolute executable path, or `null` when unavailable.
     * @see findGitExecutable
     */
    private fun resolveGitExecutable(): VPath? {
        val configured = configuredGitExecutable()
        if (configured != null) {
            gitExecExists = true
            gitPath = configured
            return configured
        }

        val cached = gitPath?.toAbsolute()?.takeIf { it.exists() && it.isFile() }
        if (cached != null) {
            gitExecExists = true
            return cached
        }

        val detected = findGitExecutable()?.toAbsolute()?.takeIf { it.exists() && it.isFile() }
        if (detected == null) {
            gitExecExists = false
            gitPath = null
            return null
        }

        val previous = gitPath?.toAbsolute()
        gitExecExists = true
        gitPath = detected
        if (previous != detected) {
            maybeSetGitPathSetting(detected.toString())
        }
        return detected
    }

    /**
     * Resolves a user-configured git executable path from settings.
     *
     * @return Absolute configured executable path, or `null` when unset/invalid.
     */
    private fun configuredGitExecutable(): VPath? {
        val configuredValue = SettingsMngr.currentValueOrNull(gitPathSettingKey) as? String ?: return null
        val trimmed = configuredValue.trim()
        if (trimmed.isEmpty()) return null

        val configuredPath = try {
            VPath.parse(trimmed).toAbsolute()
        } catch (t: Throwable) {
            logger.warn("Configured Git path is invalid: {}", trimmed, t)
            return null
        }

        if (!configuredPath.exists() || !configuredPath.isFile()) {
            logger.warn("Configured Git path does not exist or is not a file: {}", configuredPath)
            return null
        }
        return configuredPath
    }

    /**
     * Writes a detected executable path into the git-path setting when it is blank or invalid.
     *
     * @param detectedPath Auto-detected git executable path.
     * @see SettingsMngr.updateValue
     */
    @Suppress("UNCHECKED_CAST")
    private fun maybeSetGitPathSetting(detectedPath: String) {
        val setting = SettingsMngr.findSetting(gitPathSettingKey) as? SettingNode<String> ?: return
        val current = SettingsMngr.currentValueOrNull(gitPathSettingKey)
        if (current != null && current !is String) return
        if (current is String && hasUsableConfiguredPath(current)) return

        when (val validation = SettingsMngr.updateValue(setting, detectedPath)) {
            is SettingValidation.Valid -> {
                logger.info("Set git executable setting to auto-detected path: {}", detectedPath)
            }
            is SettingValidation.Invalid -> {
                logger.warn("Failed to apply auto-detected git path to setting: {}", validation.reason)
            }
        }
    }

    /**
     * Returns true when [value] points to a usable git executable file.
     */
    private fun hasUsableConfiguredPath(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        val path = try {
            VPath.parse(trimmed).toAbsolute()
        } catch (_: Exception) {
            return false
        }
        return path.exists() && path.isFile()
    }
}
