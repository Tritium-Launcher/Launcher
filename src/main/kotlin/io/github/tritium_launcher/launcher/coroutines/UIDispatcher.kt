package io.github.tritium_launcher.launcher.coroutines

import io.github.tritium_launcher.launcher.ui.helpers.runOnGuiThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

object UIDispatcher : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        runOnGuiThread { block.run() }
    }


}