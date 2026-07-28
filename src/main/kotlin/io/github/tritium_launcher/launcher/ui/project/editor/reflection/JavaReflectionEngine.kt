/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.reflection

import io.github.tritium_launcher.api.core.project.ProjectBase
import io.github.tritium_launcher.api.editor.intelligence.CompletionItem
import io.github.tritium_launcher.api.editor.intelligence.CompletionItemKind
import io.github.tritium_launcher.api.io.VPath
import io.github.tritium_launcher.api.logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap

object JavaReflectionEngine {
    private val logger = logger()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val classLoaderCache = ConcurrentHashMap<String, CachedClassLoader>()

    private data class CachedClassLoader(
        val loader: URLClassLoader,
        val projectDir: VPath,
    )

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down JavaReflectionEngine: closing {} class loaders", classLoaderCache.size)
            classLoaderCache.values.forEach { closeQuietly(it.loader) }
            classLoaderCache.clear()
        })
    }

    @Serializable
    data class ReflectedClass(
        val className: String,
        val methods: List<ReflectedMethod>,
        val fields: List<ReflectedField>,
        val constructors: List<ReflectedConstructor>,
    ) {
        fun toCompletionItems(): List<CompletionItem> {
            val items = mutableListOf<CompletionItem>()
            for (ctor in constructors) {
                items.add(CompletionItem(
                    label = "new",
                    kind = CompletionItemKind.Method,
                    detail = "(${ctor.parameterTypes.joinToString { it }}): $className",
                ))
            }
            for (method in methods) {
                items.add(CompletionItem(
                    label = method.name,
                    kind = if (method.isStatic) CompletionItemKind.Function else CompletionItemKind.Method,
                    detail = "(${method.parameterTypes.joinToString { it }}): ${method.returnType}",
                ))
            }
            for (field in fields) {
                items.add(CompletionItem(
                    label = field.name,
                    kind = if (field.isStatic) CompletionItemKind.Variable else CompletionItemKind.Field,
                    detail = field.type,
                ))
            }
            return items
        }
    }

    @Serializable
    data class ReflectedMethod(
        val name: String,
        val returnType: String,
        val parameterTypes: List<String>,
        val isStatic: Boolean,
    )

    @Serializable
    data class ReflectedField(
        val name: String,
        val type: String,
        val isStatic: Boolean,
    )

    @Serializable
    data class ReflectedConstructor(
        val parameterTypes: List<String>,
    )

    private fun collectJarUrls(project: ProjectBase): List<URL> {
        val urls = mutableListOf<URL>()
        val libsDir = project.projectDir.resolve(".tr").resolve("libraries")
        if (libsDir.exists()) {
            libsDir.walk(recursive = true).forEach { file ->
                if (file.isFile() && file.fileName().endsWith(".jar")) {
                    try {
                        urls.add(file.toJFile().toURI().toURL())
                    } catch (_: Exception) {}
                }
            }
        }
        val modsDir = project.projectDir.resolve("mods")
        if (modsDir.exists()) {
            modsDir.walk(recursive = false).forEach { file ->
                if (file.isFile() && file.fileName().endsWith(".jar")) {
                    try {
                        urls.add(file.toJFile().toURI().toURL())
                    } catch (_: Exception) {}
                }
            }
        }
        val versionsDir = project.projectDir.resolve(".tr").resolve("versions")
        if (versionsDir.exists()) {
            versionsDir.walk(recursive = true).forEach { file ->
                if (file.isFile() && file.fileName().endsWith(".jar")) {
                    try {
                        urls.add(file.toJFile().toURI().toURL())
                    } catch (_: Exception) {}
                }
            }
        }
        return urls
    }

    private fun getOrCreateClassLoader(project: ProjectBase): URLClassLoader {
        val cacheKey = project.path.toAbsolute().toString()
        val existing = classLoaderCache[cacheKey]
        if (existing != null && existing.projectDir == project.projectDir) {
            return existing.loader
        }
        if (existing != null) {
            logger.info("Project dir changed for {}, closing old class loader", project.name)
            closeQuietly(existing.loader)
        }
        val urls = collectJarUrls(project)
        logger.info("Creating URLClassLoader for {} with {} jars", project.name, urls.size)
        val loader = URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
        classLoaderCache[cacheKey] = CachedClassLoader(loader, project.projectDir)
        return loader
    }

    /**
     * Invalidates the class loader for [projectDir].
     * Call when a project is closed to release its JAR file descriptors.
     */
    fun invalidateProject(projectDir: VPath) {
        val absPath = projectDir.expandHome().toAbsolute().normalize().toString()
        val entry = classLoaderCache.remove(absPath)
        if (entry != null) {
            logger.info("Invalidating class loader for {}", projectDir)
            closeQuietly(entry.loader)
        }
    }

    /** Closes all cached class loaders. Called on shutdown. */
    fun invalidateAll() {
        classLoaderCache.values.forEach { closeQuietly(it.loader) }
        classLoaderCache.clear()
    }

    private fun closeQuietly(loader: URLClassLoader) {
        try {
            loader.close()
        } catch (e: Exception) {
            logger.warn("Failed to close URLClassLoader: {}", e.message)
        }
    }

    fun reflectClass(project: ProjectBase, className: String): ReflectedClass? {
        return try {
            val loader = getOrCreateClassLoader(project)
            val clazz = Class.forName(className, false, loader)

            val methods = clazz.declaredMethods.map { m ->
                ReflectedMethod(
                    name = m.name,
                    returnType = m.returnType.name,
                    parameterTypes = m.parameterTypes.map { it.name },
                    isStatic = Modifier.isStatic(m.modifiers),
                )
            }.sortedBy { it.name }

            val fields = clazz.declaredFields.map { f ->
                ReflectedField(
                    name = f.name,
                    type = f.type.name,
                    isStatic = Modifier.isStatic(f.modifiers),
                )
            }.sortedBy { it.name }

            val constructors = clazz.declaredConstructors.map { c ->
                ReflectedConstructor(
                    parameterTypes = c.parameterTypes.map { it.name },
                )
            }

            ReflectedClass(
                className = className,
                methods = methods,
                fields = fields,
                constructors = constructors,
            )
        } catch (e: ClassNotFoundException) {
            logger.warn("Class not found via reflection: {}", className)
            null
        } catch (e: Exception) {
            logger.warn("Failed to reflect class {}: {}", className, e.message)
            null
        }
    }

    private fun cacheDir(project: ProjectBase): VPath {
        return project.projectDir.resolve(".tr").resolve("intelligence").resolve("reflection")
    }

    private fun cacheFile(project: ProjectBase, className: String): VPath {
        val path = className.replace('.', '/') + ".json"
        return cacheDir(project).resolve(path)
    }

    fun loadCachedClass(project: ProjectBase, className: String): ReflectedClass? {
        val file = cacheFile(project, className)
        if (!file.exists()) return null
        return try {
            val text = file.readTextOrNull() ?: return null
            json.decodeFromString<ReflectedClass>(text)
        } catch (e: Exception) {
            logger.warn("Failed to read reflection cache for {}: {}", className, e.message)
            null
        }
    }

    fun saveCachedClass(project: ProjectBase, clazz: ReflectedClass) {
        val file = cacheFile(project, clazz.className)
        file.parent().mkdirs()
        val text = json.encodeToString(clazz)
        file.writeBytes(text.toByteArray())
    }

    fun getCachedCompletions(project: ProjectBase, className: String): List<CompletionItem>? {
        val cached = loadCachedClass(project, className) ?: return null
        return cached.toCompletionItems()
    }

    fun findLoadClassForVar(fullText: String, varName: String): String? {
        val escaped = Regex.escape(varName)
        val regex = Regex("""(?:let|var|const)\s+$escaped\s*=\s*Java\.loadClass\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        return regex.find(fullText)?.groupValues?.getOrNull(1)
    }

    fun resolveReflectedCompletions(project: ProjectBase, fullText: String, varName: String): List<CompletionItem>? {
        val className = findLoadClassForVar(fullText, varName) ?: return null
        return getCachedCompletions(project, className)
    }
}
