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
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class InstalledMod(
    val projectId: String,
    val modId: String,
    val fileName: String,
    val displayName: String,
    val side: String = "BOTH",
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
    val dependencies: List<String> = emptyList()
)

@OptIn(ExperimentalTime::class)
class ModDatabase(private val projectDir: VPath) : Closeable {
    private val logger = logger()
    private val dbPath: VPath = projectDir.resolve(".tr/mods.db")
    private var conn: Connection? = null

    private fun connection(): Connection {
        conn?.let { return it }
        Class.forName("org.sqlite.JDBC")
        val config = SQLiteConfig().apply {
            setEncoding(SQLiteConfig.Encoding.UTF8)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
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
                excluded_from_release INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
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
        conn = c
        return c
    }

    fun install(mod: InstalledMod) {
        val c = connection()
        c.prepareStatement(
            //language=sql
            """
            INSERT OR REPLACE INTO installed_mods
            (project_id, mod_id, file_name, display_name, side, release_type,
             source, version_id, version_label, icon_path, project_url,
             file_hash, installed_at, enabled, excluded_from_release)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, mod.projectId)
            ps.setString(2, mod.modId)
            ps.setString(3, mod.fileName)
            ps.setString(4, mod.displayName)
            ps.setString(5, mod.side)
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
            ps.executeUpdate()
        }
    }

    fun updateIconPath(projectId: String, iconPath: String) {
        connection().prepareStatement("UPDATE installed_mods SET icon_path = ? WHERE project_id = ?").use { ps ->
            ps.setString(1, iconPath)
            ps.setString(2, projectId)
            ps.executeUpdate()
        }
    }

    fun uninstall(projectId: String) {
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

    fun getBySide(side: String): List<InstalledMod> {
        val result = mutableListOf<InstalledMod>()
        connection().prepareStatement("SELECT * FROM installed_mods WHERE side = ? ORDER BY installed_at DESC").use { ps ->
            ps.setString(1, side.uppercase())
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
        connection().prepareStatement("UPDATE installed_mods SET enabled = ? WHERE project_id = ?").use { ps ->
            ps.setInt(1, if (enabled) 1 else 0)
            ps.setString(2, projectId)
            ps.executeUpdate()
        }
    }

    fun setExcludedFromRelease(projectId: String, excluded: Boolean) {
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
        connection().prepareStatement("INSERT OR REPLACE INTO release_mods (project_id) VALUES (?)").use { ps ->
            ps.setString(1, projectId)
            ps.executeUpdate()
        }
    }

    fun removeFromRelease(projectId: String) {
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

    override fun close() {
        conn?.close()
        conn = null
    }

    private fun rowToMod(rs: java.sql.ResultSet): InstalledMod {
        val projectId = rs.getString("project_id")
        return InstalledMod(
            projectId = projectId,
            modId = rs.getString("mod_id"),
            fileName = rs.getString("file_name"),
            displayName = rs.getString("display_name"),
            side = rs.getString("side") ?: "BOTH",
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
            dependencies = getDependencies(projectId),
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

        val ICONS_DIR: VPath = fromTR("mod-icons")
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
    }
}
