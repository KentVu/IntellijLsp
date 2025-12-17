package com.frenchef.intellijlsp.language

import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Generic language handler - a safe-failure fallback.
 * 
 * Design principles:
 * - Keep call chains safe by returning empty or partial results.
 * - Avoid precise semantic analysis to prevent false positives and performance issues.
 * - Provide only basic reference resolution.
 * - Leave extension points for language-specific handlers.
 */
class GenericLanguageHandler : LanguageHandler {
    private val log = logger<GenericLanguageHandler>()

    override fun supports(file: PsiFile): Boolean = true // Fallback handler; accepts all languages.

    override fun findContainingFunction(element: PsiElement): FunctionInfo? {
        // Return null rather than appearing to support a feature that later yields no results.
        // For non-JVM languages, returning no item lets the client show "no results"
        // instead of displaying an item that has no contents when opened.
        log.debug("GenericLanguageHandler.findContainingFunction: returning null (semantic analysis not supported)")
        return null
    }

    override fun findContainingClass(element: PsiElement): ClassInfo? {
        // Return null for the same reason as above.
        log.debug("GenericLanguageHandler.findContainingClass: returning null (semantic analysis not supported)")
        return null
    }

    override fun getCallExpressions(function: FunctionInfo): List<CallExpressionInfo> {
        // Return an empty list.
        // Do not traverse for calls without semantic information; that would produce many false positives.
        log.debug("GenericLanguageHandler.getCallExpressions: returning empty list (not supported)")
        return emptyList()
    }

    override fun getSuperTypes(classInfo: ClassInfo): List<ClassInfo> {
        // Return an empty list; semantic inheritance information is unavailable.
        log.debug("GenericLanguageHandler.getSuperTypes: returning empty list (not supported)")
        return emptyList()
    }

    override fun getSubTypes(classInfo: ClassInfo): List<ClassInfo> {
        // Return an empty list.
        log.debug("GenericLanguageHandler.getSubTypes: returning empty list (not supported)")
        return emptyList()
    }

    override fun resolveReference(element: PsiElement): PsiElement? {
        // Attempt basic reference resolution (a general PSI capability).
        return try {
            element.reference?.resolve()
        } catch (e: Exception) {
            log.debug("GenericLanguageHandler.resolveReference failed: ${e.message}")
            null
        }
    }

    override fun findContainingCallExpression(element: PsiElement): CallExpressionInfo? {
        // Safely fail by returning null.
        log.debug("GenericLanguageHandler.findContainingCallExpression: not supported")
        return null
    }

    override fun getSignatureInfo(callExpression: CallExpressionInfo): SignatureInfo? {
        // Safely fail by returning null.
        log.debug("GenericLanguageHandler.getSignatureInfo: not supported")
        return null
    }

    override fun getCallArgumentsInfo(callExpression: CallExpressionInfo): List<ArgumentInfo> {
        // Safely fail by returning an empty list.
        log.debug("GenericLanguageHandler.getCallArgumentsInfo: not supported")
        return emptyList()
    }

    override fun getVariablesNeedingTypeHints(
        file: PsiFile,
        startOffset: Int,
        endOffset: Int
    ): List<VariableTypeHintInfo> {
        // Safely fail by returning an empty list.
        log.debug("GenericLanguageHandler.getVariablesNeedingTypeHints: not supported")
        return emptyList()
    }

    // ============ Heuristic helper methods ============

    private fun isFunctionLike(element: PsiElement): Boolean {
        val className = element.javaClass.simpleName.lowercase()
        return className.contains("function") ||
                className.contains("method") ||
                className.contains("procedure") ||
                className.contains("routine")
    }

    private fun isClassLike(element: PsiElement): Boolean {
        val className = element.javaClass.simpleName.lowercase()
        return (className.contains("class") && !className.contains("anonymous")) ||
                className.contains("interface") ||
                className.contains("trait") ||
                className.contains("struct") ||
                (className.contains("type") && className.contains("def"))
    }
}
