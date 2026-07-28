/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.editor.inspection

import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Query
import java.util.*

object QueryCache {
    private val cache: MutableMap<String, Query> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Query>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Query>): Boolean = size > 256
        }
    )

    fun getOrCompile(language: Language, sExpression: String): Query {
        val key = "${language.hashCode()}:$sExpression"
        return cache.getOrPut(key) { Query(language, sExpression) }
    }

    fun clear() {
        cache.clear()
    }
}
