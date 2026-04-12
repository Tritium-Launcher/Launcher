package io.github.tritium_launcher.launcher.resources

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object ResourceLoader {

    fun loadText(path: String, caller: Class<*> = ResourceLoader::class.java): String {
        val normalized = path.removePrefix("/")
        val absPath = "/$normalized"

        caller.getResourceAsStream(absPath)?.use { stream ->
            return stream.reader(Charsets.UTF_8).readText()
        }

        caller.classLoader?.getResourceAsStream(normalized)?.use { stream ->
            return stream.reader(Charsets.UTF_8).readText()
        }

        throw IllegalArgumentException("Resource not found on classpath: $path")
    }

    fun copyResourceToFile(resourcePath: String, target: Path, caller: Class<*> = ResourceLoader::class.java) {
        val normalized = resourcePath.removePrefix("/")
        val absPath = "/$normalized"
        val stream = caller.getResourceAsStream(absPath) ?: caller.classLoader?.getResourceAsStream(normalized)
        stream?.use { ins ->
            Files.createDirectories(target.parent)
            Files.copy(ins, target, StandardCopyOption.REPLACE_EXISTING)
            return
        }
        throw IllegalArgumentException("Resource not found on classpath: $resourcePath")
    }
}