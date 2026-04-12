package io.github.tritium_launcher.launcher.core.project.templates

import io.github.tritium_launcher.launcher.logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for project template descriptors.
 */
object TemplateRegistry {
    private val descriptors = ConcurrentHashMap<String, TemplateDescriptor<*>>()

    val logger = logger()

    /**
     * Register a template descriptor.
     */
    fun register(descriptor: TemplateDescriptor<*>) {
        val prev = descriptors.put(descriptor.typeId, descriptor)
        if(prev != null) logger.warn("Overwriting TemplateDescriptor for ${descriptor.typeId}")
    }

    /**
     * Unregister a descriptor by type id.
     */
    fun unregister(typeId: String) {
        descriptors.remove(typeId)
    }

    /**
     * Get a descriptor by type id.
     */
    fun get(typeId: String): TemplateDescriptor<*>? = descriptors[typeId]

    /**
     * Get all registered descriptors.
     */
    fun all(): Collection<TemplateDescriptor<*>> = descriptors.values.toList()
}
