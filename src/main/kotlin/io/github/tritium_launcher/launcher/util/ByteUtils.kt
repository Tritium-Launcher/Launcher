package io.github.tritium_launcher.launcher.util

import java.nio.ByteBuffer

object ByteUtils {
    fun toByteArray(buf: ByteBuffer?): ByteArray? {
        if(buf == null) return null
        val copy = ByteArray(buf.remaining())
        val dup = buf.duplicate()
        dup.get(copy)
        return copy
    }
}