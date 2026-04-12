package io.github.tritium_launcher.launcher.core.project.templates.generation.license

import io.github.tritium_launcher.launcher.resources.ResourceLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Year

object LicenseGenerator {

    fun generateFile(
        license: License?,
        outPath: Path,
        authorOpt: String? = null,
        yearOpt: String? = null
    ) {
        if(license == null) return
        if(license.id.equals("none", ignoreCase = true)) return

        if(license.requiresAuthor || license.requiresYear) {
            val raw = license.content()
            val year = yearOpt?.trim().takeIf { !it.isNullOrBlank() } ?: Year.now().value.toString()
            val author = authorOpt?.trim().takeIf { !it.isNullOrBlank() } ?: "NONE"

            var processed = raw

            processed = processed.replace("<YEAR>", year)
            processed = processed.replace("<AUTHOR>", author)

            Files.createDirectories(outPath.parent)
            Files.writeString(outPath, processed)
            return
        }

        ResourceLoader.copyResourceToFile(license.resourcePath, outPath, license::class.java)
    }
}