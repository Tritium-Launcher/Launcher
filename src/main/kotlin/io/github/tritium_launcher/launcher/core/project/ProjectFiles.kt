package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Helper for reading/writing Tritium project definition files.
 */
object ProjectFiles {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = logger()

    private const val NEW_FILE = "trproj.json"

    /**
     * Read the project definition from the given directory.
     */
    fun readTrProject(dir: VPath): TrProjectFile? {
        val metaFile = dir.resolve(NEW_FILE)
        if(!metaFile.exists()) return null
        val text = try { metaFile.readTextOrNull() } catch (t: Throwable) {
            logger.warn("Failed reading $NEW_FILE at {}", metaFile, t); return null
        } ?: return null

        val parsed = try {
            json.decodeFromString(TrProjectFile.serializer(), text)
        } catch (t: Throwable) {
            logger.warn("Failed parsing $NEW_FILE at {}", metaFile, t)
            return null
        }
        if(parsed.type.isBlank()) {
            logger.warn("Project definition missing 'type' in {}", metaFile)
            return null
        }
        return parsed
    }

    /**
     * Write the project definition to `trproj.json`.
     */
    fun writeTrProject(dir: VPath, meta: TrProjectFile) {
        val metaFile = dir.resolve(NEW_FILE)
        try {
            metaFile.parent().mkdirs()
            val payload = json.encodeToString(meta)
            metaFile.writeBytesAtomic(payload.toByteArray())
        } catch (t: Throwable) {
            logger.error("Failed writing $NEW_FILE at {}", metaFile, t)
            throw t
        }
    }

    /**
     * Build a project definition object.
     */
    fun buildMeta(
        type: String,
        name: String,
        icon: String,
        schemaVersion: Int,
        meta: JsonObject
    ): TrProjectFile = TrProjectFile(
        type = type,
        name = name,
        icon = icon,
        schemaVersion = schemaVersion,
        meta = meta
    )
}
