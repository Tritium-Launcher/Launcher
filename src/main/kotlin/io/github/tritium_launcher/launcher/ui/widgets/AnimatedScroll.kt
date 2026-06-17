package io.github.tritium_launcher.launcher.ui.widgets

import io.github.tritium_launcher.launcher.connect
import io.github.tritium_launcher.launcher.core.TritiumEvent
import io.github.tritium_launcher.launcher.core.onEvent
import io.github.tritium_launcher.launcher.extension.core.CoreSettingKeys
import io.github.tritium_launcher.launcher.extension.core.CoreSettingValues
import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import io.qt.Nullable
import io.qt.core.*
import io.qt.gui.QWheelEvent
import io.qt.widgets.QAbstractScrollArea
import io.qt.widgets.QScrollBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.math.abs
import kotlin.math.exp

/**
 * Axis used by [AnimatedScrollController] when driving a scroll area.
 */
enum class AnimatedScrollAxis {
    Vertical,
    Horizontal
}

/**
 * Shared smooth-scrolling controller for Qt scroll-area widgets.
 *
 * The controller can optionally intercept wheel input directly, convert that
 * input into pixel deltas, and animate the target scrollbar using inertia for
 * free scrolling or a spring when moving toward an explicit target position.
 *
 * @param area Scroll area being controlled.
 * @param axis Scrollbar axis to drive.
 * @param interceptWheel Whether wheel events should be intercepted through an event filter.
 */
