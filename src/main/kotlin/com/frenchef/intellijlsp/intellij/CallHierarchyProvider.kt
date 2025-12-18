package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.language.FunctionInfo
import com.frenchef.intellijlsp.language.LanguageHandlerRegistry
import com.frenchef.intellijlsp.protocol.models.*
import com.frenchef.intellijlsp.util.LspLogger
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch

/**
 * Phase 10: Call Hierarchy Provider (T015)
 *
 * IntelliJ integration for LSP Call Hierarchy.
 * Supports multiple languages through the LanguageHandler abstraction.
 */
class CallHierarchyProvider(private val project: Project) {
    private val log = logger<CallHierarchyProvider>()

    private companion object {
        const val TAG = "CallHierarchy"
        const val MAX_RESULTS = 100
    }

    /** Prepares the call hierarchy, returning the method at the position as a CallHierarchyItem. */
    fun prepareCallHierarchy(file: PsiFile, position: Position): List<CallHierarchyItem>? =
        ReadAction.compute<List<CallHierarchyItem>?, RuntimeException> {
            try {
                LspLogger.debug(
                    TAG,
                    "prepareCallHierarchy: file=${file.name}, line=${position.line}, char=${position.character}"
                )

                val document = PsiDocumentManager.getInstance(project).getDocument(file)
                if (document == null) {
                    LspLogger.warn(TAG, "prepareCallHierarchy: document is null for ${file.name}")
                    return@compute null
                }

                val offset = document.getLineStartOffset(position.line) + position.character
                LspLogger.debug(TAG, "prepareCallHierarchy: offset=$offset, docLength=${document.textLength}")

                val element = file.findElementAt(offset)
                if (element == null) {
                    LspLogger.warn(TAG, "prepareCallHierarchy: element is null at offset $offset")
                    return@compute null
                }
                LspLogger.debug(
                    TAG,
                    "prepareCallHierarchy: element=${element.javaClass.simpleName}, text='${element.text.take(30)}'"
                )

                // Use LanguageHandler to find the method.
                val handler = LanguageHandlerRegistry.getHandler(file)
                val functionInfo = handler.findContainingFunction(element)
                if (functionInfo == null) {
                    LspLogger.warn(
                        TAG,
                        "prepareCallHierarchy: function not found for element ${element.javaClass.simpleName}"
                    )
                    return@compute null
                }
                LspLogger.debug(
                    TAG,
                    "prepareCallHierarchy: function=${functionInfo.name}, class=${functionInfo.containingClass?.name}"
                )

                val item = functionInfoToItem(functionInfo, document)
                if (item == null) {
                    LspLogger.warn(TAG, "prepareCallHierarchy: functionInfoToItem returned null")
                    return@compute null
                }
                LspLogger.info(TAG, "prepareCallHierarchy: found function '${item.name}'")
                listOf(item)
            } catch (e: Exception) {
                LspLogger.error(TAG, "Error in prepareCallHierarchy: ${e.message}")
                log.warn("Error in prepareCallHierarchy", e)
                null
            }
        }

    /** Gets incoming calls (callers of this method). */
    fun getIncomingCalls(item: CallHierarchyItem): List<CallHierarchyIncomingCall> =
        ReadAction.compute<List<CallHierarchyIncomingCall>, RuntimeException> {
            try {
                val functionInfo = findFunctionByItem(item) ?: return@compute emptyList()
                val results = mutableListOf<CallHierarchyIncomingCall>()
                val visited = mutableSetOf<String>()

                // Use ReferencesSearch (generic PSI API).
                val scope = GlobalSearchScope.projectScope(project)
                val references = ReferencesSearch.search(functionInfo.psiElement, scope).findAll()

                log.debug("getIncomingCalls: found ${references.size} references for function ${functionInfo.name}")

                for (reference in references) {
                    if (results.size >= MAX_RESULTS) break

                    val refElement = reference.element
                    val refFile = refElement.containingFile ?: continue

                    // Use the handler for the file containing the reference, not the referenced target.
                    val handler = LanguageHandlerRegistry.getHandler(refFile)

                    // Use LanguageHandler to find the method containing the element.
                    val callerFunction = handler.findContainingFunction(refElement)

                    if (callerFunction != null && callerFunction.psiElement != functionInfo.psiElement) {
                        // Prevent duplicates.
                        val key = "${callerFunction.containingClass?.qualifiedName}.${callerFunction.name}"
                        if (visited.contains(key)) continue
                        visited.add(key)

                        val callerDoc = callerFunction.psiElement.containingFile?.let {
                            PsiDocumentManager.getInstance(project).getDocument(it)
                        } ?: continue

                        val callerItem = functionInfoToItem(callerFunction, callerDoc) ?: continue
                        val refRange = getElementRange(refElement, callerDoc) ?: continue

                        results.add(
                            CallHierarchyIncomingCall(
                                from = callerItem,
                                fromRanges = listOf(refRange)
                            )
                        )
                    }
                }

                log.debug("getIncomingCalls: found ${results.size} callers")
                results
            } catch (e: Exception) {
                log.warn("Error in getIncomingCalls", e)
                emptyList()
            }
        }

