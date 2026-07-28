/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.api

import java.nio.ByteBuffer

/**
 * General Byte utilities
 */
object ByteUtils {
    fun toByteArray(buf: ByteBuffer?): ByteArray? {
        if(buf == null) return null
        val copy = ByteArray(buf.remaining())
        val dup = buf.duplicate()
        dup.get(copy)
        return copy
    }
}
