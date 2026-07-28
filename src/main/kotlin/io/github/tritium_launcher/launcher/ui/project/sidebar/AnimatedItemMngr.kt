/*
 * Copyright (c) 2025 FooterMan and contributors.
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.github.tritium_launcher.launcher.ui.project.sidebar

import io.github.tritium_launcher.api.connect
import io.github.tritium_launcher.launcher.DprMonitor
import io.qt.core.QRect
import io.qt.core.QTimer
import io.qt.core.Qt
import io.qt.gui.QColor
import io.qt.gui.QImage
import io.qt.gui.QPainter
import io.qt.gui.QPixmap
import kotlinx.serialization.json.*
import java.util.concurrent.CopyOnWriteArrayList

object AnimatedItemMngr {
    fun applyTint(pixmap: QPixmap, tintArgb: Long): QPixmap {
        val r = ((tintArgb shr 16) and 0xFF).toInt()
        val g = ((tintArgb shr 8) and 0xFF).toInt()
        val b = (tintArgb and 0xFF).toInt()

        val img = pixmap.toImage().convertToFormat(QImage.Format.Format_ARGB32)
        for (y in 0 until img.height()) {
            for (x in 0 until img.width()) {
                val px = img.pixelColor(x, y)
                img.setPixelColor(x, y, QColor(
                    px.red() * r / 255,
                    px.green() * g / 255,
                    px.blue() * b / 255,
                    px.alpha()
                ))
            }
        }
        return QPixmap.fromImage(img)
    }

    data class AnimationMeta(
        val frameWidth: Int,
        val frameHeight: Int,
        val frameSequence: List<Int>,
        val cumulativeTimesMs: List<Long>,
        val totalDurationMs: Long,
        val interpolate: Boolean
    )

    sealed class ItemTexture {
        data class Static(val pixmap: QPixmap) : ItemTexture()
        data class Animated(val sheet: QImage, val meta: AnimationMeta) : ItemTexture()
    }

    @Volatile
    var globalTickCount: Long = 0L
        private set

    private val frameCache = object : LinkedHashMap<String, QPixmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String?, QPixmap?>?): Boolean = size > 512
    }

    private val tickListeners = CopyOnWriteArrayList<() -> Unit>()
    private val animJson = Json { ignoreUnknownKeys = true }
    private var started = false
    private val timer = QTimer()
    var cachedDpr: Double = DprMonitor.current

    init {
        DprMonitor.onChange { newDpr ->
            cachedDpr = newDpr
            frameCache.clear()
        }
    }

    fun ensureStarted() {
        if (started) return
        started = true
        timer.interval = 50
        timer.timeout.connect {
            globalTickCount++
            tickListeners.toList().forEach { it() }
        }
        timer.start()
    }

    fun registerTickListener(listener: () -> Unit) {
        tickListeners.add(listener)
    }

    fun unregisterTickListener(listener: () -> Unit) {
        tickListeners.remove(listener)
    }

    fun parseAnimationMeta(jsonStr: String, sheetWidth: Int, sheetHeight: Int): AnimationMeta? {
        return try {
            val root = animJson.parseToJsonElement(jsonStr).jsonObject
            val anim = root["animation"]?.jsonObject ?: return null
            val frameTime = anim["frametime"]?.jsonPrimitive?.intOrNull ?: 1
            val width = anim["width"]?.jsonPrimitive?.intOrNull ?: sheetWidth
            val height = anim["height"]?.jsonPrimitive?.intOrNull ?: if (width > 0) sheetHeight.coerceAtMost(width) else sheetHeight
            val framesRaw = anim["frames"]?.jsonArray
            val interpolate = anim["interpolate"]?.jsonPrimitive?.booleanOrNull ?: false
            val frameCount = if (height > 0) sheetHeight / height else 1
            val frameSequence = mutableListOf<Int>()
            val frameDurations = mutableListOf<Long>()

            if (framesRaw != null) {
                for (entry in framesRaw) {
                    when (entry) {
                        is JsonPrimitive -> {
                            val idx = entry.int
                            frameSequence.add(idx)
                            frameDurations.add(frameTime * 50L)
                        }
                        is JsonObject -> {
                            val idx = entry["index"]?.jsonPrimitive?.int ?: 0
                            val time = entry["time"]?.jsonPrimitive?.intOrNull ?: frameTime
                            frameSequence.add(idx)
                            frameDurations.add(time * 50L)
                        }
                        else -> {}
                    }
                }
            }
            if (frameSequence.isEmpty()) {
                for (i in 0 until frameCount) {
                    frameSequence.add(i)
                    frameDurations.add(frameTime * 50L)
                }
            }

            val cumulative = mutableListOf<Long>()
            var acc = 0L
            for (d in frameDurations) {
                acc += d
                cumulative.add(acc)
            }
            val total = acc

            AnimationMeta(width, height, frameSequence, cumulative, total, interpolate)
        } catch (_: Exception) {
            null
        }
    }

    fun currentAnimatedPixmap(
        id: String,
        texture: ItemTexture.Animated,
        targetSize: Int
    ): QPixmap {
        val meta = texture.meta
        val elapsed = (globalTickCount * 50L) % meta.totalDurationMs
        val frameIdx = meta.cumulativeTimesMs.indexOfFirst { elapsed < it }.coerceAtLeast(0)
        val physicalSize = (targetSize * cachedDpr).toInt()

        val sheetKey = texture.sheet.cacheKey()
        if(!meta.interpolate) {
            val frameIndex = meta.frameSequence.getOrElse(frameIdx) { 0 }
            val cacheKey = "$id|$sheetKey|$frameIndex|$physicalSize|$cachedDpr"
            frameCache[cacheKey]?.let { return it }

            val srcRect = QRect(0, frameIndex * meta.frameHeight, meta.frameWidth, meta.frameHeight)
            val frameImg = texture.sheet.copy(srcRect)
            val scaled = if(meta.frameWidth <= 16 && meta.frameHeight <= 16) {
                QPixmap.fromImage(frameImg.scaled(physicalSize, physicalSize,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.FastTransformation
                ))
            } else {
                var img = frameImg
                while(img.width() / 2 >= physicalSize && img.height() / 2 >= physicalSize) {
                    img = img.scaled(img.width() / 2, img.height() / 2,
                        Qt.AspectRatioMode.KeepAspectRatio,
                        Qt.TransformationMode.SmoothTransformation
                    )
                }
                QPixmap.fromImage(img.scaled(physicalSize, physicalSize,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation
                ))
            }
            scaled.setDevicePixelRatio(cachedDpr)
            frameCache[cacheKey] = scaled
            return scaled
        } else {
            val frameStart = if(frameIdx == 0) 0L else meta.cumulativeTimesMs[frameIdx - 1]
            val frameDur   = meta.cumulativeTimesMs[frameIdx] - frameStart
            val t = (elapsed - frameStart).toFloat() / frameDur.toFloat()
            val tQuantized = (t * 8).toInt() / 8f

            val frameA = meta.frameSequence.getOrElse(frameIdx) { 0 }
            val frameB = meta.frameSequence.getOrElse((frameIdx + 1) % meta.frameSequence.size) { 0 }
            val cacheKey = "$id|$sheetKey|${frameA}_${frameB}|$tQuantized|$physicalSize|$cachedDpr"
            frameCache[cacheKey]?.let { return it }

            val srcA = QRect(0, frameA * meta.frameHeight, meta.frameWidth, meta.frameHeight)
            val srcB = QRect(0, frameB * meta.frameHeight, meta.frameWidth, meta.frameHeight)
            val imgA = texture.sheet.copy(srcA).scaled(physicalSize, physicalSize,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.FastTransformation)
            val imgB = texture.sheet.copy(srcB).scaled(physicalSize, physicalSize,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.FastTransformation)

            val result = QImage(physicalSize, physicalSize, QImage.Format.Format_ARGB32_Premultiplied)
            result.fill(0)
            val painter = QPainter(result)
            painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_Source)
            painter.drawImage(0,0,imgA)
            painter.setCompositionMode(QPainter.CompositionMode.CompositionMode_SourceOver)
            painter.setOpacity(tQuantized.toDouble())
            painter.drawImage(0,0,imgB)
            painter.end()

            val blended = QPixmap.fromImage(result)
            blended.setDevicePixelRatio(cachedDpr)
            frameCache[cacheKey] = blended
            return blended
        }
    }
}
