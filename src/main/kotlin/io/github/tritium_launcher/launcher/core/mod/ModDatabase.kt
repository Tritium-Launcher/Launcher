package io.github.tritium_launcher.launcher.core.mod

import io.github.tritium_launcher.launcher.fromTR
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.sqlite.SQLiteConfig
import java.io.Closeable
import java.security.MessageDigest
import java.sql.Connection
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class VersionHistoryRecord(
    val projectId: String,
    val oldVersionId: String,
    val oldVersionLabel: String,
    val oldFileHash: String?,
    val newVersionId: String,
    val newVersionLabel: String,
    val skipped: Boolean,
    val changedAt: Instant
)

@OptIn(ExperimentalTime::class)
data class InstalledMod(
    val projectId: String,
    val modId: String,
    val fileName: String,
    val displayName: String,
    val side: ModSide = ModSide.BOTH,
    val releaseType: String = "release",
    val source: String,
    val versionId: String,
    val versionLabel: String,
    val iconPath: String? = null,
    val projectUrl: String? = null,
    val fileHash: String? = null,
    val installedAt: Instant? = null,
    val enabled: Boolean = true,
    val excludedFromRelease: Boolean = false,
    val requiresManualDownload: Boolean = false,
    val dependencies: List<String> = emptyList()
)

@OptIn(ExperimentalTime::class)
class ModDatabase(private val projectDir: VPath) : Closeable {
    private val logger = logger()
    private val dbPath: VPath = projectDir.resolve(".tr/mods.db")
    private var conn: Connection? = null
    private var needsBackup = false