class AnimatedScrollController private constructor(
    private val area: QAbstractScrollArea,
    private val axis: AnimatedScrollAxis,
    private val interceptWheel: Boolean
) : QObject(area) {
    private var wheelAccumulator = 0
    private var scrollVelocity = 0.0
    private var scrollPos = 0.0
    private var targetPos: Double? = null
    private val timer = QTimer(this).also { it.interval = 8 }
    private val clock = QElapsedTimer()
    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        if (interceptWheel) {
            area.installEventFilter(this)
            area.viewport()?.installEventFilter(this)
        }
        timer.timeout.connect { tick() }
        scope.onEvent<TritiumEvent.SettingChanged> { event ->
            val key = "${event.namespace}:${event.nodeKey}"
            if (key == CoreSettingKeys.UiAnimateScrolling.toString()) {
                runOnGuiThread {
                    if (!CoreSettingValues.uiAnimateScrolling) stop()
                }
            }
        }
        area.destroyed.connect { scope.cancel() }
    }

    /**
     * Intercepts wheel events when enabled and translates them into animated scroll movement.
     */
    override fun eventFilter(watched: @Nullable QObject?, event: @Nullable QEvent?): Boolean {
        if (!interceptWheel || event?.type() != QEvent.Type.Wheel) {
            return super.eventFilter(watched, event)
        }

        val wheel = event as? QWheelEvent ?: return super.eventFilter(watched, event)
        if (wheel.modifiers().testFlag(Qt.KeyboardModifier.ControlModifier) ||
            wheel.modifiers().testFlag(Qt.KeyboardModifier.MetaModifier) ||
            wheel.modifiers().testFlag(Qt.KeyboardModifier.AltModifier)
        ) {
            return super.eventFilter(watched, event)
        }

        if (!canScroll()) {
            return super.eventFilter(watched, event)
        }

        val deltaPx = wheelDeltaPx(wheel)
        if (deltaPx == 0) {
            return super.eventFilter(watched, event)
        }

        nudgeBy(deltaPx)
        wheel.accept()
        return true
    }

    /**
     * Applies a wheel-style pixel delta to the controlled scrollbar.
     *
     * When animated scrolling is disabled, the scrollbar is moved immediately.
     *
     * @param deltaPx Signed pixel delta to apply.
     */
    fun nudgeBy(deltaPx: Int) {
        val bar = scrollBar() ?: return

        if (!CoreSettingValues.uiAnimateScrolling) {
            stop()
            val next = (bar.value.toDouble() + deltaPx).coerceIn(bar.minimum.toDouble(), bar.maximum.toDouble())
            bar.value = next.toInt()
            scrollPos = next
            return
        }

        if (!timer.isActive) {
            scrollPos = bar.value().toDouble()
            scrollVelocity = 0.0
            clock.start()
        }
        targetPos = null
        scrollVelocity += deltaPx * 35.0
        timer.start()
    }

    /**
     * Moves the controlled scrollbar to a specific position.
     *
     * @param target Target scrollbar value.
     * @param animate Whether movement should use the animated path when enabled globally.
     */
    fun scrollTo(target: Int, animate: Boolean = true) {
        val bar = scrollBar() ?: return
        val clamped = target.coerceIn(bar.minimum(), bar.maximum()).toDouble()

        if (!animate || !CoreSettingValues.uiAnimateScrolling) {
            stop()
            scrollPos = clamped
            bar.value = clamped.toInt()
            return
        }

        if (!timer.isActive) {
            scrollPos = bar.value().toDouble()
            scrollVelocity = 0.0
            clock.start()
        }

        targetPos = clamped
        timer.start()
    }

    /**
     * Stops any active scroll animation and clears pending target motion.
     */
    fun stop() {
        timer.stop()
        targetPos = null
        scrollVelocity = 0.0
    }

    /**
     * Advances one animation step and writes the updated position to the scrollbar.
     */
    private fun tick() {
        val bar = scrollBar() ?: return stop()

        val dt = (clock.restart().coerceAtLeast(1).toDouble() / 1000.0)

        val target = targetPos
        if (target != null) {
            val displacement = target - scrollPos
            val spring = 90.0
            val damping = 18.0

            val accel = (displacement * spring) - (scrollVelocity * damping)
            scrollVelocity += accel * dt
            scrollPos += scrollVelocity * dt

            if(abs(displacement) < 0.25 && abs(scrollVelocity) < 2.0) {
                scrollPos = target
                scrollVelocity = 0.0
                targetPos = null
            }
        } else {
            val drag = 12.0
            scrollPos += scrollVelocity * dt
            scrollVelocity *= exp(-drag * dt)

            if(abs(scrollVelocity) < 1.0) {
                scrollVelocity = 0.0
                timer.stop()
            }
        }

        val min = bar.minimum().toDouble()
        val max = bar.maximum().toDouble()
        scrollPos = scrollPos.coerceIn(min, max)

        val intPos = scrollPos.toInt().coerceIn(bar.minimum, bar.maximum)
        if(bar.value != intPos) bar.value = intPos
    }

    /**
     * Returns whether the controlled scrollbar has any scrollable range.
     */
    private fun canScroll(): Boolean {
        val bar = scrollBar() ?: return false
        return bar.maximum() > bar.minimum()
    }

    /**
     * Returns the scrollbar associated with the configured [axis].
     */
    private fun scrollBar(): QScrollBar? = when (axis) {
        AnimatedScrollAxis.Vertical -> area.verticalScrollBar()
        AnimatedScrollAxis.Horizontal -> area.horizontalScrollBar()
    }

    /**
     * Converts a wheel event into a pixel delta for the configured [axis].
     *
     * High-precision pixel deltas are used when available; otherwise angle deltas
     * are accumulated into step-sized pixel movement.
     */
    private fun wheelDeltaPx(event: QWheelEvent): Int {
        val pixel = event.pixelDelta()
        if (pixel.x() != 0 || pixel.y() != 0) {
            return when (axis) {
                AnimatedScrollAxis.Vertical -> if (pixel.y() != 0) pixel.y() else pixel.x()
                AnimatedScrollAxis.Horizontal -> if (pixel.x() != 0) pixel.x() else pixel.y()
            }
        }

        val angle = event.angleDelta()
        val angleDelta = when (axis) {
            AnimatedScrollAxis.Vertical -> if (angle.y() != 0) angle.y() else angle.x()
            AnimatedScrollAxis.Horizontal -> if (angle.x() != 0) angle.x() else angle.y()
        }

        wheelAccumulator += angleDelta
        val steps = wheelAccumulator / 120
        if (steps == 0) return 0
        wheelAccumulator -= steps * 120

        return -steps * STEP_SIZE_PX
    }

    companion object {
        private const val STEP_SIZE_PX = 40

        /**
         * Creates and attaches a controller for the given scroll area.
         *
         * @param area Scroll area to control.
         * @param axis Scrollbar axis to animate.
         * @param interceptWheel Whether wheel events should be intercepted automatically.
         */
        fun attach(
            area: QAbstractScrollArea,
            axis: AnimatedScrollAxis = AnimatedScrollAxis.Vertical,
            interceptWheel: Boolean = true
        ): AnimatedScrollController = AnimatedScrollController(area, axis, interceptWheel)
    }
}
