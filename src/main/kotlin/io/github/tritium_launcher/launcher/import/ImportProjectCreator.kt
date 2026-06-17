package io.github.tritium_launcher.launcher.import

import io.github.tritium_launcher.launcher.core.project.*
import io.github.tritium_launcher.launcher.core.project.templates.ProjectTemplateExecutor
import io.github.tritium_launcher.launcher.core.project.templates.TemplateExecutionResult
import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorStepDescriptor
import io.github.tritium_launcher.launcher.extension.core.BuiltinRegistries
import io.github.tritium_launcher.launcher.import.ui.ImportProjectDialog
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.ui.theme.TIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Assembles and runs the project creation pipeline for importing instances.
 *
 * This is the entry point used by [ImportProjectDialog] to convert a detected instance
 * into a Tritium project. It orchestrates:
 * - Metadata and export-rule file generation via [StandardProjectSteps].
 * - Optional mod import via the `"importMods"` step.
 * - Optional file import via the `"importFiles"` step.
 * - Project metadata (`trproj.json`) and icon writing.
 * - Minecraft and loader bootstrapping via [ProjectBootstrap].
 * - Import cache cleanup on success.
 *
 * All work is executed through [ProjectTemplateExecutor] so that the same pipeline handles
 * both freshly-created and imported projects, differing only in the step composition.
 */
object ImportProjectCreator {
    private val json = Json { prettyPrint = true }
    private val logger = logger()

    /**
     * Creates a Tritium project from a detected Minecraft instance.
     *
     * @param projectRoot Destination directory for the new project.
     * @param instance The detected instance to import from.
     * @param instanceMinecraftDir The instance's minecraft directory (mods, config, etc.).
     * @param sourceId Identifier of the mod source (e.g. "modrinth", "curseforge", "unknown").
     * @param iconPath Optional path to an icon file to use as the project icon.
     * @param selectedMods Mods the user has checked for import.
     * @param selectedFiles Files the user has checked for import.
     * @param sourceInstance The instance used for cache cleanup (usually the same as [instance]).
     * @param sourceIdForCache The source ID used for cache cleanup (usually the same as [sourceId]).
     * @param onProgress Optional suspend callback invoked with status messages during creation.
     * @return A [TemplateExecutionResult] describing the outcome of the generation pipeline.
     * @throws IllegalArgumentException If [projectRoot] already exists and is non-empty.
     */
    suspend fun createProject(
        projectRoot: VPath,
        instance: DetectedInstance,
        instanceMinecraftDir: VPath,
        sourceId: String,
        iconPath: VPath?,
        selectedMods: List<ImportableMod>,
        selectedFiles: List<VPath>,
        sourceInstance: DetectedInstance?,
        sourceIdForCache: String?,
        onProgress: (suspend (String) -> Unit)? = null
    ): TemplateExecutionResult {
        val packName = instance.name
        val loaderId = mapLoaderId(instance.loader)
        val loaderVer = instance.loaderVersion ?: ""
        val mcVer = instance.gameVersion ?: "unknown"

        logger.info("Import createProject start: name={} source={}", packName, sourceId)

        withContext(Dispatchers.IO) {
            if (projectRoot.existsNotEmpty()) {
                logger.warn("Aborting import, project directory already exists: {}", projectRoot)
                throw IllegalArgumentException("Project directory already exists: $projectRoot")
            }
        }

        onProgress?.invoke("Building project metadata...")

        val modpackMeta = ModpackMeta(
            id = packName,
            minecraftVersion = mcVer,
            loader = loaderId ?: "unknown",
            loaderVersion = loaderVer,
            source = sourceId,
            license = null,
            icon = if (iconPath != null) "icon.png" else null
        )
        val manifest = json.encodeToString(ModpackMeta.serializer(), modpackMeta)

        val steps = mutableListOf<GeneratorStepDescriptor>()
        steps += StandardProjectSteps.metadataStep("import-source-meta", manifest)
        steps += StandardProjectSteps.exportRulesStep("import-export-rules")
        steps += StandardProjectSteps.placeholderSteps()
        StandardProjectSteps.iconStep(iconPath?.toString().orEmpty())?.let { steps += it }

        if (selectedMods.isNotEmpty()) {
            onProgress?.invoke("Preparing ${selectedMods.size} mod(s)...")
            val modsJson = buildJsonArray {
                selectedMods.forEach { mod ->
                    addJsonObject {
                        put("jarPath", mod.jarPath.toString())
                        put("modId", mod.modId)
                        put("displayName", mod.displayName)
                        put("fileName", mod.fileName)
                        put("side", mod.side.name)
                        put("sourceProjectId", mod.sourceProjectId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("sourceVersionId", mod.sourceVersionId?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("sourceIconUrl", mod.sourceIconUrl?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("dependencyIds", buildJsonArray {
                            mod.dependencyIds.forEach { add(it) }
                        })
                    }
                }
            }
            steps += GeneratorStepDescriptor(
                "import-mods",
                "importMods",
                JsonObject(mapOf(
                    "sourceId" to JsonPrimitive(sourceId),
                    "mods" to modsJson
                )),
                affects = listOf("mods/*.jar")
            )
        }

        if (selectedFiles.isNotEmpty()) {
            onProgress?.invoke("Preparing ${selectedFiles.size} file(s)...")
            val filesJson = buildJsonArray {
                selectedFiles.forEach { file -> add(file.toString()) }
            }
            steps += GeneratorStepDescriptor(
                "import-files",
                "importFiles",
                JsonObject(mapOf(
                    "sourceMinecraftDir" to JsonPrimitive(instanceMinecraftDir.toString()),
                    "files" to filesJson
                )),
                affects = listOf("**")
            )
        }

        projectRoot.mkdirs()

        onProgress?.invoke("Writing project files...")

        val execResult = ProjectTemplateExecutor.run(
            templateId = "import:$packName",
            projectRoot = projectRoot.toJPath(),
            variables = emptyMap(),
            steps = steps,
            onStep = { stepId, _, total ->
                onProgress?.invoke("Running step $stepId ($total total)...")
            }
        )

        if (execResult.successful) {
            onProgress?.invoke("Finalizing project...")

            val iconValue = if (iconPath != null) "icon.png" else TIcons.defaultProjectIcon
            val rawMeta = buildJsonObject { put("metaPath", "trmodpack.json") }
            val trMeta = ProjectFiles.buildMeta(
                type = "source",
                name = packName,
                icon = iconValue,
                schemaVersion = ModpackTemplateDescriptor.currentSchema,
                meta = rawMeta
            )
            ProjectFiles.writeTrProject(projectRoot, trMeta)

            val loader = loaderId?.let { id ->
                BuiltinRegistries.ModLoader.all().find { it.id == id }
            }
            if (loader != null && mcVer != "unknown" && loaderVer.isNotBlank()) {
                onProgress?.invoke("Bootstrapping Minecraft and loader...")
                ProjectBootstrap.launch(projectRoot, packName, mcVer, loader, loaderVer)
            }

            if (sourceInstance != null && sourceIdForCache != null) {
                deleteImportCache(sourceInstance, sourceIdForCache)
            }
        }

        return execResult
    }
}
