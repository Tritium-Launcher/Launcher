package io.github.tritium_launcher.launcher.core.project.templates.generation

import io.github.tritium_launcher.launcher.core.project.templates.generation.builtin.*
import io.github.tritium_launcher.launcher.logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for generator step factories.
 */
object StepRegistry {
    private val factories = ConcurrentHashMap<String, (GeneratorStepDescriptor) -> GeneratorStep>()

    val logger = logger()

    init {
        register("fetch") { desc -> FetchStep.fromDescriptor(desc) }
        register("extract") { desc -> ExtractStep.fromDescriptor(desc) }
        register("createFile") { desc -> CreateFileStep.fromDescriptor(desc) }
        register("patchFile") { desc -> PatchFileStep.fromDescriptor(desc) }
        register("runCommand") { desc -> RunCommandStep.fromDescriptor(desc) }
    }

    /**
     * Register a factory for a step type.
     */
    fun register(type: String, factory: (GeneratorStepDescriptor) -> GeneratorStep) {
        logger.info("Register step factory: $type")
        factories[type] = factory
    }

    /**
     * Create a step instance from a descriptor.
     */
    fun create(descriptor: GeneratorStepDescriptor): GeneratorStep {
        val factory = factories[descriptor.type]
            ?: throw IllegalArgumentException("No step factory registered for type :${descriptor.type}")
        return factory(descriptor)
    }
}
