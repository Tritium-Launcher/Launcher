package io.github.tritium_launcher.launcher.registry

import io.github.tritium_launcher.launcher.logger
import java.util.*

/**
 * Thread safe registry that can integrate [java.util.ServiceLoader] provider discovery.
 */
class AutoLoadingRegistry<T : Any, P : Any>(
    idFn: (T) -> String,
    caseInsensitiveLookup: Boolean = true,

    /**
     * If supplied, ServiceLoader([providerClass]) will be used to discover providers.
     *
     * If null, no [java.util.ServiceLoader] behavior is available for the registry.
     */
    private val providerClass: Class<P>? = null,

    /**
     * Converts a provider instance (P) to a registry item (T).
     *
     * If [providerClass] != null then mapper must be supplied.
     */
    private val providerToItemMapper: ((P) -> T?)? = null,

    /**
     * When true and [providerClass] supplied, the registry will automatically call [loadProviders] during construction.
     */
    autoLoadOnInit: Boolean = false
): SimpleRegistry<T>(idFn, caseInsensitiveLookup) {

    private val logger = logger()

    init {
        require(!(providerClass != null && providerToItemMapper == null)) {
            "if providerClass is supplied, providerToItemMapper must be provided"
        }
        if(autoLoadOnInit && providerClass != null) loadProviders()
    }

    /**
     * Discover providers via [ServiceLoader] and register mapped items.
     */
    fun loadProviders() {
        val provCls = providerClass ?: return
        val mapper  = providerToItemMapper ?: return

        val loaderToTry  = listOf(
            { ServiceLoader.load(provCls) },
            { ServiceLoader.load(provCls, Thread.currentThread().contextClassLoader) }
        )

        var loader: ServiceLoader<P>? = null
        for(tryLoad in loaderToTry) {
            loader = try {
                tryLoad()
            } catch (e: ServiceConfigurationError) {
                logger.warn("ServiceLoader initial load failed for ${provCls.name}", e)
                null
            }
            if(loader != null) break
        }

        if(loader == null) {
            logger.info("No ServiceLoader available for ${provCls.name}")
            return
        }

        val iterator = try {
            loader.iterator()
        } catch (e: ServiceConfigurationError) {
            logger.error("ServiceLoader iterator failed for ${provCls.name}", e)
            return
        }

        while(true) {
            val provInstance = try {
                if (!iterator.hasNext()) break
                iterator.next()
            } catch (e: ServiceConfigurationError) {
                logger.error("Invalid service entry for ${provCls.name} - skipping remaining entries", e)
                break
            } catch (t: Throwable) {
                logger.error("Unexpected error obtaining provider for ${provCls.name}", t)
                break
            }

            try {
                val item = mapper(provInstance)
                if (item != null) {
                    register(item)
                    logger.info("Registered provider item: ${idFn(item)} from ${provInstance::class.qualifiedName}")
                } else {
                    logger.warn("Mapper returned null for provider ${provInstance::class.qualifiedName}")
                }
            } catch (t: Throwable) {
                logger.error("Provider ${provInstance::class.qualifiedName} failed mapping/registration", t)
            }
        }
    }

    /**
     * Can be used to load provider items from a supplier (DI / Koin).
     */
    fun registerProvidersFromSupplier(supplier: () -> Iterable<P>) {
        val mapper = providerToItemMapper ?: return
        val providers = try { supplier() } catch (_: Throwable) { emptyList() }
        for(p in providers) {
            try {
                val item = mapper(p)
                if(item != null) register(item)
            } catch (t: Throwable) {
                logger.error("Supplier-provided provider ${p::class.qualifiedName} failed", t)
            }
        }
    }

    /**
     * Clears any provider-registered items and reloads via [ServiceLoader].
     *
     * This simply just attempts discovery again, override if precise semantics are needed.
     */
    fun reloadProviders() {
        loadProviders()
    }
}
