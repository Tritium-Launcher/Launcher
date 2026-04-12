package io.github.tritium_launcher.launcher.core.project.templates.generation.license

import io.github.tritium_launcher.launcher.accounts.ProfileMngr
import io.github.tritium_launcher.launcher.git.Git
import io.github.tritium_launcher.launcher.git.github.GitHubAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Gathers a potential username from the user's connected services.
 * @see io.github.tritium_launcher.launcher.core.project.ModpackProjectType
 */
object AuthorResolver {
    suspend fun resolvePreferredAuthor(): Pair<String, String>? = withContext(Dispatchers.IO) {

        // Try from GitHub
        try {
            val gh = try {
                GitHubAuth.getProfile()
            } catch (_: Throwable) { null }

            val name = gh?.login?.takeIf { it.isNotBlank() }
            if(!name.isNullOrBlank()) return@withContext name to "GitHub"
        } catch (_: Throwable) {}

        // Try from Git
        try {
            if(Git.gitExecExists) {
                val pb = ProcessBuilder("git", "config", "--global", "--get", "user.name")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                proc.waitFor(3, TimeUnit.SECONDS)
                if (out.isNotBlank()) return@withContext out to "Git Config"
            }
        } catch (_: Throwable) {}

        // Try from MC
        try {
            val profile = ProfileMngr.Cache.get()
            val name = profile?.name?.takeIf { it.isNotBlank() }
            if(!name.isNullOrBlank()) return@withContext name to "Minecraft"
        } catch (_: Throwable) {}

        return@withContext null
    }
}