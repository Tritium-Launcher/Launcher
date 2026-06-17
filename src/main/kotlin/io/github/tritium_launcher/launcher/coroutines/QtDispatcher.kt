package io.github.tritium_launcher.launcher.coroutines

import io.qt.core.QCoreApplication
import io.qt.core.QMetaObject
import io.qt.core.QThread
import io.qt.core.Qt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * A Coroutine Dispatcher that schedules tasks on the Qt Event Loop.
 * Provides integration with [Dispatchers.Main] via [QtMainDispatcherFactory].
 */
internal class QtDispatcher : MainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher = Immediate

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val app = QCoreApplication.instance()
        if (app == null) {
            block.run()
        } else {
            val appThread = app.thread()
            if (appThread != null && appThread == QThread.currentThread()) {
                block.run()
            } else {
                QMetaObject.invokeMethod(
                    app,
                    QMetaObject.Slot0 { block.run() },
                    Qt.ConnectionType.QueuedConnection
                )
            }
        }
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        val app = QCoreApplication.instance() ?: return false
        val appThread = app.thread() ?: return false
        return appThread != QThread.currentThread()
    }

    private object Immediate : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher get() = this

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            (QCoreApplication.instance()?.thread() ?: return false) != QThread.currentThread()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val app = QCoreApplication.instance()
            val appThread = app?.thread()
            if (app == null || appThread == null || appThread == QThread.currentThread()) {
                block.run()
            } else {
                QMetaObject.invokeMethod(
                    app,
                    QMetaObject.Slot0 { block.run() },
                    Qt.ConnectionType.QueuedConnection
                )
            }
        }
    }
}
