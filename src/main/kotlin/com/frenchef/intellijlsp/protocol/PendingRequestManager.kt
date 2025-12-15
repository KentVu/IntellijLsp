package com.frenchef.intellijlsp.protocol

import com.frenchef.intellijlsp.protocol.models.ErrorCodes
import com.google.gson.JsonElement
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages pending requests for bidirectional communication.
 *
 * Responsibilities:
 * - Generate unique request IDs.
 * - Maintain the pending request map.
 * - Handle request timeouts (30 seconds).
 */
class PendingRequestManager {
    private val log = logger<PendingRequestManager>()

    /** Request ID generator. */
    private val requestIdCounter = AtomicLong(1)

    /** Pending request map: ID -> CompletableFuture. */
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()

    /** Default timeout in milliseconds. */
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    /** Generates the next unique request ID. */
    fun nextRequestId(): String {
        return "server-${requestIdCounter.getAndIncrement()}"
    }

    /**
     * Registers a pending request and returns its Future.
     *
     * @param id Request ID.
     * @param method Request method name (used for logging).
     * @return CompletableFuture used to wait for the response.
     */
    fun registerRequest(id: String, method: String): CompletableFuture<JsonElement?> {
        val future = CompletableFuture<JsonElement?>()
        val pendingRequest =
            PendingRequest(
                id = id,
                method = method,
                future = future,
                createdAt = System.currentTimeMillis()
            )

        pendingRequests[id] = pendingRequest
        log.info("Registered pending request: id=$id, method=$method")

        // Set the timeout.
        scheduleTimeout(id, DEFAULT_TIMEOUT_MS)

        return future
    }

    /**
     * Completes a pending request successfully.
     *
     * @param id Request ID.
     * @param result Response result.
     * @return true if the request existed and was completed.
     */
    fun completeRequest(id: String, result: JsonElement?): Boolean {
        val pending = pendingRequests.remove(id)
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.createdAt
            log.info(
                "Completed pending request: id=$id, method=${pending.method}, elapsed=${elapsed}ms"
            )
            pending.future.complete(result)
            return true
        }
        log.warn("No pending request found for id=$id")
        return false
    }

    /**
     * Completes a pending request with a failure.
     *
     * @param id Request ID.
     * @param error Error information.
     * @return true if the request existed and was completed.
     */
    fun failRequest(id: String, error: Throwable): Boolean {
        val pending = pendingRequests.remove(id)
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pending.createdAt
            log.warn(
                "Failed pending request: id=$id, method=${pending.method}, elapsed=${elapsed}ms, error=${error.message}"
            )
            pending.future.completeExceptionally(error)
            return true
        }
        log.warn("No pending request found for id=$id")
        return false
    }

    /**
     * Cancels a pending request.
     *
     * @param id Request ID.
     * @param reason Cancellation reason.
     * @return true if the request existed and was cancelled.
     */
    fun cancelRequest(id: String, reason: String = "Request cancelled"): Boolean {
        val pending = pendingRequests.remove(id)
        if (pending != null) {
            log.info("Cancelled pending request: id=$id, method=${pending.method}, reason=$reason")
            pending.future.cancel(false)
            return true
        }
        return false
    }

    /** Gets the current number of pending requests. */
    fun pendingCount(): Int = pendingRequests.size

    /** Cancels all pending requests (used during shutdown). */
    fun cancelAll(reason: String = "Server shutdown") {
        val count = pendingRequests.size
        pendingRequests.keys.toList().forEach { id -> cancelRequest(id, reason) }
        if (count > 0) {
            log.info("Cancelled $count pending requests: reason=$reason")
        }
    }

    /** Sets the request timeout. */
    private fun scheduleTimeout(id: String, timeoutMs: Long) {
        CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS).execute {
            val pending = pendingRequests.remove(id)
            if (pending != null) {
                log.warn(
                    "Request timeout: id=$id, method=${pending.method}, timeout=${timeoutMs}ms"
                )
                pending.future.completeExceptionally(
                    LspException(
                        code = ErrorCodes.REQUEST_CANCELLED,
                        message = "Request timeout after ${timeoutMs}ms"
                    )
                )
            }
        }
    }

    /** Internal class representing a pending request. */
    private data class PendingRequest(
        val id: String,
        val method: String,
        val future: CompletableFuture<JsonElement?>,
        val createdAt: Long
    )
}
