/**
 * Host bootstrap helpers for starting and stopping the core service container.
 *
 * Builds the registry core module, discovers extension modules (ServiceLoader and extension
 * directory), starts Koin, and then freezes registries to prevent late registrations.
 */
package io.github.tritium_launcher.launcher.bootstrap

import io.github.tritium_launcher.launcher.extension.ExtensionDirectoryLoader
import io.github.tritium_launcher.launcher.extension.ExtensionLoader
import io.github.tritium_launcher.launcher.extension.core.CoreExtension
import io.github.tritium_launcher.launcher.io.VPath
import io.github.tritium_launcher.launcher.registry.RegistryMngr
import io.github.tritium_launcher.launcher.settings.SettingsMngr
import io.ktor.utils.io.core.*
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.logger.slf4jLogger

private val registryCoreModule = module {
    single { RegistryMngr }
    single { SettingsMngr }
}

/**
 * Starts the host container and returns class loaders for directory-based extensions.
 *
 * @param loadExtDir Directory containing extension jars to load via [ExtensionDirectoryLoader].
 * @return Closeables for extension class loaders; pass to [stopHost] to release resources.
 */
internal fun startHost(loadExtDir: VPath): List<Closeable> {
    val core = listOf(registryCoreModule)
    val discoveredModules = mutableListOf<Module>()
    val loaders = mutableListOf<Closeable>()

    discoveredModules += ExtensionLoader.discoveredModules()

    val result = ExtensionDirectoryLoader.loadFrom(loadExtDir)
    discoveredModules += result.modules
    loaders += result.loaders

    discoveredModules += CoreExtension.modules

    startKoin {
        slf4jLogger()
        modules(core + discoveredModules)
    }

    val registryMngr = GlobalContext.get().get<RegistryMngr>()
    registryMngr.freezeAll()

    return loaders
}

/**
 * Stops the host container and closes any extension class loaders.
 */
internal fun stopHost(loaders: List<Closeable> = emptyList()) {
    loaders.forEach { it.close() }
    stopKoin()
}