    private fun connection(): Connection {
        conn?.let { return it }
        Class.forName("org.sqlite.JDBC")
        val config = SQLiteConfig().apply {
            setEncoding(SQLiteConfig.Encoding.UTF8)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(5000)
        }
        dbPath.parent().mkdirs()
        val c = config.createConnection("jdbc:sqlite:${dbPath.toAbsolute()}")
        c.createStatement().execute("PRAGMA foreign_keys = ON;")
        c.createStatement().execute(
            //language=sql
            """
            CREATE TABLE IF NOT EXISTS installed_mods (
                project_id TEXT PRIMARY KEY,
                mod_id TEXT NOT NULL,
                file_name TEXT NOT NULL,
                display_name TEXT NOT NULL,
                side TEXT NOT NULL DEFAULT 'BOTH',
                release_type TEXT NOT NULL DEFAULT 'release',
                source TEXT NOT NULL,
                version_id TEXT NOT NULL,
                version_label TEXT NOT NULL,
                icon_path TEXT,
                project_url TEXT,
                file_hash TEXT,
                installed_at INTEGER,
                enabled INTEGER NOT NULL DEFAULT 1,
                excluded_from_release INTEGER NOT NULL DEFAULT 0,
                requires_manual_download INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        try {
            c.createStatement().execute(
                //language=sql
                """
                ALTER TABLE installed_mods ADD COLUMN requires_manual_download INTEGER NOT NULL DEFAULT 0
                """.trimIndent()
            )
        } catch (_: Exception) { }
        c.createStatement().execute(
            //language=sql
            """
            CREATE TABLE IF NOT EXISTS release_mods (
                project_id TEXT PRIMARY KEY
            )
            """.trimIndent()
        )
        c.createStatement().execute(
            //language=sql
            """
            CREATE TABLE IF NOT EXISTS mod_dependencies (
                mod_id TEXT,
                depends_on_id TEXT,
                PRIMARY KEY (mod_id, depends_on_id),
                FOREIGN KEY (mod_id) REFERENCES installed_mods(project_id) ON DELETE CASCADE,
                FOREIGN KEY (depends_on_id) REFERENCES installed_mods(project_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        c.createStatement().execute(
            //language=sql
            """
            CREATE TABLE IF NOT EXISTS mod_version_history (
                project_id TEXT NOT NULL,
                old_version_id TEXT NOT NULL,
                old_version_label TEXT NOT NULL,
                old_file_hash TEXT,
                new_version_id TEXT NOT NULL,
                new_version_label TEXT NOT NULL,
                skipped INTEGER NOT NULL DEFAULT 0,
                changed_at INTEGER NOT NULL,
                PRIMARY KEY (project_id, changed_at)
            )
            """.trimIndent()
        )
        conn = c
        return c
    }

    fun install(mod: InstalledMod) {
        needsBackup = true
        val c = connection()
        c.prepareStatement(
            //language=sql
            """
            INSERT OR REPLACE INTO installed_mods
            (project_id, mod_id, file_name, display_name, side, release_type,
             source, version_id, version_label, icon_path, project_url,
             file_hash, installed_at, enabled, excluded_from_release,
             requires_manual_download)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, mod.projectId)
            ps.setString(2, mod.modId)
            ps.setString(3, mod.fileName)
            ps.setString(4, mod.displayName)
            ps.setString(5, mod.side.name)
            ps.setString(6, mod.releaseType)
            ps.setString(7, mod.source)
            ps.setString(8, mod.versionId)
            ps.setString(9, mod.versionLabel)
            ps.setString(10, mod.iconPath)
            ps.setString(11, mod.projectUrl)
            ps.setString(12, mod.fileHash)
            if (mod.installedAt != null) ps.setLong(13, mod.installedAt.toEpochMilliseconds()) else ps.setNull(13, java.sql.Types.INTEGER)
            ps.setInt(14, if (mod.enabled) 1 else 0)
            ps.setInt(15, if (mod.excludedFromRelease) 1 else 0)
            ps.setInt(16, if (mod.requiresManualDownload) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun updateIconPath(projectId: String, iconPath: String) {
        needsBackup = true
        connection().prepareStatement("UPDATE installed_mods SET icon_path = ? WHERE project_id = ?").use { ps ->
            ps.setString(1, iconPath)
            ps.setString(2, projectId)
            ps.executeUpdate()
        }
    }

    fun uninstall(projectId: String) {
        needsBackup = true
        connection().prepareStatement("DELETE FROM installed_mods WHERE project_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeUpdate()
        }
    }

    fun getByProjectId(projectId: String): InstalledMod? {
        connection().prepareStatement("SELECT * FROM installed_mods WHERE project_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                if (rs.next()) return rowToMod(rs)
            }
        }
        return null
    }

    fun getByModId(modId: String): List<InstalledMod> {
        val result = mutableListOf<InstalledMod>()
        connection().prepareStatement("SELECT * FROM installed_mods WHERE mod_id = ?").use { ps ->
            ps.setString(1, modId)
            ps.executeQuery().use { rs ->
                while (rs.next()) result.add(rowToMod(rs))
            }
        }
        return result
    }

    fun getAll(): List<InstalledMod> {
        val result = mutableListOf<InstalledMod>()
        connection().createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM installed_mods ORDER BY installed_at DESC").use { rs ->
                while (rs.next()) result.add(rowToMod(rs))
            }
        }
        return result
    }

    fun search(query: String): List<InstalledMod> {
        val pattern = "%${query.replace("%", "\\%").replace("_", "\\_")}%"
        val result = mutableListOf<InstalledMod>()
        connection().prepareStatement(
            //language=sql
            """
            SELECT * FROM installed_mods
            WHERE display_name LIKE ? ESCAPE '\'
               OR mod_id LIKE ? ESCAPE '\'
               OR file_name LIKE ? ESCAPE '\'
            ORDER BY installed_at DESC
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, pattern)
            ps.setString(2, pattern)
            ps.setString(3, pattern)
            ps.executeQuery().use { rs ->
                while (rs.next()) result.add(rowToMod(rs))
            }
        }
        return result
    }

    fun getBySide(side: ModSide): List<InstalledMod> {
        val result = mutableListOf<InstalledMod>()
        connection().prepareStatement("SELECT * FROM installed_mods WHERE side = ? ORDER BY installed_at DESC").use { ps ->
            ps.setString(1, side.name)
            ps.executeQuery().use { rs ->
                while (rs.next()) result.add(rowToMod(rs))
            }
        }
        return result
    }

    fun getByReleaseType(type: String): List<InstalledMod> {
        val result = mutableListOf<InstalledMod>()
        connection().prepareStatement("SELECT * FROM installed_mods WHERE release_type = ? ORDER BY installed_at DESC").use { ps ->
            ps.setString(1, type.lowercase())
            ps.executeQuery().use { rs ->
                while (rs.next()) result.add(rowToMod(rs))
            }
        }
        return result
    }

    fun getBySource(source: String): List<InstalledMod> {
        val result = mutableListOf<InstalledMod>()
        connection().prepareStatement("SELECT * FROM installed_mods WHERE source = ? ORDER BY installed_at DESC").use { ps ->
            ps.setString(1, source)
            ps.executeQuery().use { rs ->
                while (rs.next()) result.add(rowToMod(rs))
            }
        }
        return result
    }

    fun exists(projectId: String): Boolean {
        connection().prepareStatement("SELECT 1 FROM installed_mods WHERE project_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    fun count(): Int {
        connection().createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM installed_mods").use { rs ->
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun setEnabled(projectId: String, enabled: Boolean) {
        needsBackup = true
        connection().prepareStatement("UPDATE installed_mods SET enabled = ? WHERE project_id = ?").use { ps ->
            ps.setInt(1, if (enabled) 1 else 0)
            ps.setString(2, projectId)
            ps.executeUpdate()
        }
    }

    fun setExcludedFromRelease(projectId: String, excluded: Boolean) {
        needsBackup = true
        connection().prepareStatement("UPDATE installed_mods SET excluded_from_release = ? WHERE project_id = ?").use { ps ->
            ps.setInt(1, if (excluded) 1 else 0)
            ps.setString(2, projectId)
            ps.executeUpdate()
        }
    }

    fun getEnabled(): List<InstalledMod> {
        val result = mutableListOf<InstalledMod>()
        connection().createStatement().use { stmt ->
            stmt.executeQuery("SELECT * FROM installed_mods WHERE enabled = 1 ORDER BY installed_at DESC").use { rs ->
                while (rs.next()) result.add(rowToMod(rs))
            }
        }
        return result
    }

    fun addToRelease(projectId: String) {
        needsBackup = true
        connection().prepareStatement("INSERT OR REPLACE INTO release_mods (project_id) VALUES (?)").use { ps ->
            ps.setString(1, projectId)
            ps.executeUpdate()
        }
    }

    fun removeFromRelease(projectId: String) {
        needsBackup = true
        connection().prepareStatement("DELETE FROM release_mods WHERE project_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeUpdate()
        }
    }

    fun isInRelease(projectId: String): Boolean {
        connection().prepareStatement("SELECT 1 FROM release_mods WHERE project_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    fun getReleaseMods(): List<String> {
        val result = mutableListOf<String>()
        connection().createStatement().use { stmt ->
            stmt.executeQuery("SELECT project_id FROM release_mods").use { rs ->
                while (rs.next()) result.add(rs.getString("project_id"))
            }
        }
        return result
    }

    fun getReleaseModsFull(): List<InstalledMod> {
        val result = mutableListOf<InstalledMod>()
        connection().createStatement().use { stmt ->
            stmt.executeQuery(
                """SELECT m.* FROM installed_mods m
                   INNER JOIN release_mods r ON m.project_id = r.project_id
                   WHERE m.enabled = 1
                   ORDER BY m.installed_at DESC"""
            ).use { rs ->
                while (rs.next()) result.add(rowToMod(rs))
            }
        }
        return result
    }

    fun setDependencies(projectId: String, dependencyIds: List<String>) {
        needsBackup = true
        val c = connection()
        c.prepareStatement("DELETE FROM mod_dependencies WHERE mod_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeUpdate()
        }
        if (dependencyIds.isEmpty()) return
        c.prepareStatement("INSERT OR IGNORE INTO mod_dependencies (mod_id, depends_on_id) VALUES (?, ?)").use { ps ->
            dependencyIds.forEach { depId ->
                ps.setString(1, projectId)
                ps.setString(2, depId)
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun getDependencies(projectId: String): List<String> {
        val result = mutableListOf<String>()
        connection().prepareStatement("SELECT depends_on_id FROM mod_dependencies WHERE mod_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                while (rs.next()) result.add(rs.getString("depends_on_id"))
            }
        }
        return result
    }

    fun recordVersionChange(
        projectId: String,
        oldVersionId: String,
        oldVersionLabel: String,
        oldFileHash: String?,
        newVersionId: String,
        newVersionLabel: String,
        skipped: Boolean = false
    ) {
        needsBackup = true
        val c = connection()
        c.prepareStatement(
            //language=sql
            """
            INSERT INTO mod_version_history
            (project_id, old_version_id, old_version_label, old_file_hash,
             new_version_id, new_version_label, skipped, changed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, projectId)
            ps.setString(2, oldVersionId)
            ps.setString(3, oldVersionLabel)
            ps.setString(4, oldFileHash)
            ps.setString(5, newVersionId)
            ps.setString(6, newVersionLabel)
            ps.setInt(7, if (skipped) 1 else 0)
            ps.setLong(8, Clock.System.now().toEpochMilliseconds())
            ps.executeUpdate()
        }
    }

    fun getVersionHistory(projectId: String): List<VersionHistoryRecord> {
        val result = mutableListOf<VersionHistoryRecord>()
        connection().prepareStatement(
            "SELECT * FROM mod_version_history WHERE project_id = ? ORDER BY changed_at DESC"
        ).use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                while (rs.next()) result.add(rowToVersionHistory(rs))
            }
        }
        return result
    }

    fun getPreviousVersion(projectId: String): VersionHistoryRecord? {
        connection().prepareStatement(
            //language=sql
            """
            SELECT * FROM mod_version_history
            WHERE project_id = ? AND skipped = 0
            ORDER BY changed_at DESC LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                if (rs.next()) return rowToVersionHistory(rs)
            }
        }
        return null
    }

    fun getSkippedVersion(projectId: String, currentVersionId: String): VersionHistoryRecord? {
        connection().prepareStatement(
            "SELECT * FROM mod_version_history WHERE project_id = ? AND skipped = 1 AND new_version_id != ? ORDER BY changed_at DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, projectId)
            ps.setString(2, currentVersionId)
            ps.executeQuery().use { rs ->
                if (rs.next()) return rowToVersionHistory(rs)
            }
        }
        return null
    }

    fun isVersionSkipped(projectId: String, versionId: String): Boolean {
        connection().prepareStatement(
            "SELECT 1 FROM mod_version_history WHERE project_id = ? AND new_version_id = ? AND skipped = 1 LIMIT 1"
        ).use { ps ->
            ps.setString(1, projectId)
            ps.setString(2, versionId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    override fun close() {
        if (needsBackup) {
            try { backupToRegistry() } catch (e: Exception) {
                logger.warn("Failed to backup mod registry", e)
            }
        }
        conn?.close()
        conn = null
    }

    fun backupToRegistry() {
        val registry = ModRegistryStore(projectDir)
        val allMods = getAll().map { registry.entryFromInstalledMod(it) }
        val data = ModRegistryData(
            version = 2,
            mods = allMods.associateBy { it.projectId }
        )
        registry.save(data)
        needsBackup = false
    }

    private fun rowToMod(rs: java.sql.ResultSet): InstalledMod {
        val projectId = rs.getString("project_id")
        return InstalledMod(
            projectId = projectId,
            modId = rs.getString("mod_id"),
            fileName = rs.getString("file_name"),
            displayName = rs.getString("display_name"),
            side = try { ModSide.valueOf(rs.getString("side")?.uppercase() ?: "BOTH") } catch (_: Exception) { ModSide.BOTH },
            releaseType = rs.getString("release_type") ?: "release",
            source = rs.getString("source"),
            versionId = rs.getString("version_id"),
            versionLabel = rs.getString("version_label"),
            iconPath = rs.getString("icon_path"),
            projectUrl = rs.getString("project_url"),
            fileHash = rs.getString("file_hash"),
            installedAt = rs.getLong("installed_at").let { if (rs.wasNull()) null else Instant.fromEpochMilliseconds(it) },
            enabled = rs.getInt("enabled") != 0,
            excludedFromRelease = rs.getInt("excluded_from_release") != 0,
            requiresManualDownload = rs.getInt("requires_manual_download") != 0,
            dependencies = getDependencies(projectId),
        )
    }

    private fun rowToVersionHistory(rs: java.sql.ResultSet): VersionHistoryRecord {
        return VersionHistoryRecord(
            projectId = rs.getString("project_id"),
            oldVersionId = rs.getString("old_version_id"),
            oldVersionLabel = rs.getString("old_version_label"),
            oldFileHash = rs.getString("old_file_hash"),
            newVersionId = rs.getString("new_version_id"),
            newVersionLabel = rs.getString("new_version_label"),
            skipped = rs.getInt("skipped") != 0,
            changedAt = Instant.fromEpochMilliseconds(rs.getLong("changed_at"))
        )
    }

    private fun parseJsonList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromJsonElement<List<String>>(json.parseToJsonElement(raw))
        } catch (_: Exception) { emptyList() }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val logger = logger()

        val ICONS_DIR: VPath = fromTR("cache", "mod-icons")
        val CACHE_DIR: VPath = fromTR("mod-cache")

        init {
            ICONS_DIR.mkdirs()
        }

        fun sha1(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-1")
            return digest.digest(bytes).joinToString("") { "%02x".format(it) }
        }

        fun iconPathFor(projectId: String): VPath = ICONS_DIR.resolve("$projectId.png")
        fun cachePathFor(hash: String): VPath = CACHE_DIR.resolve("$hash.jar")

        fun restoreFromRegistryIfNeeded(db: ModDatabase, projectDir: VPath) {
            if (db.count() > 0) return
            val registry = ModRegistryStore(projectDir)
            val data = registry.load()
            if (data.mods.isEmpty()) return
            logger.info("Restoring mod database from registry backup ({} entries)", data.mods.size)
            data.mods.values.forEach { entry ->
                db.install(registry.toInstalledMod(entry))
            }
        }
    }
}
