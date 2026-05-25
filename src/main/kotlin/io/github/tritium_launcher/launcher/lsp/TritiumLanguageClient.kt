package io.github.tritium_launcher.launcher.lsp

import io.github.tritium_launcher.launcher.logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture

/**
 * LSP client implementation that forwards diagnostics to the UI event bus
 * and logs server messages.
 */
class TritiumLanguageClient : LanguageClient {
    val logger = logger()

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        LSPEventBus.publishDiagnostics(diagnostics)
    }

    override fun telemetryEvent(`object`: Any?) {
        `object`?.let { logger.debug("LSP: {}", it) }
    }

    override fun showMessage(messageParams: MessageParams?) {
        messageParams?.let {
            val msg = "[${it.type}] LSP: ${it.message}"
            when(it.type) {
                MessageType.Error   -> logger.error(msg)
                MessageType.Warning -> logger.warn(msg)
                else -> logger.info(msg)
            }
        }
    }
    override fun showMessageRequest(requestParams: ShowMessageRequestParams?): CompletableFuture<MessageActionItem?>? {
        logger.info("LSP Message Request: {}", requestParams?.message)
        return CompletableFuture.completedFuture(requestParams?.actions?.firstOrNull())
    }

    override fun logMessage(message: MessageParams?) {
        message?.let {
            logger.debug("[Server Log] {}", it.message)
        }
    }

    override fun configuration(configurationParams: ConfigurationParams): CompletableFuture<List<Any>> {
        return CompletableFuture.completedFuture(emptyList())
    }
}

/**
 * Thread-safe diagnostics event bus using Kotlin Flows.
 */
internal object LSPEventBus {
    private val _diagnostics = MutableSharedFlow<PublishDiagnosticsParams>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Flow of diagnostics published by the LSP server.
     */
    val diagnostics = _diagnostics.asSharedFlow()

    /**
     * Publishes diagnostics to the flow.
     */
    fun publishDiagnostics(p: PublishDiagnosticsParams) {
        _diagnostics.tryEmit(p)
    }
}
