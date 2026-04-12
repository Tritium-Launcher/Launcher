package io.github.tritium_launcher.launcher.lsp

import io.github.tritium_launcher.launcher.logger
import io.github.tritium_launcher.launcher.lsp.LSPEventBus.publishDiagnostics
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
}

/**
 * Thread-safe diagnostics event bus.
 *
 * LSP4J calls [publishDiagnostics] on background threads, so this must be safe
 * for concurrent subscribe/unsubscribe/publish.
 */
internal object LSPEventBus {
    private val listeners = java.util.concurrent.ConcurrentHashMap<Int, (PublishDiagnosticsParams) -> Unit>()
    private val nextId = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Registers a diagnostics listener and returns its subscription id.
     */
    fun subscribe(l: (PublishDiagnosticsParams) -> Unit): Int {
        val id = nextId.getAndIncrement()
        listeners[id] = l
        return id
    }

    /**
     * Removes a previously registered listener.
     */
    fun unsubscribe(id: Int) {
        listeners.remove(id)
    }

    /**
     * Publishes diagnostics to all listeners.
     */
    fun publishDiagnostics(p: PublishDiagnosticsParams) {
        listeners.values.toList().forEach { it(p) }
    }
}
