package io.github.tritium_launcher.launcher.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-wide event bus for decoupled communication between components.
 */
object TritiumEventBus {
    private val _events = MutableSharedFlow<TritiumEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun publish(event: TritiumEvent) {
        _events.tryEmit(event)
    }
}

sealed interface TritiumEvent {
    /**
     * Request to focus the Registry Browser on a specific ID.
     */
    data class RegistryFocusRequest(val id: String) : TritiumEvent
}