    /** Gets outgoing calls (methods called by this method). */
    fun getOutgoingCalls(item: CallHierarchyItem): List<CallHierarchyOutgoingCall> =
        ReadAction.compute<List<CallHierarchyOutgoingCall>, RuntimeException> {
            try {
                val functionInfo = findFunctionByItem(item) ?: return@compute emptyList()
                val results = mutableListOf<CallHierarchyOutgoingCall>()
                val visited = mutableSetOf<String>()

                val file = functionInfo.psiElement.containingFile ?: return@compute emptyList()
                val document = PsiDocumentManager.getInstance(project).getDocument(file)
                    ?: return@compute emptyList()

                // Use LanguageHandler to get all call expressions.
                val handler = LanguageHandlerRegistry.getHandler(file)
                val callExpressions = handler.getCallExpressions(functionInfo)

                log.debug("getOutgoingCalls: found ${callExpressions.size} call expressions in ${functionInfo.name}")

                for (call in callExpressions) {
                    if (results.size >= MAX_RESULTS) break

                    val target = call.resolvedTarget ?: continue

                    // Prevent duplicates.
                    val key = "${target.containingClass?.qualifiedName}.${target.name}"
                    if (visited.contains(key)) continue
                    visited.add(key)

                    val calleeDoc = target.psiElement.containingFile?.let {
                        PsiDocumentManager.getInstance(project).getDocument(it)
                    }

                    if (calleeDoc != null) {
                        val calleeItem = functionInfoToItem(target, calleeDoc)
                        val callRange = getElementRange(call.psiElement, document)

                        if (calleeItem != null && callRange != null) {
                            results.add(
                                CallHierarchyOutgoingCall(
                                    to = calleeItem,
                                    fromRanges = listOf(callRange)
                                )
                            )
                        }
                    }
                }

                log.debug("getOutgoingCalls: found ${results.size} callees")
                results
            } catch (e: Exception) {
                log.warn("Error in getOutgoingCalls", e)
                emptyList()
            }
        }

    /** Finds a function from a CallHierarchyItem. */
    private fun findFunctionByItem(item: CallHierarchyItem): FunctionInfo? {
        LspLogger.debug(TAG, "findFunctionByItem: uri=${item.uri}, name=${item.name}")

        // Use the shared URI parser to handle differences in case and encoding.
        val resolved = LspDecompiledUriResolver.resolveForRequest(project, item.uri)
        if (resolved == null) {
            LspLogger.warn(TAG, "findFunctionByItem: failed to parse URI ${item.uri}")
            return null
        }

        // Calculate the offset from fileText to match the client's Position.
        val offset = PsiMapper.positionToOffset(resolved.fileText, item.selectionRange.start)
        LspLogger.debug(TAG, "findFunctionByItem: offset=$offset, line=${item.selectionRange.start.line}")

        val element = resolved.analysisPsiFile.findElementAt(offset)
        if (element == null) {
            LspLogger.warn(TAG, "findFunctionByItem: element is null at offset $offset")
            return null
        }

        val handler = LanguageHandlerRegistry.getHandler(resolved.analysisPsiFile)
        val functionInfo = handler.findContainingFunction(element)
        if (functionInfo == null) {
            LspLogger.warn(TAG, "findFunctionByItem: function not found")
        } else {
            LspLogger.debug(TAG, "findFunctionByItem: found function ${functionInfo.name}")
        }
        return functionInfo
    }

    /** Converts FunctionInfo to a CallHierarchyItem. */
    private fun functionInfoToItem(
        functionInfo: FunctionInfo,
        document: com.intellij.openapi.editor.Document
    ): CallHierarchyItem? {
        val file = functionInfo.psiElement.containingFile?.virtualFile ?: return null
        val textRange = functionInfo.psiElement.textRange ?: return null
        val nameIdentifier = functionInfo.nameIdentifier ?: return null
        val nameRange = nameIdentifier.textRange ?: return null

        val startLine = document.getLineNumber(textRange.startOffset)
        val startChar = textRange.startOffset - document.getLineStartOffset(startLine)
        val endLine = document.getLineNumber(textRange.endOffset)
        val endChar = textRange.endOffset - document.getLineStartOffset(endLine)

        val selStartLine = document.getLineNumber(nameRange.startOffset)
        val selStartChar = nameRange.startOffset - document.getLineStartOffset(selStartLine)
        val selEndLine = document.getLineNumber(nameRange.endOffset)
        val selEndChar = nameRange.endOffset - document.getLineStartOffset(selEndLine)

        val uri = file.url.replace("file://", "file:///")

        return CallHierarchyItem(
            name = functionInfo.name,
            kind = if (functionInfo.isConstructor) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD,
            detail = functionInfo.containingClass?.qualifiedName,
            uri = uri,
            range = Range(
                start = Position(line = startLine, character = startChar),
                end = Position(line = endLine, character = endChar)
            ),
            selectionRange = Range(
                start = Position(line = selStartLine, character = selStartChar),
                end = Position(line = selEndLine, character = selEndChar)
            )
        )
    }

    /** Gets the element range. */
    private fun getElementRange(
        element: PsiElement,
        document: com.intellij.openapi.editor.Document
    ): Range? {
        val textRange = element.textRange ?: return null

        val startLine = document.getLineNumber(textRange.startOffset)
        val startChar = textRange.startOffset - document.getLineStartOffset(startLine)
        val endLine = document.getLineNumber(textRange.endOffset)
        val endChar = textRange.endOffset - document.getLineStartOffset(endLine)

        return Range(
            start = Position(line = startLine, character = startChar),
            end = Position(line = endLine, character = endChar)
        )
    }
}
