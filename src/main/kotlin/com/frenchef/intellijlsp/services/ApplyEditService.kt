package com.frenchef.intellijlsp.services

import com.frenchef.intellijlsp.protocol.JsonRpcHandler
import com.frenchef.intellijlsp.protocol.models.ApplyWorkspaceEditParams
import com.frenchef.intellijlsp.protocol.models.ApplyWorkspaceEditResult
import com.frenchef.intellijlsp.protocol.models.ClientCapabilities
import com.frenchef.intellijlsp.protocol.models.WorkspaceEdit
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Service for server-initiated edits.
 *
 * Sends `workspace/applyEdit` requests to the client to apply a WorkspaceEdit.
 *
 * According to the LSP 3.17 specification:
 * - The server sends a request to the client.
 * - The client applies the edit and returns a result.
 * - If the client does not support applyEdit, no request is sent.
 */
class ApplyEditService(
    private val jsonRpcHandler: JsonRpcHandler,
    private val clientCapabilitiesProvider: () -> ClientCapabilities?
) {
    private val log = logger<ApplyEditService>()
    private val gson = Gson()

    companion object {
        /** workspace/applyEdit method name. */
        const val METHOD = "workspace/applyEdit"

        /** Default timeout in seconds. */
        const val DEFAULT_TIMEOUT_SECONDS = 30L
    }

    /** Checks whether the client supports workspace/applyEdit. */
    fun isSupported(): Boolean {
        val capabilities = clientCapabilitiesProvider()
        return capabilities?.workspace?.applyEdit == true
    }

    /**
     * Sends a workspace/applyEdit request to the client.
     *
     * @param edit WorkspaceEdit to apply.
     * @param label Optional edit description (shown in the UI).
     * @return ApplyWorkspaceEditResult, or null if the client does not support the request.
     */
    fun applyEdit(edit: WorkspaceEdit, label: String? = null): ApplyWorkspaceEditResult? {
        if (!isSupported()) {
            log.info("Client does not support workspace/applyEdit, skipping")
            return null
        }

        val params = ApplyWorkspaceEditParams(label = label, edit = edit)

        log.info(
            "Sending workspace/applyEdit request: label=$label, changes=${edit.changes?.size ?: 0} files"
        )

        return try {
            val future = jsonRpcHandler.sendRequest(METHOD, params)
            val result = future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            if (result != null) {
                val applyResult = gson.fromJson(result, ApplyWorkspaceEditResult::class.java)
                log.info(
                    "workspace/applyEdit result: applied=${applyResult.applied}, failureReason=${applyResult.failureReason}"
                )
                applyResult
            } else {
                log.warn("workspace/applyEdit returned null result")
                ApplyWorkspaceEditResult(applied = false, failureReason = "No result from client")
            }
        } catch (e: Exception) {
            log.error("workspace/applyEdit failed: ${e.message}", e)
            ApplyWorkspaceEditResult(applied = false, failureReason = e.message)
        }
    }

    /**
     * Asynchronously sends a workspace/applyEdit request.
     *
     * @param edit WorkspaceEdit to apply.
     * @param label Optional edit description.
     * @return CompletableFuture containing the result, or an already-completed null Future if unsupported.
     */
    fun applyEditAsync(
        edit: WorkspaceEdit,
        label: String? = null
    ): CompletableFuture<ApplyWorkspaceEditResult?> {
        if (!isSupported()) {
            log.info("Client does not support workspace/applyEdit, skipping")
            return CompletableFuture.completedFuture(null)
        }

        val params = ApplyWorkspaceEditParams(label = label, edit = edit)

        log.info("Sending async workspace/applyEdit request: label=$label")

        return jsonRpcHandler
            .sendRequest(METHOD, params)
            .thenApply { result ->
                if (result != null) {
                    val applyResult =
                        gson.fromJson(result, ApplyWorkspaceEditResult::class.java)
                    log.info("workspace/applyEdit result: applied=${applyResult.applied}")
                    applyResult
                } else {
                    log.warn("workspace/applyEdit returned null result")
                    ApplyWorkspaceEditResult(
                        applied = false,
                        failureReason = "No result from client"
                    )
                }
            }
            .exceptionally { e ->
                log.error("workspace/applyEdit failed: ${e.message}", e)
                ApplyWorkspaceEditResult(applied = false, failureReason = e.message)
            }
    }
}
