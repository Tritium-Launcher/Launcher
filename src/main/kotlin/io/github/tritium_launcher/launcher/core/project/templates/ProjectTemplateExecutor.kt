package io.github.tritium_launcher.launcher.core.project.templates

import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorContext
import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorStepDescriptor
import io.github.tritium_launcher.launcher.core.project.templates.generation.StepExecutionResult
import io.github.tritium_launcher.launcher.core.project.templates.generation.StepRegistry
import io.github.tritium_launcher.launcher.logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Instant

/**
 * Executes a list of generator step descriptors and returns a summarized result.
 * This keeps project creation consistent across project types.
 */
object ProjectTemplateExecutor {
    private val logger = logger()

    /**
     * Execute generator steps and return a summarized result.
     *
     * @param templateId Id of the template being executed.
     * @param projectRoot Root directory for generated files.
     * @param variables Variables available to steps.
     * @param steps Generator step descriptors to run in order.
     */
    suspend fun run(
        templateId: String,
        projectRoot: Path,
        variables: Map<String, String>,
        steps: List<GeneratorStepDescriptor>
    ): TemplateExecutionResult = withContext(Dispatchers.IO) {
        val start = Instant.now()
        val ctx = GeneratorContext(
            projectRoot = projectRoot,
            variables = variables,
            logger = logger,
            workingDir = projectRoot,
            snapshotDir = projectRoot.resolve(".tr/snapshots")
        )
        val results = mutableListOf<StepExecutionResult>()
        for (desc in steps) {
            val step = StepRegistry.create(desc)
            logger.info("Executing template step {} type={}", desc.id, desc.type)
            val res = step.execute(ctx)
            results += res
            if(!res.success) {
                return@withContext TemplateExecutionResult(
                    templateId = templateId,
                    projectRoot = projectRoot.toString(),
                    startTime = start,
                    endTime = Instant.now(),
                    successful = false,
                    snapshotPath = null,
                    stepResults = results,
                    logs = emptyList()
                )
            }
        }
        TemplateExecutionResult(
            templateId = templateId,
            projectRoot = projectRoot.toString(),
            startTime = start,
            endTime = Instant.now(),
            successful = true,
            snapshotPath = null,
            stepResults = results,
            logs = emptyList()
        )
    }
}
