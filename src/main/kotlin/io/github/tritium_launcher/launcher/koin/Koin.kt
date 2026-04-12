package io.github.tritium_launcher.launcher.koin

import io.github.tritium_launcher.launcher.registry.Registrable
import io.github.tritium_launcher.launcher.registry.Registry
import io.github.tritium_launcher.launcher.registry.RegistryMngr
import org.koin.core.context.GlobalContext


inline fun <reified T: Registrable> getRegistry(name: String): Registry<T> =
    GlobalContext.get().get<RegistryMngr>().getOrCreateRegistry<T>(name)
