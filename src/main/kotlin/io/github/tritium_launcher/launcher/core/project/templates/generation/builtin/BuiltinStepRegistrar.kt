/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.core.project.templates.generation.builtin

import io.github.tritium_launcher.api.BuiltinRegistries
import io.github.tritium_launcher.api.project.template.StepFactory

object BuiltinStepRegistrar {
    fun registerBuiltinSteps() {
        val registry = BuiltinRegistries.Step
        registry.registerOrReplace("tritium", StepFactory("fetch") { desc -> FetchStep.fromDescriptor(desc) })
        registry.registerOrReplace("tritium", StepFactory("extract") { desc -> ExtractStep.fromDescriptor(desc) })
        registry.registerOrReplace("tritium", StepFactory("createFile") { desc -> CreateFileStep.fromDescriptor(desc) })
        registry.registerOrReplace("tritium", StepFactory("patchFile") { desc -> PatchFileStep.fromDescriptor(desc) })
        registry.registerOrReplace("tritium", StepFactory("runCommand") { desc -> RunCommandStep.fromDescriptor(desc) })
        registry.registerOrReplace("tritium", StepFactory("importMods") { desc -> ImportModsStep.fromDescriptor(desc) })
        registry.registerOrReplace("tritium", StepFactory("importFiles") { desc -> ImportFilesStep.fromDescriptor(desc) })
    }
}
