/**
 * IO helpers shared across the project.
 */
package io.github.tritium_launcher.launcher.io

import kotlinx.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Atomically write [data] to [path], creating parent directories when needed.
 *
 * Writes to a temporary file in the same directory and then replaces the target. If the platform
 * does not support atomic moves, falls back to a non-atomic replace.
 */
@Throws(IOException::class)
fun atomicWrite(path: VPath, data: ByteArray, durable: Boolean = false) {
    val target = path.toJPath()
    val parent = target.parent ?: throw IOException("No parent for path $path")
    Files.createDirectories(parent)
    val tmp = parent.resolve(".tmp-${System.nanoTime()}-${target.fileName}")
    try {
        if (durable) {
            FileChannel.open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC
            ).use { ch ->
                ch.write(ByteBuffer.wrap(data))
                ch.force(true)
            }
        } else {
            Files.write(tmp, data)
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        if (durable) {
            runCatching {
                FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
            }
        }
    } finally {
        try {
            Files.deleteIfExists(tmp)
        } catch (_: Exception) {
        }
    }
}

@Throws(IOException::class)
fun atomicWriteText(path: VPath, text: String, charset: Charset = Charsets.UTF_8, durable: Boolean = false) {
    atomicWrite(path, text.toByteArray(charset), durable = durable)
}
