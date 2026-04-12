package io.github.tritium_launcher.launcher.platform

import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * General entrypoint for Java-related actions.
 */
object Java {
    private val logger = logger()

    /**
     * Discovered Java runtime candidate.
     *
     * @property version Raw `java -version` value.
     * @property major Parsed major version.
     * @property executablePath Absolute path to the java executable.
     * @property homePath Inferred JAVA_HOME-style root for the executable.
     */
    data class RuntimeCandidate(
        val version: String,
        val major: Int,
        val executablePath: String,
        val homePath: String
    )

    fun test(path: String): Boolean {
        val executable = resolveJavaExecutable(path) ?: return false
        return testForVersion(executable.toJFile()) != null
    }

    fun testForVersion(executable: File): String? {
        return try {
            val process = ProcessBuilder(executable.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()

                if(!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroy()
                    null
                } else {
                    val output = process.inputStream.bufferedReader().readText()
                    val regex = Regex("version \"([^\"]+)\"")
                    regex.find(output)?.groupValues?.get(1)
                }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolves a provided Java path into a java executable path.
     *
     * @return Absolute executable path or `null` if invalid.
     */
    fun resolveJavaExecutable(pathOrHome: String?): VPath? {
        val raw = pathOrHome?.trim().orEmpty()
        if (raw.isBlank()) return null

        val direct = File(raw)
        if (direct.isFile) {
            return direct.takeIf { it.exists() }?.let { VPath.parse(it.absolutePath) }
        }

        if (!direct.isDirectory) return null
        val candidates = mutableListOf<File>()
        if (Platform.isWindows) {
            candidates += File(direct, "bin\\java.exe")
        } else {
            candidates += File(direct, "bin/java")
            candidates += File(direct, "Contents/Home/bin/java")
        }
        return candidates.firstOrNull { it.exists() && it.isFile }?.let { VPath.parse(it.absolutePath) }
    }

    /**
     * Parse Java major version from a raw version string.
     *
     * Examples:
     * - `1.8.0_412` -> `8`
     * - `17.0.12` -> `17`
     * - `21` -> `21`
     */
    fun parseMajorVersion(version: String): Int? {
        val numbers = Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
        if (numbers.isEmpty()) return null
        val first = numbers.first()
        return if (first == 1 && numbers.size >= 2) numbers[1] else first
    }

    /**
     * Locate installed Java runtimes and return executable + version metadata.
     */
    fun detectInstalledRuntimes(): List<RuntimeCandidate> {
        val candidates = collectCandidateExecutables()
        val runtimes = candidates.mapNotNull { exe ->
            val version = testForVersion(exe) ?: return@mapNotNull null
            val major = parseMajorVersion(version) ?: return@mapNotNull null
            val home = resolveHomeFromExecutable(exe)
            RuntimeCandidate(
                version = version,
                major = major,
                executablePath = exe.absolutePath,
                homePath = home
            )
        }

        return runtimes
            .distinctBy { normalizePathKey(it.executablePath) }
            .sortedWith(
                compareByDescending<RuntimeCandidate> { it.major }
                    .thenComparator { a, b -> compareVersionStrings(a.version, b.version) * -1 }
                    .thenBy { it.executablePath.lowercase() }
            )
    }

    fun locateJavaExecutablesWithVersion(): List<Pair<String, String>> {
        return detectInstalledRuntimes().map { it.version to it.executablePath }
    }

    /**
     * Returns the best detected Java executable path for [major], or null if none found.
     */
    fun findDetectedExecutableForMajor(major: Int): VPath? {
        val runtime = detectInstalledRuntimes().firstOrNull { it.major == major } ?: return null
        return VPath.parse(runtime.executablePath)
    }

    private fun collectCandidateExecutables(): List<File> {
        val out = linkedMapOf<String, File>()
        fun add(file: File?) {
            if (file == null) return
            if (!file.exists() || !file.isFile) return
            val key = normalizePathKey(file.absolutePath)
            out[key] = file.absoluteFile
        }

        val javaExecName = if (Platform.isWindows) "java.exe" else "java"
        val pathEnv = System.getenv("PATH").orEmpty()
        pathEnv.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .forEach { dir ->
                add(File(dir, javaExecName))
                if (!Platform.isWindows) {
                    add(File(dir, "java"))
                }
            }

        val javaHomeEnv = System.getenv("JAVA_HOME")
        add(resolveJavaExecutable(javaHomeEnv).toFileOrNull())
        add(resolveJavaExecutable(System.getProperty("java.home")).toFileOrNull())

        val home = System.getProperty("user.home").orEmpty()
        val commonHomes = listOfNotNull(
            if (home.isNotBlank()) File(home, ".sdkman/candidates/java") else null,
            File("/usr/lib/jvm"),
            File("/usr/java"),
            File("/opt/java"),
            File("/Library/Java/JavaVirtualMachines"),
            File("C:\\Program Files\\Java"),
            File("C:\\Program Files (x86)\\Java")
        )

        commonHomes.forEach { root ->
            if (!root.exists() || !root.isDirectory) return@forEach
            (root.listFiles() ?: emptyArray()).forEach { dir ->
                if (!dir.isDirectory) return@forEach
                add(resolveJavaExecutable(dir.absolutePath).toFileOrNull())
            }
        }

        if (Platform.isMacOS) {
            readMacJavaHomeCandidates().forEach { homePath ->
                add(resolveJavaExecutable(homePath).toFileOrNull())
            }
        }

        return out.values.toList()
    }

    private fun readMacJavaHomeCandidates(): List<String> {
        val cmd = listOf("/usr/libexec/java_home", "-V")
        return try {
            val process = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy()
                return emptyList()
            }
            val output = process.inputStream.bufferedReader().readText()
            output.lineSequence()
                .map { it.trim() }
                .mapNotNull { line ->
                    val idx = line.lastIndexOf(" /")
                    val raw = if (idx >= 0) line.substring(idx + 1) else line
                    raw.takeIf { it.startsWith("/") && it.contains("/JavaVirtualMachines/") }
                }
                .distinct()
                .toList()
        } catch (e: Exception) {
            logger.debug("Failed reading macOS java_home candidates", e)
            emptyList()
        }
    }

    private fun resolveHomeFromExecutable(executable: File): String {
        val parent = executable.parentFile
        val grand = parent?.parentFile
        if (parent != null && parent.name.equals("bin", ignoreCase = true) && grand != null) {
            return grand.absolutePath
        }
        return parent?.absolutePath ?: executable.absolutePath
    }

    private fun normalizePathKey(path: String): String {
        val normalized = runCatching { File(path).canonicalPath }.getOrDefault(path)
        return if (Platform.isWindows) normalized.lowercase() else normalized
    }

    private fun compareVersionStrings(a: String, b: String): Int {
        val av = versionNumericParts(a)
        val bv = versionNumericParts(b)
        val max = maxOf(av.size, bv.size)
        for (i in 0 until max) {
            val ai = av.getOrElse(i) { 0 }
            val bi = bv.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return a.compareTo(b)
    }

    private fun versionNumericParts(version: String): List<Int> {
        return Regex("\\d+").findAll(version)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }

    private fun VPath?.toFileOrNull(): File? {
        if (this == null) return null
        return runCatching { this.toJFile() }.getOrNull()
    }
}
