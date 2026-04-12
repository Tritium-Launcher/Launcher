package io.github.tritium_launcher.launcher.core.project.templates.generation.builtin

import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorContext
import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorStep
import io.github.tritium_launcher.launcher.core.project.templates.generation.GeneratorStepDescriptor
import io.github.tritium_launcher.launcher.core.project.templates.generation.StepExecutionResult
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.io.atomicWrite
import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.redactUserPath
import io.github.tritium_launcher.launcher.toURI
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

private val logger = logger("io.github.tritium_launcher.launcher.templates.generation.builtin")

private fun resolvePath(ctx: GeneratorContext, p: String): VPath {
    val path = VPath.parse(p)
    val root = VPath.parse(ctx.projectRoot.toString())
    return if (path.isAbsolute) path.normalize() else root.resolve(path).normalize()
}

/**
 * Fetches a remote or local file into the project.
 */
class FetchStep(
    override val id: String,
    override val type: String = "fetch",
    private val url: String,
    private val dest: String
) : GeneratorStep {
    companion object {
        private val sharedClient = HttpClient(CIO)

        /**
         * Create a step from a descriptor.
         */
        fun fromDescriptor(desc: GeneratorStepDescriptor): FetchStep {
            val url = (desc.meta["url"]?.jsonPrimitive?.contentOrNull)
                ?: throw IllegalArgumentException("fetch step missing 'url'")
            val dest = (desc.meta["dest"]?.jsonPrimitive?.contentOrNull)
                ?: throw IllegalArgumentException("fetch step missing 'dest'")
            return FetchStep(id = desc.id, url = url, dest = dest)
        }
    }

    override suspend fun execute(ctx: GeneratorContext): StepExecutionResult = withContext(Dispatchers.IO) {
        val resolvedDest = resolvePath(ctx, dest)
        try {
            ctx.logger.info("Fetching resource into {}", resolvedDest.toString().redactUserPath())
            resolvedDest.parent().mkdirs()

            val uri = try { url.toURI() } catch (_: Exception) { null }

            val localPath = when {
                uri?.scheme == "file" -> VPath.get(uri)
                uri == null -> VPath.parse(url)
                else -> null
            }
            if(uri?.scheme == "file" || (uri == null && localPath?.exists() == true)) {
                try {
                    val srcPath = localPath ?: return@withContext StepExecutionResult(id, type, success = false, message = "Invalid local path")
                    ctx.logger.info("Copying local file into {}", resolvedDest.toString().redactUserPath())
                    val bytes = srcPath.bytesOrNull()
                        ?: return@withContext StepExecutionResult(id, type, success = false, message = "Failed to read local file")
                    atomicWrite(resolvedDest, bytes)

                    return@withContext StepExecutionResult(
                        id,
                        type,
                        success = true,
                        message = "Copied local file",
                        createdFiles = listOf(resolvedDest.toString())
                    )
                } catch (t: Throwable) {
                    ctx.logger.error("Local file copy failed for {}", resolvedDest.toString().redactUserPath(), t)
                    return@withContext StepExecutionResult(id, type, false, t.message)
                }
            }

            try {
                val bytes = sharedClient.get(url).body<ByteArray>()
                atomicWrite(resolvedDest, bytes)
                return@withContext StepExecutionResult(
                    id,
                    type,
                    success = true,
                    message = "Downloaded",
                    createdFiles = listOf(resolvedDest.toString())
                )
            } catch (t: Throwable) {
                ctx.logger.error("Fetch failed for destination {}", resolvedDest.toString().redactUserPath(), t)
                return@withContext StepExecutionResult(id, type, success = false, message = t.message)
            }
        } catch (t: Throwable) {
            logger.error("Fetch step unexpected failure for destination {}", resolvedDest.toString().redactUserPath(), t)
            StepExecutionResult(id, type, success = false, message = t.message)
        }
    }

}

/**
 * Extracts a zip archive into a destination folder.
 */
