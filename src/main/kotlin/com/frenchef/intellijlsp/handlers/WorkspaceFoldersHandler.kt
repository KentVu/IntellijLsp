package com.frenchef.intellijlsp.handlers

import com.frenchef.intellijlsp.protocol.JsonRpcHandler
import com.frenchef.intellijlsp.protocol.LspGson
import com.frenchef.intellijlsp.protocol.models.DidChangeWorkspaceFoldersParams
import com.frenchef.intellijlsp.protocol.models.WorkspaceFolder
import com.google.gson.JsonElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

/**
 * Phase 10: Workspace Folders Handler (T028)
 *
 * Handles LSP workspace/didChangeWorkspaceFolders notifications and maintains the workspace folder list.
 */
class WorkspaceFoldersHandler(
    private val project: Project,
    private val jsonRpcHandler: JsonRpcHandler
) {
    private val log = logger<WorkspaceFoldersHandler>()
    private val gson = LspGson.instance

    /** Current list of workspace folders. */
    private val workspaceFolders = mutableListOf<WorkspaceFolder>()

    /** Registers the Workspace Folders handler. */
    fun register() {
        jsonRpcHandler.registerNotificationHandler(
            "workspace/didChangeWorkspaceFolders",
            this::handleDidChangeWorkspaceFolders
        )
        log.info("WorkspaceFoldersHandler registered")
    }

    /** Initializes the workspace folder list from the initialize request. */
    fun initializeWorkspaceFolders(folders: List<WorkspaceFolder>?) {
        workspaceFolders.clear()
        folders?.let {
            workspaceFolders.addAll(it)
            log.info("Initialized ${it.size} workspace folders")
        }
    }

    /** Handles a workspace/didChangeWorkspaceFolders notification. */
    private fun handleDidChangeWorkspaceFolders(params: JsonElement?) {
        if (params == null || params.isJsonNull) {
            log.debug("didChangeWorkspaceFolders: null params")
            return
        }

        try {
            val changeParams = gson.fromJson(params, DidChangeWorkspaceFoldersParams::class.java)
            val event = changeParams.event

            // Remove folders.
            for (removed in event.removed) {
                val wasRemoved = workspaceFolders.removeIf { it.uri == removed.uri }
                if (wasRemoved) {
                    log.info("Removed workspace folder: ${removed.name} (${removed.uri})")
                }
            }

            // Add folders.
            for (added in event.added) {
                // Avoid adding duplicates.
                if (workspaceFolders.none { it.uri == added.uri }) {
                    workspaceFolders.add(added)
                    log.info("Added workspace folder: ${added.name} (${added.uri})")
                }
            }

            log.info("Current workspace folders: ${workspaceFolders.size}")
        } catch (e: Exception) {
            log.error("Error handling didChangeWorkspaceFolders", e)
        }
    }

    /** Gets the current list of workspace folders. */
    fun getWorkspaceFolders(): List<WorkspaceFolder> = workspaceFolders.toList()
}
