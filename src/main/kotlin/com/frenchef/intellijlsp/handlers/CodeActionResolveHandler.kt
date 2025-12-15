package com.frenchef.intellijlsp.handlers

import com.frenchef.intellijlsp.intellij.DocumentManager
import com.frenchef.intellijlsp.protocol.JsonRpcHandler
import com.frenchef.intellijlsp.protocol.LspGson
import com.frenchef.intellijlsp.protocol.models.*
import com.google.gson.JsonElement
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Handles codeAction/resolve requests.
 *
 * Lazily resolves the edit property of a CodeAction:
 * 1. Restores context (uri, offset, actionTitle) from the data field.
 * 2. Finds the corresponding IntentionAction/QuickFix.
 * 3. Applies it to a PSI file copy.
 * 4. Calculates the diff to generate a WorkspaceEdit.
 */
class CodeActionResolveHandler(
    private val project: Project,
    private val jsonRpcHandler: JsonRpcHandler,
    private val documentManager: DocumentManager
) {
    private val log = logger<CodeActionResolveHandler>()
    private val gson = LspGson.instance

    /** Registers the codeAction/resolve handler. */
    fun register() {
        jsonRpcHandler.registerRequestHandler("codeAction/resolve", this::handleResolve)
        log.info("CodeActionResolveHandler registered for codeAction/resolve")
    }

    /** Handles a codeAction/resolve request. */
    private fun handleResolve(params: JsonElement?): JsonElement? {
        if (params == null || params.isJsonNull) {
            log.debug("codeAction/resolve: null params received")
            return null
        }

        return try {
            val codeAction = gson.fromJson(params, CodeAction::class.java)
            val data = codeAction.data

            if (data == null) {
                log.warn("codeAction/resolve: No data field in CodeAction")
                return gson.toJsonTree(codeAction)
            }

            log.info("Resolving CodeAction: ${codeAction.title} for ${data.uri}")

            val resolvedAction = resolveCodeAction(codeAction, data)
            gson.toJsonTree(resolvedAction)
        } catch (e: Exception) {
            log.error("Error resolving code action", e)
            params // Return the original request.
        }
    }

    /** Resolves a CodeAction and calculates its edit. */
    private fun resolveCodeAction(codeAction: CodeAction, data: CodeActionData): CodeAction {
        val virtualFile = documentManager.getVirtualFile(data.uri)
        if (virtualFile == null) {
            log.warn("Virtual file not found: ${data.uri}")
            return codeAction
        }

        // 1. Get PSI and Document (ReadAction).
        val (psiFile, document) =
            ReadAction.compute<Pair<PsiFile, Document>?, RuntimeException> {
                val file = PsiManager.getInstance(project).findFile(virtualFile)
                if (file == null) {
                    log.warn("PSI file not found")
                    return@compute null
                }

                val doc = PsiDocumentManager.getInstance(project).getDocument(file)
                if (doc == null) {
                    log.warn("Document not found")
                    return@compute null
                }
                Pair(file, doc)
            }
                ?: return codeAction

        // 2. Find and apply the corresponding action based on actionType.
        // Note: resolveQuickFix and resolveIntention handle EDT/ReadContext internally,
        // so do not wrap this in ReadAction or it may deadlock (invokeAndWait from ReadAction).
        val edit =
            when (data.actionType) {
                "quickfix" -> resolveQuickFix(psiFile, document, data)
                "intention" -> resolveIntention(psiFile, document, data)
                else -> {
                    log.warn("Unknown action type: ${data.actionType}")
                    null
                }
            }

        return codeAction.copy(edit = edit)
    }

    /** Resolves a QuickFix CodeAction. */
    private fun resolveQuickFix(
        psiFile: PsiFile,
        document: Document,
        data: CodeActionData
    ): WorkspaceEdit? {
        try {
            var foundAction: IntentionAction? = null
            var result: WorkspaceEdit? = null

            val safeOffset = data.offset.coerceIn(0, document.textLength)

            // Expand the search range to include the start and end of the current line.
            val lineNumber = document.getLineNumber(safeOffset)
            val lineStart = document.getLineStartOffset(lineNumber)
            val lineEnd = document.getLineEndOffset(lineNumber)

            log.info("Searching QuickFix in range $lineStart-$lineEnd (line $lineNumber)")

            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                // Find the matching QuickFix in HighlightInfo.
                DaemonCodeAnalyzerEx.processHighlights(
                    document,
                    project,
                    null,
                    lineStart,
                    lineEnd
                ) { info: HighlightInfo ->
                    if (foundAction != null) return@processHighlights false

                    log.debug("Found HighlightInfo: ${info.description}, severity=${info.severity}")

                    info.findRegisteredQuickFix<Any?> { descriptor, _ ->
                        log.debug("  QuickFix: ${descriptor.action.text}")
                        if (descriptor.action.text == data.actionTitle) {
                            foundAction = descriptor.action
                            log.info("Found matching QuickFix: ${data.actionTitle}")
                        }
                        null
                    }
                    foundAction == null
                }

                val action = foundAction
                if (action != null) {
                    result = applyActionAndGetEdit(psiFile, document, action, data)
                }
            }

            if (foundAction == null) {
                log.warn("QuickFix not found: ${data.actionTitle}")
                return null
            }

            return result
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.error("Error resolving quickfix", e)
            return null
        }
    }

    /** Resolves an Intention CodeAction. */
    private fun resolveIntention(
        psiFile: PsiFile,
        document: Document,
        data: CodeActionData
    ): WorkspaceEdit? {
        try {
            var result: WorkspaceEdit? = null

            com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                var tempEditor: Editor? = null
                try {
                    val safeOffset = data.offset.coerceIn(0, document.textLength)
                    tempEditor = EditorFactory.getInstance().createEditor(document, project)
                    tempEditor.caretModel.moveToOffset(safeOffset)

                    val intentionManager = IntentionManager.getInstance()

                    // Find the matching intention.
                    val matchingIntention =
                        intentionManager.intentionActions.find { intention ->
                            try {
                                intention.isAvailable(project, tempEditor, psiFile) &&
                                        intention.text == data.actionTitle
                            } catch (_: Exception) {
                                false
                            }
                        }

                    if (matchingIntention == null) {
                        log.warn("Intention not found: ${data.actionTitle}")
                    } else {
                        // Apply the action on the EDT.
                        result = applyActionAndGetEdit(psiFile, document, matchingIntention, data)
                    }
                } finally {
                    if (tempEditor != null && !tempEditor.isDisposed) {
                        EditorFactory.getInstance().releaseEditor(tempEditor)
                    }
                }
            }

            return result
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.error("Error resolving intention", e)
            return null
        }
    }

    /**
     * Temporarily applies the action to the original file, calculates the edit, and immediately rolls back the text.
     *
     * Notes:
     * - Many IntelliJ/Kotlin Intentions and QuickFixes depend on real PSI/VirtualFile context;
     *   invoking them on a PsiFileFactory copy may return successfully without changing the document, leaving resolve without an edit.
     * - To reliably generate a WorkspaceEdit, apply the action to the original document, read the modified text, then restore it.
     */
    private fun applyActionAndGetEdit(
        originalPsiFile: PsiFile,
        originalDocument: Document,
        action: IntentionAction,
        data: CodeActionData
    ): WorkspaceEdit? {
        val originalText = originalDocument.text
        val safeOffset = data.offset.coerceIn(0, originalDocument.textLength)

        var tempEditor: Editor? = null
        try {
            tempEditor = EditorFactory.getInstance().createEditor(originalDocument, project)
            tempEditor.caretModel.moveToOffset(safeOffset)

            var modifiedText: String? = null

            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    PsiDocumentManager.getInstance(project).commitDocument(originalDocument)

                    action.invoke(project, tempEditor, originalPsiFile)
                    PsiDocumentManager.getInstance(project).commitDocument(originalDocument)

                    modifiedText = originalDocument.text
                } catch (e: Exception) {
                    log.warn("Failed to invoke action: ${e.message}", e)
                } finally {
                    try {
                        // Roll back so the actual file is not changed (LSP resolve only needs the edit).
                        originalDocument.setText(originalText)
                        PsiDocumentManager.getInstance(project).commitDocument(originalDocument)
                    } catch (e: Exception) {
                        log.warn("Failed to rollback document text: ${e.message}", e)
                    }
                }
            }

            val after = modifiedText ?: return null
            if (after == originalText) return null

            // For robustness, return a single TextEdit replacing the whole file (avoids client rejection due to diff errors).
            val textEdits = computeFullDocumentReplaceEdit(originalText, after)
            if (textEdits.isEmpty()) return null

            return WorkspaceEdit(changes = mapOf(data.uri to textEdits))
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.error("Error applying action and getting edit", e)
            return null
        } finally {
            if (tempEditor != null && !tempEditor.isDisposed) {
                EditorFactory.getInstance().releaseEditor(tempEditor)
            }
        }
    }

    private fun computeFullDocumentReplaceEdit(
        originalText: String,
        modifiedText: String
    ): List<TextEdit> {
        if (originalText == modifiedText) return emptyList()

        val lines = originalText.split("\n")
        val endLine = (lines.size - 1).coerceAtLeast(0)
        val endChar = if (lines.isEmpty()) 0 else lines.last().length

        return listOf(
            TextEdit(
                range =
                    Range(
                        start = Position(line = 0, character = 0),
                        end = Position(line = endLine, character = endChar)
                    ),
                newText = modifiedText
            )
        )
    }
}
