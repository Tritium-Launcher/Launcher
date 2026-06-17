package io.github.tritium_launcher.launcher.extension

import io.github.tritium_launcher.launcher.logger
import org.koin.core.module.Module
import java.util.*

object ExtensionLoader {
    private val logger = logger()
    private var cachedClasspath: List<Extension>? = null

    var allExtensions: List<Extension> = emptyList()
        internal set

    fun discover(): List<Extension> {
        if (cachedClasspath == null) {
            cachedClasspath = ServiceLoader.load(Extension::class.java)
                .iterator()
                .asSequence()
                .toList()
            logger.info("Discovered {} extensions: {}", cachedClasspath!!.size, cachedClasspath!!.joinToString { it.javaClass.simpleName })
        }
        return cachedClasspath!!
    }

    fun discoveredModules(): List<Module> = discover().flatMap { it.modules }
}
