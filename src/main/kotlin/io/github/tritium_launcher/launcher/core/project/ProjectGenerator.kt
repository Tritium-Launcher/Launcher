package io.github.tritium_launcher.launcher.core.project

import io.github.tritium_launcher.launcher.core.project.templates.TemplateExecutionResult
import io.github.tritium_launcher.launcher.coroutines.UIDispatcher
import io.github.tritium_launcher.launcher.logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Runs project creation on a background dispatcher and reports progress to UI.
 */
class ProjectGenerator(private val uiCtx: CoroutineDispatcher = UIDispatcher) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logger = logger()

    /**
     * Events emitted during project generation.
     */
    sealed class ProjectGeneratorEvent {
        data class Progress(val message: String) : ProjectGeneratorEvent()
        data class Success(val result: TemplateExecutionResult) : ProjectGeneratorEvent()
        data class Error(val throwable: Throwable) : ProjectGeneratorEvent()
    }

    /**
     * Create a project and returns a [Flow] of [ProjectGeneratorEvent].
     */
    fun createProject(
        projectType: ProjectType,
        vars: Map<String, String>
    ): Flow<ProjectGeneratorEvent> = flow {
        emit(ProjectGeneratorEvent.Progress("Generating Project..."))
        logger.info("Started generating project '{}'", projectType.id)

        try {
            val result = projectType.createProject(vars)
            logger.info("Finished generating project '{}'", projectType.id)
            emit(ProjectGeneratorEvent.Progress("Finished"))
            emit(ProjectGeneratorEvent.Success(result))
        } catch (c: CancellationException) {
            logger.info("Cancelled generating project '{}'", projectType.id)
            throw c
        } catch (t: Throwable) {
            logger.warn("Failed to generate project '{}'", projectType.id, t)
            emit(ProjectGeneratorEvent.Error(t))
        }
    }

    /**
     * Create a project asynchronously (Legacy callback-based API).
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
        createProject(projectType, vars).collect { event ->
            when (event) {
                is ProjectGeneratorEvent.Progress -> withContext(uiCtx) { onProgress(event.message) }
                is ProjectGeneratorEvent.Success -> withContext(NonCancellable + uiCtx) { 
                    onComplete(Result.success(event.result)) 
                }
                is ProjectGeneratorEvent.Error -> withContext(NonCancellable + uiCtx) { 
                    onComplete(Result.failure(event.throwable)) 
                }
            }
        }
    }

    /**
     * Cancel all running project creation jobs.
     */
    fun dispose() { scope.cancel() }
}
