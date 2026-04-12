package io.github.tritium_launcher.launcher.core.project.templates.generation

/**
 * Executable step used by project generators.
 */
interface GeneratorStep {
    val id: String
    val type: String
    /**
     * Execute the step.
     */
    suspend fun execute(ctx: GeneratorContext): StepExecutionResult
    /**
     * Dry-run helper for previewing effects.
     */
    suspend fun dryRun(ctx: GeneratorContext): StepExecutionResult = StepExecutionResult(
        stepId = id,
        stepType = type,
        success = true,
        message = "Dry-run: no-op"
    )
}
