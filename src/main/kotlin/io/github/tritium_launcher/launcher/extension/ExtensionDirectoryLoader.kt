package io.github.tritium_launcher.launcher.extension

import io.github.tritium_launcher.launcher.io.VPath
import io.ktor.utils.io.core.*
import org.koin.core.module.Module
import java.net.URLClassLoader
import java.util.*

object ExtensionDirectoryLoader {

    data class Result(val modules: List<Module>, val loaders: List<Closeable>)

    fun loadFrom(dir: VPath): Result {
        if(!dir.exists() || !dir.isDir()) return Result(emptyList(), emptyList())

        val modules = mutableListOf<Module>()
        val loaders = mutableListOf<Closeable>()

        dir.listFiles { f -> f.isFile() && f.hasExtension("jar") }.forEach { jar ->
            val url = jar.toFileUriEncoded().toURL()
            val loader = URLClassLoader(arrayOf(url), Extension::class.java.classLoader)
            try {
                val sl = ServiceLoader.load(Extension::class.java, loader)
                val found = sl.iterator().asSequence().toList()
                found.forEach { modules += it.modules }
                loaders += loader
            } catch (_: Throwable) {
                loader.close()
            }
        }

        return Result(modules, loaders)
    }
}