class ExtractStep(
    override val id: String,
    override val type: String = "extract",
    private val src: String,
    private val dest: String
) : GeneratorStep {
    companion object {
        /**
         * Create a step from a descriptor.
         */
        fun fromDescriptor(desc: GeneratorStepDescriptor): ExtractStep {
            val src = (desc.meta["src"]?.jsonPrimitive?.contentOrNull)
                ?: throw IllegalArgumentException("extract step missing 'src'")
            val dest = (desc.meta["dest"]?.jsonPrimitive?.contentOrNull)
                ?: throw IllegalArgumentException("extract step missing 'dest'")
            return ExtractStep(id = desc.id, src = src, dest = dest)
        }
    }

    override suspend fun execute(ctx: GeneratorContext): StepExecutionResult = withContext(Dispatchers.IO) {
        val srcPath = resolvePath(ctx, src)
        val destPath = resolvePath(ctx, dest)
        if (!srcPath.exists()) {
            return@withContext StepExecutionResult(id, type, success = false, message = "Source archive missing: $srcPath")
        }
        destPath.mkdirs()
        try {
            srcPath.inputStream().use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    val created = mutableListOf<String>()
                    while (entry != null) {
                        val outPath = destPath.resolve(entry.name).normalize()
                        if (entry.isDirectory) {
                            outPath.mkdirs()
                        } else {
                            outPath.parent().mkdirs()
                            BufferedOutputStream(FileOutputStream(outPath.toJFile())).use { fos ->
                                zis.copyTo(fos)
                            }
                            created.add(outPath.toString())
                        }
                        entry = zis.nextEntry
                    }
                    StepExecutionResult(id, type, success = true, message = "Extracted to $destPath", createdFiles = created)
                }
            }
        } catch (t: Throwable) {
            logger.error("Extract failed", t)
            StepExecutionResult(id, type, success = false, message = t.message)
        }
    }

}

/**
 * Creates a file from a template string.
 */
