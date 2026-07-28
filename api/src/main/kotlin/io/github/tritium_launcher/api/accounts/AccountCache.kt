/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api.accounts

import io.github.tritium_launcher.api.TConstants
import io.github.tritium_launcher.api.fromTR
import io.github.tritium_launcher.api.logger
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

object AccountCache {
    private val log = logger()
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = fromTR(TConstants.Dirs.PROFILES, "accounts").also { it.mkdirs() }
    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, AccountDescriptor>>()

    init {
        try {
            cacheDir.listFiles(filter = { it.isDir() }).forEach { serviceDir ->
                val service = serviceDir.fileName()
                val serviceMap = ConcurrentHashMap<String, AccountDescriptor>()
                serviceDir.listFiles(filter = { it.isFile() && it.fileName().endsWith(".json") }).forEach { file ->
                    try {
                        val contents = file.readTextOrNull() ?: return@forEach
                        val descriptor = json.decodeFromString<AccountDescriptor>(contents)
                        serviceMap[file.fileName().removeSuffix(".json")] = descriptor
                    } catch (t: Throwable) {
                        log.warn("Failed to load cached account descriptor from {}", file.toAbsolute(), t)
                    }
                }
                if (serviceMap.isNotEmpty()) {
                    cache[service] = serviceMap
                }
            }
            log.info("AccountCache: loaded {} services from disk", cache.size)
        } catch (t: Throwable) {
            log.warn("Failed to pre-load account cache", t)
        }
    }

    fun getCached(service: String, accountId: String): AccountDescriptor? {
        return cache[service]?.get(accountId)
    }

    fun getAllCached(service: String): List<AccountDescriptor> {
        return cache[service]?.values?.toList() ?: emptyList()
    }

    fun save(service: String, accountId: String, descriptor: AccountDescriptor) {
        cache.computeIfAbsent(service) { ConcurrentHashMap() }[accountId] = descriptor
        val file = cacheDir.resolve(service).resolve("$accountId.json")
        try {
            file.parent().mkdirs()
            file.writeBytesAtomic(json.encodeToString<AccountDescriptor>(descriptor).toByteArray())
        } catch (t: Throwable) {
            log.warn("Failed to save cached account descriptor for {}/{}", service, accountId, t)
        }
    }

    fun remove(service: String, accountId: String) {
        cache[service]?.remove(accountId)
        val file = cacheDir.resolve(service).resolve("$accountId.json")
        try {
            if (file.exists()) file.delete()
        } catch (t: Throwable) {
            log.warn("Failed to delete cached account descriptor for {}/{}", service, accountId, t)
        }
    }
}
