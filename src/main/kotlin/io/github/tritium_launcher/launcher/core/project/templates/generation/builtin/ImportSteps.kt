package io.github.tritium_launcher.launcher.core.project.templates.generation.builtin

import io.github.tritium_launcher.launcher.core.mod.InstalledMod
import io.github.tritium_launcher.launcher.core.mod.ModDatabase
import io.github.tritium_launcher.launcher.core.mod.ModSide
import io.github.tritium_launcher.launcher.core.mod.readModJarIcon
import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorContext
import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorStep
import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorStepDescriptor
import io.github.tritium_launcher.launcher.core.project.templates.generation.StepExecutionResult
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import kotlin.time.Clock

private val logger = logger("io.github.tritium_launcher.launcher.templates.generation.builtin.import")

/**
 * Copies selected mod jars into the project's mods/ directory and registers them
 * in the ModDatabase.
 */
class ImportModsStep(
    override val id: String,
    override val type: String = "importMods",
    private val sourceId: String,
    private val mods: List<ImportModEntry>
) : GeneratorStep {
    data class ImportModEntry(
        val jarPath: String,
        val modId: String,
        val displayName: String,
        val fileName: String,
        val side: String,
        val sourceProjectId: String?,
        val sourceVersionId: String? = null,
        val sourceIconUrl: String? = null,
        val dependencyIds: List<String>
    )

    companion object {
        fun fromDescriptor(desc: GeneratorStepDescriptor): ImportModsStep {
            val sourceId = desc.meta["sourceId"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("importMods step missing 'sourceId'")
            val modsArray = desc.meta["mods"]?.jsonArray
                ?: throw IllegalArgumentException("importMods step missing 'mods'")
            val mods = modsArray.map { elem ->
                val obj = elem.jsonObject
                ImportModEntry(
                    jarPath = obj["jarPath"]?.jsonPrimitive?.contentOrNull ?: "",
                    modId = obj["modId"]?.jsonPrimitive?.contentOrNull ?: "",
                    displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: "",
                    fileName = obj["fileName"]?.jsonPrimitive?.contentOrNull ?: "",
                    side = obj["side"]?.jsonPrimitive?.contentOrNull ?: "BOTH",
                    sourceProjectId = obj["sourceProjectId"]?.jsonPrimitive?.contentOrNull,
                    sourceVersionId = obj["sourceVersionId"]?.jsonPrimitive?.contentOrNull,
                    sourceIconUrl = obj["sourceIconUrl"]?.jsonPrimitive?.contentOrNull,
                    dependencyIds = obj["dependencyIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                )
            }
            return ImportModsStep(desc.id, sourceId = sourceId, mods = mods)
        }
    }

    override suspend fun execute(ctx: GeneratorContext): StepExecutionResult = withContext(Dispatchers.IO) {
        val projectRoot = VPath.get(ctx.projectRoot.toString())
        try {
            ModDatabase(projectRoot).use { db ->
                for (mod in mods) {
                    val srcPath = VPath.get(mod.jarPath)
                    val destJar = projectRoot.resolve("mods/${mod.fileName}")
                    val bytes = srcPath.bytesOrNull()
                    if (bytes != null) {
                        destJar.parent().mkdirs()
                        destJar.writeBytesAtomic(bytes)
                        val hash = ModDatabase.sha1(bytes)

                        val iconFile = run {
                            val iconUrl = mod.sourceIconUrl?.takeIf { it.isNotBlank() }
                            if (iconUrl != null) {
                                try {
                                    val iconBytes = URI(iconUrl).toURL().openStream().readBytes()
                                    val f = ModDatabase.iconPathFor(mod.sourceProjectId ?: mod.modId)
                                    f.writeBytesAtomic(iconBytes)
                                    f
                                } catch (_: Exception) {
                                    readModJarIcon(destJar)?.let { bytes ->
                                        val f = ModDatabase.iconPathFor(mod.sourceProjectId ?: mod.modId)
                                        f.writeBytesAtomic(bytes)
                                        f
                                    }
                                }
                            } else {
                                readModJarIcon(destJar)?.let { bytes ->
                                    val f = ModDatabase.iconPathFor(mod.sourceProjectId ?: mod.modId)
                                    f.writeBytesAtomic(bytes)
                                    f
                                }
                            }
                        }

                        val projectId = mod.sourceProjectId ?: mod.modId
                        val installedMod = InstalledMod(
                            projectId = projectId,
                            modId = mod.modId,
                            fileName = mod.fileName,
                            displayName = mod.displayName,
                            side = ModSide.valueOf(mod.side),
                            releaseType = "release",
                            source = sourceId,
                            versionId = mod.sourceVersionId.orEmpty(),
                            versionLabel = "",
                            iconPath = iconFile?.toAbsolute()?.toString(),
                            projectUrl = null,
                            fileHash = hash,
                            installedAt = Clock.System.now(),
                            enabled = true,
                            excludedFromRelease = false,
                            requiresManualDownload = false,
                            dependencies = mod.dependencyIds
                        )
                        db.install(installedMod)
                        if (mod.dependencyIds.isNotEmpty()) {
                            db.setDependencies(projectId, mod.dependencyIds)
                        }
                    }
                }
            }
            StepExecutionResult(id, type, success = true, message = "Imported ${mods.size} mod(s)")
        } catch (t: Throwable) {
            logger.error("ImportModsStep failed", t)
            StepExecutionResult(id, type, success = false, message = t.message)
        }
    }
}

/**
 * Copies checked files from the source instance into the project,
 * preserving relative paths under the source instance's minecraft dir.
 */
class ImportFilesStep(
    override val id: String,
    override val type: String = "importFiles",
    private val sourceMinecraftDir: String,
    private val files: List<String>
) : GeneratorStep {
    companion object {
        fun fromDescriptor(desc: GeneratorStepDescriptor): ImportFilesStep {
            val sourceDir = desc.meta["sourceMinecraftDir"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("importFiles step missing 'sourceMinecraftDir'")
            val filesArray = desc.meta["files"]?.jsonArray
                ?: throw IllegalArgumentException("importFiles step missing 'files'")
            val files = filesArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            return ImportFilesStep(desc.id, sourceMinecraftDir = sourceDir, files = files)
        }
    }

    override suspend fun execute(ctx: GeneratorContext): StepExecutionResult = withContext(Dispatchers.IO) {
        val projectRoot = VPath.get(ctx.projectRoot.toString())
        val sourceRoot = VPath.get(sourceMinecraftDir)
        try {
            var copied = 0
            for (filePath in files) {
                val srcFile = VPath.get(filePath)
                val relative = sourceRoot.relativize(srcFile)
                val dest = projectRoot.resolve(relative.toString())
                dest.parent().mkdirs()
                val bytes = srcFile.bytesOrNull()
                if (bytes != null) {
                    dest.writeBytesAtomic(bytes)
                    copied++
                }
            }
            StepExecutionResult(id, type, success = true, message = "Copied $copied file(s)")
        } catch (t: Throwable) {
            logger.error("ImportFilesStep failed", t)
            StepExecutionResult(id, type, success = false, message = t.message)
        }
    }
}
