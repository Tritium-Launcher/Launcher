/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.project

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlInputConfig
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import io.github.tritium_launcher.api.project.TrProjectFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Helper for reading/writing Tritium project definition files.
 */
object ProjectFiles {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val logger = logger()

    private const val FILE_NAME = ".trproj"
    private const val FILE_NAME_OLD = "trproj.json"

    val toml = Toml(
        inputConfig = TomlInputConfig(
            ignoreUnknownNames = true,
            allowEmptyValues = true
        )
    )

    private val legacyJson = Json { ignoreUnknownKeys = true }

    /**
     * Read the project definition from the given directory.
     */
    fun readTrProject(dir: VPath): TrProjectFile? {
        val newFile = dir.resolve(FILE_NAME)
        val oldFile = dir.resolve(FILE_NAME_OLD)

        if(!newFile.exists() && oldFile.exists()) return migrateLegacy(dir, oldFile, newFile)

        if(!newFile.exists()) return null

        val text = try {
            newFile.readTextOrNull()
        } catch (t: Throwable) {
            logger.warn("Failed reading .trproj at {}", newFile, t)
            return null
        } ?: return null

        val parsed = try {
            toml.decodeFromString(TrProjectFile.serializer(), text)
        } catch (t: Throwable) {
            logger.warn("Failed parsing .trproj at {}", newFile, t)
            return null
        }

        if(parsed.type.isBlank()) {
            logger.warn("Project definition missing type in {}", newFile)
            return null
        }

        return parsed
    }

    /**
     * Write the project definition to `.trproj`.
     */
    fun writeTrProject(dir: VPath, meta: TrProjectFile) {
        val metaFile = dir.resolve(FILE_NAME)
        try {
            metaFile.parent().mkdirs()
            val payload = toml.encodeToString(TrProjectFile.serializer(), meta)
            metaFile.writeBytesAtomic(payload.toByteArray())
        } catch (t: Throwable) {
            logger.error("Failed writing .trproj at {}", metaFile, t)
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
        metaPath: String
    ): TrProjectFile = TrProjectFile(
        type = type,
        name = name,
        icon = icon,
        schemaVersion = schemaVersion,
        metaPath = metaPath
    )

    /**
     * Migrates a legacy trproj.json to .trproj.
     */
    private fun migrateLegacy(dir: VPath, legacy: VPath, target: VPath): TrProjectFile? = try {
        val text = legacy.readTextOrNull() ?: return null

        val rawJson = legacyJson.parseToJsonElement(text).jsonObject
        val extractedMetaPath = rawJson["meta"]
            ?.jsonObject
            ?.get("metaPath")
            ?.jsonPrimitive
            ?.contentOrNull
            ?: "trmodpack.json"

        val parsed = legacyJson.decodeFromString(TrProjectFile.serializer(), text)

        if(parsed.type.isBlank()) {
            logger.warn("Legacy trproj.json missing type in {}, skipping migration", dir)
            return null
        }

        val tomlPayload = toml.encodeToString(TrProjectFile.serializer(), parsed)
        target.writeBytesAtomic(tomlPayload.toByteArray())
        legacy.delete()

        logger.info("Migrated trproj.json -> .trproj for '{}'", parsed.name)
        parsed
    } catch (t: Throwable) {
        logger.warn("Failed to migrate trproj.json in {}", dir, t)
        null
    }
}
