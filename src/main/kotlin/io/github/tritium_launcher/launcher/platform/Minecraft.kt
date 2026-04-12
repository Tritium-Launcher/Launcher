package io.github.tritium_launcher.launcher.platform

/**
 * Utilities for Minecraft.
 */
object Minecraft {
    private val releasePattern = Regex(
        "^\\\\s*v?(\\\\d+(?:\\\\.\\\\d+){0,3})(?:[-+\\\\s].*)?\\\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val oldSnapshotPattern = Regex("^(\\d{2})w(\\d{1,2})[a-z]$", RegexOption.IGNORE_CASE)
    private val newSnapshotPattern = Regex("^(\\d+(?:\\.\\d+){0,3})-snapshot-\\d+$", RegexOption.IGNORE_CASE)
    private val preReleasePattern = Regex("(?i)-pre\\d*$", RegexOption.IGNORE_CASE)
    private val rcPattern = Regex("(?i)-rc\\d*$", RegexOption.IGNORE_CASE)

    enum class VersionStage {
        RELEASE,
        SNAPSHOT,
        PRE_RELEASE,
        RELEASE_CANDIDATE
    }

    /**
     * Parsed Minecraft Version for comparisons.
     */
    data class MCVersion(
        val raw: String,
        val parts: List<Int>,
        val stage: VersionStage
    ) : Comparable<MCVersion> {
        val major: Int get() = parts.getOrElse(0) { 0 }
        val minor: Int? get() = parts.getOrNull(1)
        val patch: Int? get() = parts.getOrNull(2)

        override fun compareTo(other: MCVersion): Int {
            val numeric = comparePartLists(parts, other.parts)
            if(numeric != 0) return numeric

            val order = when (this.stage) {
                VersionStage.SNAPSHOT -> 0
                VersionStage.PRE_RELEASE -> 1
                VersionStage.RELEASE_CANDIDATE -> 2
                VersionStage.RELEASE -> 3
            }

            val otherOrder = when(other.stage) {
                VersionStage.SNAPSHOT -> 0
                VersionStage.PRE_RELEASE -> 1
                VersionStage.RELEASE_CANDIDATE -> 2
                VersionStage.RELEASE -> 3
            }

            return order.compareTo(otherOrder)
        }
    }

    /**
     * Parses a Minecraft version into numeric parts.
     *
     * - `1.21.1` -> `[1, 21, 1]`
     * - `1.21-pre1` -> `[1, 21]`
     * - `26.1` -> `[26, 1]`
     *
     * Returns `null` when parsing is not possible (for example snapshots like `20w14∞`).
     */
    fun parseVersion(version: String): MCVersion? {
        val trim = version.trim()
        val base = baseOf(trim)

        oldSnapshotPattern.find(base)?.let { m ->
            val yy = m.groupValues[1].toIntOrNull() ?: return null
            val week = m.groupValues[2].toIntOrNull() ?: return null
            return MCVersion(trim, listOf(yy, week), VersionStage.SNAPSHOT)
        }

        newSnapshotPattern.matchEntire(base)?.let { m ->
            val numeric = m.groupValues[1]
            val parts = numeric.split(".").mapNotNull { it.toIntOrNull() }.takeIf { it.isNotEmpty() } ?: return null
            return MCVersion(trim, parts, VersionStage.SNAPSHOT)
        }

        val match = releasePattern.matchEntire(trim) ?: return null
        val parts = match.groupValues[1]
            .split(".")
            .mapNotNull { it.toIntOrNull() }
            .takeIf { it.isNotEmpty() }
            ?: return null

        val stage = detectStage(base)
        return MCVersion(trim, parts, stage)
    }

    /**
     * True for versions like `1.21-pre1`.
     */
    fun isSnapshotVersion(version: String): Boolean = detectStage(baseOf(version)) == VersionStage.SNAPSHOT

    /**
     * True for parsed snapshot versions.
     */
    fun isSnapshotVersion(version: MCVersion): Boolean = version.stage == VersionStage.SNAPSHOT

    /**
     * True for versions like `1.21-pre1`.
     */
    fun isPreReleaseVersion(version: String): Boolean = preReleasePattern.containsMatchIn(baseOf(version))

    /**
     * True for parsed pre-release versions.
     */
    fun isPreReleaseVersion(version: MCVersion): Boolean = version.stage == VersionStage.PRE_RELEASE

    /**
     * True for versions like `1.21-rc1`.
     */
    fun isReleaseCandidateVersion(version: String): Boolean = rcPattern.containsMatchIn(baseOf(version))

    /**
     * True for parsed release-candidate versions.
     */
    fun isReleaseCandidateVersion(version: MCVersion): Boolean = version.stage == VersionStage.RELEASE_CANDIDATE

    /**
     * True for stable release versions.
     */
    fun isStableReleaseVersion(version: String): Boolean {
        val parsed = parseVersion(version) ?: return false
        return isStableReleaseVersion(parsed)
    }

    /**
     * True for stable parsed release versions.
     */
    fun isStableReleaseVersion(version: MCVersion): Boolean = version.stage == VersionStage.RELEASE

    private val javaRules: List<Pair<(MCVersion) -> Boolean, Int>> = listOf(
        Pair({ v -> v.major >= 26 }, 25),
        Pair({ v -> v.major == 1 && (v.minor ?: 0) <= 16 }, 8),
        Pair({ v -> v.major == 1 && (v.minor ?: 0) in 17..20 }, 17),
        Pair({ v -> v.major == 1 && (v.minor ?: 0) >= 21 }, 21),
    )

    /**
     * Required Java major for a given Minecraft version.
     *
     * - MC <= 1.16.x -> Java 8
     * - MC 1.17 .. 1.20.6 -> Java 17
     * - MC 1.21 .. 1.21.11 -> Java 21
     * - MC 26.* -> Java 25
     */
    fun requiredJavaMajor(version: String, fallback: Int = 21): Int {
        val parsed = parseVersion(version) ?: return fallback
        return requiredJavaMajor(parsed, fallback)
    }

    /**
     * Required Java major for a parsed Minecraft version.
     */
    fun requiredJavaMajor(version: MCVersion, fallback: Int = 21): Int {
        for((predicate, javaMaj) in javaRules) {
            if(predicate(version)) return javaMaj
        }
        return fallback
    }

    private fun baseOf(version: String): String =
        version.trim()
            .substringBefore('+')
            .substringBefore(' ')
            .trim()
            .lowercase()

    private fun detectStage(version: String): VersionStage {
        if(oldSnapshotPattern.matches(version))         return VersionStage.SNAPSHOT
        if (version.contains("-snapshot-"))             return VersionStage.SNAPSHOT
        if (preReleasePattern.containsMatchIn(version)) return VersionStage.PRE_RELEASE
        if (rcPattern.containsMatchIn(version))         return VersionStage.RELEASE_CANDIDATE
        return VersionStage.RELEASE
    }

    private fun comparePartLists(left: List<Int>, right: List<Int>): Int {
        val max = maxOf(left.size, right.size)
        for (index in 0 until max) {
            val li = left.getOrElse(index) { 0 }
            val ri = right.getOrElse(index) { 0 }
            if (li != ri) return li.compareTo(ri)
        }
        return 0
    }
}