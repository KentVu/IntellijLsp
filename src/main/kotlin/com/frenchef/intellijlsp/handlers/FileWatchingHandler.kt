package com.frenchef.intellijlsp.handlers

import com.frenchef.intellijlsp.protocol.JsonRpcHandler
import com.frenchef.intellijlsp.protocol.models.DidChangeWatchedFilesParams
import com.frenchef.intellijlsp.protocol.models.FileChangeType
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Phase 10: File Watching Handler (T031)
 *
 * Handles LSP workspace/didChangeWatchedFiles notifications and acts according to file event types.
 */
class FileWatchingHandler(
    private val project: Project,
    private val jsonRpcHandler: JsonRpcHandler
) {
    private val log = logger<FileWatchingHandler>()
    private val gson = Gson()

    /** Registers the File Watching handler. */
    fun register() {
        jsonRpcHandler.registerNotificationHandler(
            "workspace/didChangeWatchedFiles",
            this::handleDidChangeWatchedFiles
        )
        log.info("FileWatchingHandler registered")
    }

    /** Handles a workspace/didChangeWatchedFiles notification. */
    private fun handleDidChangeWatchedFiles(params: JsonElement?) {
        if (params == null || params.isJsonNull) {
            log.debug("didChangeWatchedFiles: null params")
            return
        }

        try {
            val changeParams = gson.fromJson(params, DidChangeWatchedFilesParams::class.java)

            for (event in changeParams.changes) {
                when (event.type) {
                    FileChangeType.CREATED -> handleFileCreated(event.uri)
                    FileChangeType.CHANGED -> handleFileChanged(event.uri)
                    FileChangeType.DELETED -> handleFileDeleted(event.uri)
                    else -> log.warn("Unknown file change type: ${event.type}")
                }
            }
        } catch (e: Exception) {
            log.error("Error handling didChangeWatchedFiles", e)
        }
    }

    /** Handles a file creation event. */
    private fun handleFileCreated(uri: String) {
        log.debug("File created: $uri")
        refreshVirtualFile(uri)
    }

    /** Handles a file modification event. */
    private fun handleFileChanged(uri: String) {
        log.debug("File changed: $uri")
        refreshVirtualFile(uri)
        // TODO: Trigger a diagnostics refresh if needed.
    }

    /** Handles a file deletion event. */
    private fun handleFileDeleted(uri: String) {
        log.debug("File deleted: $uri")
        refreshVirtualFile(uri)
        // TODO: Clean up related state if needed.
    }

    /** Refreshes the VirtualFile state. */
    private fun refreshVirtualFile(uri: String) {
        try {
            val normalizedUri = uri.replace("file:///", "file://")
            val virtualFile = VirtualFileManager.getInstance().findFileByUrl(normalizedUri)

            if (virtualFile != null) {
                virtualFile.refresh(true, false)
                log.debug("Refreshed VirtualFile: ${virtualFile.path}")
            } else {
                // The file may have just been created; refresh its parent directory.
                val parentPath = normalizedUri.substringBeforeLast("/")
                val parentFile = VirtualFileManager.getInstance().findFileByUrl(parentPath)
                parentFile?.refresh(true, true)
                log.debug("Refreshed parent directory for: $uri")
            }
        } catch (e: Exception) {
            log.warn("Error refreshing VirtualFile: $uri", e)
        }
    }
}
