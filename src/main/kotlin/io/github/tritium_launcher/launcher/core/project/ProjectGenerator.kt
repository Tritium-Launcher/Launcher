package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.core.project.templates.TemplateExecutionResult
import io.github.tritium_launcher.launcher.coroutines.UIDispatcher
import io.github.tritium_launcher.launcher.logger
import kotlinx.coroutines.*

/**
 * Runs project creation on a background dispatcher and reports progress to UI.
 */
class ProjectGenerator(private val uiCtx: CoroutineDispatcher = UIDispatcher) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logger = logger()

    /**
     * Create a project asynchronously.
     *
     * @param projectType The project type to create.
     * @param vars Variables collected from the setup UI.
     * @param onProgress Called on the UI dispatcher with status messages.
     * @param onComplete Called on the UI dispatcher with the creation result.
     */
    fun createProjectAsync(
        projectType: ProjectType,
        vars: Map<String, String>,
        onProgress: (String) -> Unit = {},
        onComplete: (Result<TemplateExecutionResult>) -> Unit
    ): Job = scope.launch {
        withContext(uiCtx) { onProgress("Generating Project...") }
        logger.info("Started generating project '{}'", projectType.id)

        val result = try {
            projectType.createProject(vars)
        } catch (c: CancellationException) {
            logger.info("Cancelled generating project '{}'", projectType.id)
            withContext(NonCancellable + uiCtx) { onComplete(Result.failure(c)) }
            return@launch
        } catch (t: Throwable) {
            logger.warn("Failed to generate project '{}'", projectType.id, t)
            withContext(NonCancellable + uiCtx) { onComplete(Result.failure(t)) }
            return@launch
        }

        logger.info("Finished generating project '{}'", projectType.id)
        withContext(NonCancellable + uiCtx) {
            onProgress("Finished")
            onComplete(Result.success(result))
        }
    }

    /**
     * Cancel all running project creation jobs.
     */
    fun dispose() { scope.cancel() }
}
