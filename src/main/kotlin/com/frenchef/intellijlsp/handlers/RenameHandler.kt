package com.frenchef.intellijlsp.handlers

import com.frenchef.intellijlsp.intellij.DocumentManager
import com.frenchef.intellijlsp.intellij.RenameProvider
import com.frenchef.intellijlsp.intellij.RenameResult
import com.frenchef.intellijlsp.protocol.JsonRpcHandler
import com.frenchef.intellijlsp.protocol.LspGson
import com.frenchef.intellijlsp.protocol.models.PrepareRenameParams
import com.frenchef.intellijlsp.protocol.models.RenameParams
import com.google.gson.JsonElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

/**
 * Phase 10: Rename Handler (T011)
 *
 * Handles LSP textDocument/prepareRename and textDocument/rename requests.
 */
class RenameHandler(
    private val project: Project,
    private val jsonRpcHandler: JsonRpcHandler,
    private val documentManager: DocumentManager
) {
    private val log = logger<RenameHandler>()
    private val gson = LspGson.instance
    private val renameProvider = RenameProvider(project)

    /** Registers Rename handlers. */
    fun register() {
        jsonRpcHandler.registerRequestHandler(
            "textDocument/prepareRename",
            this::handlePrepareRename
        )
        jsonRpcHandler.registerRequestHandler("textDocument/rename", this::handleRename)
        log.info("RenameHandler registered for textDocument/prepareRename and textDocument/rename")
    }

    /**
     * Handles a textDocument/prepareRename request.
     *
     * Checks whether a symbol can be renamed and returns the rename range and placeholder name.
     */
    private fun handlePrepareRename(params: JsonElement?): JsonElement? {
        if (params == null || params.isJsonNull) {
            log.debug("prepareRename: null params")
            return null
        }

        try {
            val prepareParams = gson.fromJson(params, PrepareRenameParams::class.java)
            val uri = prepareParams.textDocument.uri
            val position = prepareParams.position

            log.debug(
                "prepareRename requested for $uri at line ${position.line}, char ${position.character}"
            )

            val virtualFile =
                documentManager.getVirtualFile(uri)
                    ?: run {
                        log.debug("prepareRename: file not found: $uri")
                        return null
                    }

            val psiFile =
                ReadAction.compute<com.intellij.psi.PsiFile?, RuntimeException> {
                    PsiManager.getInstance(project).findFile(virtualFile)
                }
                    ?: run {
                        log.debug("prepareRename: could not get PsiFile for $uri")
                        return null
                    }

            val result = renameProvider.prepareRename(psiFile, position)
            if (result == null) {
                log.debug("prepareRename: element is not renameable")
                return null
            }

            log.debug("prepareRename: returning placeholder '${result.placeholder}'")
            return gson.toJsonTree(result)
        } catch (e: Exception) {
            log.error("Error handling prepareRename", e)
            return null
        }
    }

    /**
     * Handles a textDocument/rename request.
     *
     * Performs the rename and returns a WorkspaceEdit containing all changes.
     */
    private fun handleRename(params: JsonElement?): JsonElement? {
        if (params == null || params.isJsonNull) {
            log.debug("rename: null params")
            return null
        }

        try {
            val renameParams = gson.fromJson(params, RenameParams::class.java)
            val uri = renameParams.textDocument.uri
            val position = renameParams.position
            val newName = renameParams.newName

            log.info("rename requested for $uri at line ${position.line}: newName='$newName'")

            val virtualFile =
                documentManager.getVirtualFile(uri)
                    ?: run {
                        log.debug("rename: file not found: $uri")
                        return null
                    }

            val psiFile =
                ReadAction.compute<com.intellij.psi.PsiFile?, RuntimeException> {
                    PsiManager.getInstance(project).findFile(virtualFile)
                }
                    ?: run {
                        log.debug("rename: could not get PsiFile for $uri")
                        return null
                    }

            return when (val result = renameProvider.rename(psiFile, position, newName)) {
                is RenameResult.Success -> {
                    log.info("rename: success, ${result.edit.changes?.size ?: 0} files affected")
                    gson.toJsonTree(result.edit)
                }

                is RenameResult.Error -> {
                    log.warn("rename: error - ${result.message}")
                    // LSP allows null to indicate that the symbol cannot be renamed.
                    null
                }

                null -> {
                    log.debug("rename: returned null")
                    null
                }
            }
        } catch (e: Exception) {
            log.error("Error handling rename", e)
            return null
        }
    }
}
