package io.github.tritium_launcher.launcher.extension

import org.koin.core.module.Module
import java.util.*

object ExtensionLoader {

    fun discover(): List<Extension> = ServiceLoader.load(Extension::class.java)
        .iterator()
        .asSequence()
        .toList()

    fun discoveredModules(): List<Module> = discover().flatMap { it.modules }
}