class CreateFileStep(
    override val id: String,
    override val type: String = "createFile",
    private val path: String,
    private val template: String,
    private val overwrite: Boolean = false
) : GeneratorStep {
    companion object {
        /**
         * Create a step from a descriptor.
         */
        fun fromDescriptor(desc: GeneratorStepDescriptor): CreateFileStep {
            val path = (desc.meta["path"]?.jsonPrimitive?.contentOrNull)
                ?: throw IllegalArgumentException("createFile step missing 'path'")
            val template = (desc.meta["template"]?.jsonPrimitive?.contentOrNull)
                ?: throw IllegalArgumentException("createFile step missing 'template'")
            val overwrite = desc.meta["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
            return CreateFileStep(desc.id, path = path, template = template, overwrite = overwrite)
        }
    }

    override suspend fun execute(ctx: GeneratorContext): StepExecutionResult = withContext(Dispatchers.IO) {
        val resolved = resolvePath(ctx, path)
        if (resolved.exists() && !overwrite) {
            return@withContext StepExecutionResult(id, type, success = false, message = "File exists: $resolved")
        }
        val content = substitute(template, ctx.variables)
        resolved.parent().mkdirs()
        atomicWrite(resolved, content.toByteArray(StandardCharsets.UTF_8))
        StepExecutionResult(id, type, success = true, message = "Created file", createdFiles = listOf(resolved.toString()))
    }

    private fun substitute(template: String, vars: Map<String, String>): String {
        var out = template
        for ((k, v) in vars) {
            out = out.replace("{{${k}}}", v)
        }
        return out
    }
}

/**
 * Applies a patch to an existing file.
 */
class PatchFileStep(
    override val id: String,
    override val type: String = "patchFile",
    private val path: String,
    private val mode: String = "append",
    private val marker: String? = null,
    private val content: String
) : GeneratorStep {
    companion object {
        /**
         * Create a step from a descriptor.
         */
        fun fromDescriptor(desc: GeneratorStepDescriptor): PatchFileStep {
            val path = (desc.meta["path"]?.jsonPrimitive?.contentOrNull)
                ?: throw IllegalArgumentException("patchFile step missing 'path'")
            val mode = desc.meta["mode"]?.jsonPrimitive?.contentOrNull ?: "append"
            val marker = desc.meta["marker"]?.jsonPrimitive?.contentOrNull
            val content = (desc.meta["content"]?.jsonPrimitive?.contentOrNull) ?: ""
            return PatchFileStep(desc.id, path = path, mode = mode, marker = marker, content = content)
        }
    }

    override suspend fun execute(ctx: GeneratorContext): StepExecutionResult = withContext(Dispatchers.IO) {
        val resolved = resolvePath(ctx, path)
        if (!resolved.exists()) {
            return@withContext StepExecutionResult(id, type, success = false, message = "Target missing: $resolved")
        }
        val prior = resolved.readTextOrNull()
            ?: return@withContext StepExecutionResult(id, type, success = false, message = "Failed to read: $resolved")
        val newContent = when (mode) {
            "append" -> prior + "\n" + substitute(content, ctx.variables)
            "replaceMarker" -> {
                val m = marker ?: return@withContext StepExecutionResult(id, type, success = false, message = "Missing marker for replaceMarker")
                if (!prior.contains(m)) {
                    return@withContext StepExecutionResult(id, type, success = false, message = "Marker not found")
                }
                prior.replace(m, substitute(content, ctx.variables))
            }
            else -> return@withContext StepExecutionResult(id, type, success = false, message = "Unknown mode: $mode")
        }
        atomicWrite(resolved, newContent.toByteArray(StandardCharsets.UTF_8))
        StepExecutionResult(id, type, success = true, message = "Patched", modifiedFiles = listOf(resolved.toString()))
    }

    private fun substitute(template: String, vars: Map<String, String>): String {
        var out = template
        for ((k, v) in vars) out = out.replace("{{${k}}}", v)
        return out
    }
}

/**
 * Runs a command in the project directory.
 */
class RunCommandStep(
    override val id: String,
    override val type: String = "runCommand",
    private val command: String
) : GeneratorStep {
    companion object {
        /**
         * Create a step from a descriptor.
         */
        fun fromDescriptor(desc: GeneratorStepDescriptor): RunCommandStep {
            val cmd = (desc.meta["command"]?.jsonPrimitive?.contentOrNull)
                ?: throw IllegalArgumentException("runCommand step missing 'command'")
            return RunCommandStep(desc.id, command = cmd)
        }
    }

    override suspend fun execute(ctx: GeneratorContext): StepExecutionResult = withContext(Dispatchers.IO) {
        try {
            val workingDir = VPath.parse(ctx.workingDir.toString())
            val parsed = parseCommand(command)
            ctx.logger.info(
                "Running command in {}",
                workingDir.toString().redactUserPath()
            )
            val procBuilder = ProcessBuilder(parsed)
                .directory(workingDir.toJFile())
                .redirectErrorStream(true)
            val proc = procBuilder.start()
            val out = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            val success = exit == 0
            StepExecutionResult(id, type, success = success, message = "Exit=$exit\n$out")
        } catch (t: Throwable) {
            StepExecutionResult(id, type, success = false, message = t.message)
        }
    }

    private fun parseCommand(cmd: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0
        while(i < cmd.length) {
            val c = cmd[i]
            when {
                c == '\\' && i + 1 < cmd.length -> {
                    i++
                    sb.append(cmd[i])
                }
                c == '\'' && !inDouble -> {
                    inSingle = !inSingle
                }
                c == '"' && !inSingle -> {
                    inDouble = !inDouble
                }
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if(sb.isNotEmpty()) {
                        parts.add(sb.toString())
                        sb.setLength(0)
                    }
                }
                else -> sb.append(c)
            }
            i++
        }

        if(sb.isNotEmpty()) parts.add(sb.toString())
        return parts
    }
}
