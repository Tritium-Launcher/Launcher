/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.project.template

import io.github.tritium_launcher.api.registry.Registrable
import io.github.tritium_launcher.api.registry.Registry

class StepFactory(
    override val id: String,
    val create: (GeneratorStepDescriptor) -> GeneratorStep
) : Registrable

fun Registry<StepFactory>.create(descriptor: GeneratorStepDescriptor): GeneratorStep {
    val factory = get(descriptor.type)
        ?: throw IllegalArgumentException("No step factory registered for type: ${descriptor.type}")
    return factory.create(descriptor)
}
