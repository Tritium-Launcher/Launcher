package io.github.tritium_launcher.launcher.io

import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.matches
import io.github.tritium_launcher.launcher.userHome
import io.qt.core.QUrl
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.io.*
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Custom [Path] or [File] implementation for generally easier use and Tritium defaults.
 */
@OptIn(ExperimentalTime::class)
@Serializable(with = VPath.Serializer::class)
data class VPath(
    private val root: String?,
    private val segments: List<String>
): Comparable<VPath> {

    internal val logger = logger()

    val isAbsolute: Boolean = root != null

    override fun toString(): String {
        val joined = segments.joinToString("/")
        return when {
            root == null && joined.isEmpty() -> "."
            root == null -> joined
            joined.isEmpty() -> root
            else -> when {
                root == "/" -> "$root$joined"
                else -> "$root/$joined"
            }
        }
    }

    /**
     * Resolve [other] against this path.
     * - If [other] is absolute, returns [other].
     * - Otherwise returns this + other segments.
     */
    fun resolve(other: VPath): VPath = if (other.isAbsolute) other else VPath(root, segments + other.segments)

    /**
     * Resolve a relative string or fragment (accepts either "a/b" or "a\\b").
     */
    fun resolve(fragment: String): VPath = resolve(parse(fragment))

    fun join(vararg parts: String): VPath = parts.fold(this) { acc, p -> acc.resolve(parse(p)) }

    /**
     * Normalize this path:
     * - Remove "." segments.
     * - Collapse ".." where possible; on an absolute path leading ".." are discarded
     *     (i.e. you can't go above the root). On a relative path leading ".." are preserved.
     */
    fun normalize(): VPath {
        val out = ArrayList<String>(segments.size)
        for (seg in segments) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    if (out.isNotEmpty() && out.last() != "..") {
                        out.removeAt(out.size - 1)
                    } else {
                        if (!isAbsolute) {
                            out.add("..")
                        }
                    }
                }

                else -> out.add(seg)
            }
        }

        return VPath(root, out)
    }

    /**
     * Return an absolute path by resolving against [base] if this is relative.
     * If already absolute, returns this.normalize()
     */
    fun toAbsolute(base: VPath = cwd()): VPath = if (isAbsolute) normalize() else base.resolve(this).normalize()

    /**
     * Return an absolute path as a String.
     */
    fun toAbsoluteString(base: VPath = cwd()): String = toAbsolute(base).toString()

    /**
     * Attempt to compute a relative path from this to [target].
     */
    fun relativize(target: VPath): VPath {
        if (this.root != target.root) {
            throw IllegalArgumentException("Cannot relativize paths with different roots: $root vs ${target.root}")
        }

        val a = this.segments
        val b = target.segments
        var idx = 0
        while (idx < a.size && idx < b.size && a[idx] == b[idx]) idx++

        val up = List(a.size - idx) { ".." }
        val down = b.subList(idx, b.size)
        val combined = up + down
        return VPath(null, combined)
    }

    /**
     * Expand a leading '~' to the user home directory.
     * - "~" -> user.home
     * - "~/sub" -> user.home/sub
     */
    fun expandHome(): VPath {
        if (segments.isEmpty()) return this
        val first = segments.first()
        if (first == "~" || first.startsWith("~/")) {
            val rest = if (segments.size > 1) segments.drop(1) else emptyList()
            return userHome.resolve(VPath(null, rest)).normalize()
        }
        val asString = this.toString()
        if (asString.startsWith("~")) {
            val suffix = asString.removePrefix("~").removePrefix("/")
            return userHome.resolve(parse(suffix)).normalize()
        }
        return this
    }

    fun segments(): List<String> = segments.toList()


    /**
     * Convert to [Path].
     */
    fun toJPath(): Path = Paths.get(this.toString())

    /**
     * Convert to [java.io.File].
     */
    fun toJFile(): File = File(this.toString())

    /**
     * Convert to a Qt [QUrl] file URL.
     * Relative paths are resolved against [base].
     */
    fun toQUrl(base: VPath = cwd()): QUrl = QUrl.fromLocalFile(toAbsolute(base).toJFile().absolutePath)

    /**
     * Build a canonical local file [URI] using the JDK implementation.
     * Relative paths are resolved to absolute first.
     */
    fun toFileUriEncoded(): URI = toAbsolute().toJFile().toURI()

    /**
     * Return true when filename starts with '.'.
     */
    fun isHiddenName(): Boolean = fileName().startsWith('.')

    /**
     * Exists on disk.
     */
    fun exists(followSymLinks: Boolean = true): Boolean = try {
        if(followSymLinks) {
            Files.exists(toJPath())
        } else {
            Files.exists(toJPath(), LinkOption.NOFOLLOW_LINKS)
        }
    } catch (e: Exception) {
        logger.warn("Exception checking existence on path '$this'", e)
        false
    }

    /**
     * Returns true if this path exists on disk, is a directory and contains at least one entry.
     */
    fun existsNotEmpty(vararg linkOptions: LinkOption): Boolean {
        val p = toJPath()
        return try {
            if (!Files.exists(p, *linkOptions) || !Files.isDirectory(p, *linkOptions)) return false
            Files.newDirectoryStream(p).use { stream ->
                stream.iterator().hasNext()
            }
        } catch (e: Exception) {
            logger.warn("Exception checking existence and contents on path '$this'", e)
            false
        }
    }

    /**
     * Return the filename.
     */
    fun fileName(): String = segments.lastOrNull() ?: ""

    /**
     * Return true if file name matches.
     */
    fun isFileName(vararg name: String): Boolean = fileName().matches(*name)

    /**
     * Is a regular file on disk.
     */
    fun isFile(vararg linkOptions: LinkOption): Boolean =
        try {
            toJPath().isRegularFile(*linkOptions)
        } catch (e: Exception) {
            logger.warn("Exception checking if is File", e)
            false
        }

    /**
     * Is a directory on disk.
     */
    fun isDir(vararg linkOptions: LinkOption): Boolean =
        try {
            toJPath().isDirectory(*linkOptions)
        } catch (e: Exception) {
            logger.warn("Exception checking if is Directory", e)
            false
        }

    /**
     * Size in bytes when file exists or null.
     */
    fun sizeOrNull(): Long? = try {
        if (!exists()) null else Files.size(toJPath())
    } catch (e: Exception) {
        logger.warn("Exception getting size from file", e)
        null
    }

    /**
     * Create directories for this path.
     */
    fun mkdirs(): Boolean = try {
        Files.createDirectories(toJPath())
        true
    } catch (e: Exception) {
        logger.error("Exception creating directories for path '$this'", e)
        false
    }

    /**
     * Create an empty file and directories for this path.
     */
    fun touch(): VPath {
        try {
            val p = toJPath()
            p.parent?.let { Files.createDirectories(it) }
            if(!Files.exists(p)) Files.createFile(p)
        } catch (e: Exception) {
            logger.error("Exception touching path '$this'", e)
            throw e
        }
        return this
    }

    /**
     * Read all bytes if a file or null
     */
    fun bytesOrNull(): ByteArray? = try {
        Files.readAllBytes(toJPath())
    } catch (e: Exception) {
        logger.warn("Exception reading bytes for path '$this'", e)
        null
    }

    /**
     * Read all bytes if a file or 0
     */
    fun bytesOrNothing(): ByteArray = try {
        Files.readAllBytes(toJPath())
    } catch (e: Exception) {
        logger.warn("Exception reading bytes for path '$this'", e)
        byteArrayOf()
    }

    /**
     * Write bytes, overwrites.
     */
    fun writeBytes(bytes: ByteArray, vararg options: OpenOption): VPath {
        try {
            val p = toJPath()
            p.parent?.let { Files.createDirectories(it) }
            Files.write(p, bytes, *options.ifEmpty { arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING) })
        } catch (e: Exception) {
            logger.error("Exception writing bytes for path '$this'", e)
            throw e
        }
        return this
    }

    /**
     * Atomically write bytes via temp file + replace.
     */
    fun writeBytesAtomic(bytes: ByteArray, durable: Boolean = false): VPath {
        atomicWrite(this, bytes, durable = durable)
        return this
    }

    /**
     * Atomically write text via temp file + replace.
     */
    fun writeTextAtomic(text: String, charset: Charset = Charsets.UTF_8, durable: Boolean = false): VPath {
        atomicWrite(this, text.toByteArray(charset), durable = durable)
        return this
    }

    suspend fun readBytesAsync(): ByteArray? = withContext(IODispatchers.FileIO) { bytesOrNull() }

    suspend fun writeBytesAsync(bytes: ByteArray, vararg options: OpenOption): VPath = withContext(IODispatchers.FileIO) {
        writeBytes(bytes, *options)
    }

    suspend fun listAsync(): List<VPath> = withContext(IODispatchers.FileIO) { list() }

    /**
     * List child names, returns an empty list if not directory or on error.
     */
    fun list(): List<VPath> {
        return try {
            val p = toJPath()
            if (!Files.isDirectory(p)) return emptyList()
            Files.list(p).use { stream ->
                stream.map { parse(it.toString()) }.toList()
            }
        } catch (e: Exception) {
            logger.warn("Exception listing for path '$this'", e)
            emptyList()
        }
    }

    /**
     * List children of this path filtered by the predicate.
     *
     * @param filter a predicate receiving each child as [VPath], return true to keep.
     * @param followSymLinks if true, symlink-following is allowed for the directory check.
     * @return a [List] of matching child [VPath] entries. Return empty list if this path is
     *          not a directory or on IO error.
     */
    fun listFiles(
        followSymLinks: Boolean = true,
        filter: (VPath) -> Boolean
    ): List<VPath> {
        return try {
            val p = toJPath()
            if(!Files.isDirectory(p, *if(followSymLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)))
                return emptyList()

            Files.list(p).use { stream ->
                stream.map { parse(it.toString()) }
                    .filter { try { filter(it) } catch (_: Exception) { false } }
                    .toList()
            }
        } catch (e: Exception) {
            logger.warn("Exception listing files for path '$this'", e)
            emptyList()
        }
    }

    fun <R : Comparable<R>> listFilesSortedBy(
        followSymLinks: Boolean = true,
        selector: (VPath) -> R?,
        filter: (VPath) -> Boolean = { true }
    ): List<VPath> {
        return try {
            val p = toJPath()
            if(!Files.isDirectory(p, *if(followSymLinks) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS))) {
                return emptyList()
            }

            Files.list(p).use { stream ->
                stream
                    .map { parse(it.toString()) }
                    .filter { try { filter(it) } catch (_: Exception) { false } }
                    .toList()
                    .sortedWith { a, b ->
                        val ra = selector(a)
                        val rb = selector(b)

                        when {
                            ra === rb -> 0
                            ra == null -> -1
                            rb == null -> 1
                            else -> ra.compareTo(rb)
                        }
                    }
            }
        } catch (e: Exception) {
            logger.warn("Exception listing sorted files for path '{}'", this, e)
            emptyList()
        }
    }

    /**
     * Match this path against the provided glob. Uses [PathMatcher].
     */
    fun matchesJGlob(globExpr: String): Boolean = try {
        val matcher = FileSystems.getDefault().getPathMatcher(globExpr)
        matcher.matches(this.toJPath())
    } catch (e: Exception) {
        logger.warn("Exception matching against glob for path '$this'", e)
        false
    }

    /**
     * Matches using regex against the string form of this path.
     */
    fun matchesRegex(regex: Regex): Boolean = regex.matches(this.toString())

    /**
     * Returns extension for file.
     */
    fun extension(): String {
        val name = fileName()
        val idx = name.lastIndexOf('.')
        return if(idx == -1) "" else name.substring(idx + 1)
    }

    /**
     * Returns extension for file or null.
     */
    fun extensionOrNull(): String? = extension().ifEmpty { null }

    /**
     * Returns true if file extension matches.
     */
    fun hasExtension(ext: String, ignoreCase: Boolean = true): Boolean {
        val e = extensionOrNull() ?: return false
        return if(ignoreCase) e.equals(ext, true) else e == ext
    }

    /**
     * Delete file or directory, fails if path is not empty.
     */
    fun delete(): Boolean = try {
        Files.deleteIfExists(toJPath())
    } catch (e: Exception) {
        logger.warn("Exception deleting file / directory at path '$this'", e)
        false
    }

    /**
     * Return the parent path.
     */
    fun parent(): VPath = when {
        segments.isEmpty() && isAbsolute -> this

        isAbsolute -> {
            if(segments.size <= 1) VPath(root, emptyList())
            else VPath(root, segments.dropLast(1))
        }

        segments.size > 1 -> VPath(root, segments.dropLast(1))

        else -> VPath(null, emptyList())
    }

    fun startsWith(prefix: VPath): Boolean {
        if(this.root != prefix.root) return false
        if(prefix.segments.size > this.segments.size) return false
        for(i in prefix.segments.indices) if(this.segments[i] != prefix.segments[i]) return false
        return true
    }

    fun endsWith(suffix: VPath): Boolean {
        if(suffix.segments.size > this.segments.size) return false
        val offset = this.segments.size - suffix.segments.size
        for(i in suffix.segments.indices) if(this.segments[offset + i] != suffix.segments[i]) return false
        return true
    }

    fun subpath(beginIndex: Int, endIndex: Int): VPath {
        val sub = segments.subList(beginIndex, endIndex)
        return VPath(null, sub)
    }

    /**
     * Find the common ancestor of this and [other], returns null if none (different roots).
     */
    fun commonAncestorOrNull(other: VPath): VPath? {
        if(this.root != other.root) return null
        val a = this.segments
        val b = other.segments
        val out = ArrayList<String>()
        val lim = minOf(a.size, b.size)
        for(i in 0 until lim) {
            if(a[i] == b[i]) out.add(a[i]) else break
        }
        return VPath(this.root, out)
    }

    /**
     * Walk the file tree rooted at this path.
     *
     * This calls into [java.nio.file] and is best-effort.
     * The consumer receives VPath entries relative to this path.
     */
    fun walk(recursive: Boolean = true, maxDepth: Int = Int.MAX_VALUE): Sequence<VPath> = sequence {
        val p = toJPath()
        if(!Files.exists(p)) return@sequence
        if(!recursive) {
            Files.list(p).use { stream ->
                for(it in stream) yield(parse(it.toString()))
            }
        } else {
            Files.walk(p, maxDepth).use { stream ->
                for(it in stream) yield(parse(it.toString()))
            }
        }
    }

    override fun compareTo(other: VPath): Int {
        val rCmp = compareValues(root, other.root)
        if(rCmp != 0) return rCmp
        val min = minOf(segments.size, other.segments.size)
        for(i in 0 until min) {
            val c = segments[i].compareTo(other.segments[i])
            if(c != 0) return c
        }
        return segments.size - other.segments.size
    }

    fun isRoot(): Boolean = isAbsolute && segments.isEmpty()
    fun isEmpty(): Boolean = segments.isEmpty() && root == null
    fun isNotEmpty(): Boolean = !isEmpty()

    /**
     * Return the last-modified timestamp of this path as an [Instant] or null if it doesn't exist or cannot be read.
     */
    fun lastModifiedOrNull(followSymLinks: Boolean = true): Instant? = try {
        val ft: FileTime
        if(followSymLinks) {
            ft = Files.getLastModifiedTime(toJPath())
        } else {
            ft = Files.getLastModifiedTime(toJPath(), LinkOption.NOFOLLOW_LINKS)
        }
        Instant.fromEpochMilliseconds(ft.toMillis())
    } catch (e: Exception) {
        logger.error("Exception getting last modified timestamp for path '$this'", e)
        null
    }

    suspend fun lastModifiedOrNullAsync(followSymLinks: Boolean = true): Instant? =
        withContext(IODispatchers.FileIO) { lastModifiedOrNull(followSymLinks) }

    fun creationOrNull(followSymLinks: Boolean = true): Instant? = try {
        val attrs: BasicFileAttributes
        if(followSymLinks) {
            attrs = Files.readAttributes(toJPath(), BasicFileAttributes::class.java)
        } else {
            attrs = Files.readAttributes(toJPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
        Instant.fromEpochMilliseconds(attrs.creationTime().toMillis())
    } catch (e: Exception) {
        logger.error("Exception getting creation timestamp for path '$this'", e)
        null
    }

    suspend fun creationOrNullAsync(followSymLinks: Boolean = true): Instant? =
        withContext(IODispatchers.FileIO) { creationOrNull(followSymLinks) }

    fun lastAccessOrNull(followSymLinks: Boolean = true): Instant? = try {
        val attrs: BasicFileAttributes
        if(followSymLinks) {
            attrs = Files.readAttributes(toJPath(), BasicFileAttributes::class.java)
        } else {
            attrs = Files.readAttributes(toJPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }
        Instant.fromEpochMilliseconds(attrs.lastAccessTime().toMillis())
    } catch (e: Exception) {
        logger.error("Exception getting last modified timestamp for path '$this'", e)
        null
    }

    suspend fun lastAccessOrNullAsync(followSymLinks: Boolean = true): Instant? =
        withContext(IODispatchers.FileIO) { lastAccessOrNull(followSymLinks) }

    /**
     * Read the file contents as text.
     *
     * @return File text, or null if the file doesn't exist or an error occurs.
     */
    fun readTextOrNull(charset: Charset = Charsets.UTF_8): String? = try {
        String(Files.readAllBytes(toJPath()), charset)
    } catch (e: Exception) {
        logger.warn("Failed to read text from path '$this'", e)
        null
    }

    /**
     * Read the file contents as text.
     *
     * @return File text, or null if the file doesn't exist or an error occurs.
     */
    fun readTextOr(default: String, charset: Charset = Charsets.UTF_8): String = try {
        String(Files.readAllBytes(toJPath()), charset)
    } catch (_: Throwable) {
        default
    }

    /**
     * Read the file contents as text.
     *
     * @return File text, or null if the file doesn't exist or an error occurs.
     */
    inline fun readTextOr(charset: Charset = Charsets.UTF_8, action: () -> Unit): String? = try {
        String(Files.readAllBytes(toJPath()), charset)
    } catch (_: Throwable) {
        action()
        null
    }

    suspend fun readTextOrNullAsync(charset: Charset = Charsets.UTF_8): String? =
        withContext(IODispatchers.FileIO) { readTextOrNull(charset) }

    fun readLinesOrNull(charset: Charset = Charsets.UTF_8): List<String>? = try {
        Files.readAllLines(toJPath(), charset)
    } catch (e: Exception) {
        logger.warn("Failed to read lines from path '$this'", e)
        null
    }

    suspend fun readLinesOrNullAsync(charset: Charset = Charsets.UTF_8): List<String>? =
        withContext(IODispatchers.FileIO) { readLinesOrNull(charset) }

    fun bufferedReaderOrNull(charset: Charset = Charsets.UTF_8): BufferedReader? = try {
        Files.newBufferedReader(toJPath(), charset)
    } catch (e: Exception) {
        logger.warn("Failed to get BufferedReader for path '$this'", e)
        null
    }

    fun inputStream(vararg openOption: OpenOption = emptyArray()): InputStream = try {
        Files.newInputStream(toJPath(), *openOption)
    } catch (e: Exception) {
        logger.warn("Failed to get InputStream for path '$this'", e)
        throw e
    }

    fun outputStream(vararg openOption: OpenOption = emptyArray()): OutputStream = try {
        Files.newOutputStream(toJPath(), *openOption)
    } catch (e: Exception) {
        logger.warn("Failed to get OutputStream for path '$this'", e)
        throw e
    }

    inline fun <R> useLinesOrNull(charset: Charset = Charsets.UTF_8, block: (Sequence<String>) -> R): R? {
        return try {
            Files.newBufferedReader(toJPath(), charset).use { br ->
                block(br.lineSequence())
            }
        } catch (_: Exception) {
            null
        }
    }



    operator fun div(other: VPath): VPath = this.resolve(other)
    operator fun div(other: String): VPath = this.resolve(parse(other))

    companion object {

        /** Parse a path string into a VPath. */
        @JvmStatic
        fun parse(input: String): VPath {
            val s = input.trim()

            if(s.isEmpty()) return VPath(null, emptyList())
            if(s == ".") return VPath(null, emptyList())

            // Handle Windows drive prefix
            val driveMatch = WINDOWS_DRIVE_ROOT_REGEX.find(s)
            if(driveMatch != null) {
                val drive = driveMatch.groupValues[1]
                val rest = driveMatch.groupValues[3]
                val segs = splitSegments(rest)
                return VPath(drive, segs)
            }

            if(s.startsWith("/") || s.startsWith("\\")) {
                val body = s.dropWhile { it == '/' || it == '\\' }
                val segs = splitSegments(body)
                return VPath("/", segs)
            }

            val segs = splitSegments(s)
            return VPath(null, segs)
        }

        private fun splitSegments(raw: String): List<String> {
            if(raw.isEmpty()) return emptyList()
            return raw.split(PATH_SEGMENT_SPLIT_REGEX).filter { it.isNotEmpty() }
        }

        /** String */
        @JvmStatic
        fun get(first: String): VPath = parse(first)

        /** VPath + VPath */
        @JvmStatic
        fun get(first: VPath, vararg more: VPath): VPath = more.fold(first) { acc, p -> acc.resolve(p) }

        /** String + VPath */
        @JvmStatic
        fun get(first: String, vararg more: VPath): VPath = get(parse(first), *more)

        /** VPath + String */
        @JvmStatic
        fun get(first: VPath, vararg more: String): VPath =
            more.fold(first) { acc, s -> acc.resolve(parse(s)) }

        /** String + String */
        @JvmStatic
        fun get(first: String, vararg more: String): VPath =
            get(parse(first), *more.map { parse(it) }.toTypedArray())

        @JvmStatic
        fun get(uri: URI): VPath = parseUri(uri)

        @JvmStatic
        fun get(first: URI, vararg more: URI): VPath = more.fold(get(first)) { acc, u -> acc.resolve(get(u)) }

        /** Current working directory */
        @JvmStatic
        fun cwd(): VPath {
            val cwd = System.getProperty("user.dir") ?: "."
            return parse(cwd)
        }

        private val WINDOWS_DRIVE_ROOT_REGEX = Regex("^([A-Za-z]:)([/\\\\])?(.*)$")
        private val PATH_SEGMENT_SPLIT_REGEX = Regex("[/\\\\]")

        private fun parseUri(uri: URI): VPath {
            fun percentDecode(raw: String?): String {
                if (raw == null) return ""
                val out = ByteArrayOutputStream(raw.length)
                var i = 0
                while (i < raw.length) {
                    val c = raw[i]
                    if (c == '%') {
                        if (i + 2 < raw.length) {
                            try {
                                val hi = Character.digit(raw[i + 1], 16)
                                val lo = Character.digit(raw[i + 2], 16)
                                if (hi >= 0 && lo >= 0) {
                                    out.write((hi shl 4) + lo)
                                    i += 3
                                    continue
                                }
                            } catch (_: Exception) {
                            }
                        }
                        out.write('%'.code)
                        i++
                    } else {
                        val bytes = c.toString().toByteArray(Charsets.UTF_8)
                        out.write(bytes)
                        i++
                    }
                }
                return String(out.toByteArray(), Charsets.UTF_8)
            }

            return when (uri.scheme?.lowercase()) {
                null -> {
                    val p = uri.path ?: uri.toString()
                    parse(percentDecode(p))
                }

                "file" -> {
                    val fromNio = runCatching { Paths.get(uri).toString() }.getOrNull()
                    if (!fromNio.isNullOrBlank()) {
                        parse(fromNio)
                    } else {
                        val rawPath = uri.rawPath ?: uri.path ?: ""
                        val decoded = percentDecode(rawPath)
                        parse(decoded)
                    }
                }

                else -> {
                    val rawPath = uri.rawPath
                    if (!rawPath.isNullOrEmpty()) {
                        val decoded = percentDecode(rawPath)
                        if (decoded.startsWith("/")) parse(decoded) else VPath(null, listOf(decoded))
                    } else {
                        VPath(null, listOf(uri.toString()))
                    }
                }
            }
        }
    }

    object Serializer: KSerializer<VPath> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("VPath", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: VPath
        ) { encoder.encodeString(value.toString()) }

        override fun deserialize(decoder: Decoder): VPath {
            val s = decoder.decodeString()
            return parse(s)
        }
    }
}
