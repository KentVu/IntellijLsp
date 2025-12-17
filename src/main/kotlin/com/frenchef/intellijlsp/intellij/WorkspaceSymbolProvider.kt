package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.protocol.models.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

/**
 * Workspace symbol search provider.
 *
 * Uses the LanguageHandler abstraction for multi-language symbol indexing.
 * Provides full functionality for JVM languages and degraded support for others.
 */
class WorkspaceSymbolProvider(private val project: Project) {
    private val log = logger<WorkspaceSymbolProvider>()

    private companion object {
        const val MAX_RESULTS = 100
    }

    /**
     * Searches for symbols matching the query.
     */
    fun searchSymbols(query: String): List<SymbolInformation> {
        if (query.isBlank()) {
            log.debug("Empty query, returning empty list")
            return emptyList()
        }

        return ReadAction.compute<List<SymbolInformation>, RuntimeException> {
            try {
                val results = mutableListOf<SymbolInformation>()
                val scope = GlobalSearchScope.projectScope(project)

                // Use PsiShortNamesCache to search for classes (provided by the Java plugin; generic PSI API).
                searchClasses(query, scope, results)

                // Use PsiShortNamesCache to search for methods.
                searchMethods(query, scope, results)

                log.info("Found ${results.size} symbols for query: $query")
                results.take(MAX_RESULTS)
            } catch (e: Exception) {
                log.warn("Error searching symbols for query: $query", e)
                emptyList()
            }
        }
    }

    /**
     * Searches for classes using the generic PSI API.
     */
    private fun searchClasses(
        query: String,
        scope: GlobalSearchScope,
        results: MutableList<SymbolInformation>
    ) {
        try {
            val cache = PsiShortNamesCache.getInstance(project)
            val classNames = cache.allClassNames.filter {
                it.contains(query, ignoreCase = true)
            }.take(50)

            for (name in classNames) {
                if (results.size >= MAX_RESULTS) break
                val classes = cache.getClassesByName(name, scope)
                for (psiClass in classes) {
                    if (results.size >= MAX_RESULTS) break
                    val symbol = psiClassToSymbol(psiClass)
                    if (symbol != null) {
                        results.add(symbol)
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Error searching classes: ${e.message}")
        }
    }

    /**
     * Searches for methods using the generic PSI API.
     */
    private fun searchMethods(
        query: String,
        scope: GlobalSearchScope,
        results: MutableList<SymbolInformation>
    ) {
        try {
            val cache = PsiShortNamesCache.getInstance(project)
            val methodNames = cache.allMethodNames.filter {
                it.contains(query, ignoreCase = true)
            }.take(50)

            for (name in methodNames) {
                if (results.size >= MAX_RESULTS) break
                val methods = cache.getMethodsByName(name, scope)
                for (method in methods) {
                    if (results.size >= MAX_RESULTS) break
                    val symbol = psiMethodToSymbol(method)
                    if (symbol != null) {
                        results.add(symbol)
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Error searching methods: ${e.message}")
        }
    }

    /**
     * Converts a PsiClass to SymbolInformation using the generic PSI API.
     */
    private fun psiClassToSymbol(psiClass: PsiClass): SymbolInformation? {
        val file = psiClass.containingFile?.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiClass.containingFile) ?: return null

        val name = psiClass.name ?: return null
        val kind = when {
            psiClass.isInterface -> SymbolKind.INTERFACE
            psiClass.isEnum -> SymbolKind.ENUM
            else -> SymbolKind.CLASS
        }

        val startOffset = psiClass.textRange?.startOffset ?: return null
        val startLine = document.getLineNumber(startOffset)
        val startChar = startOffset - document.getLineStartOffset(startLine)

        return SymbolInformation(
            name = name,
            kind = kind,
            location = Location(
                uri = file.url.replace("file://", "file:///"),
                range = Range(
                    start = Position(line = startLine, character = startChar),
                    end = Position(line = startLine, character = startChar + name.length)
                )
            ),
            containerName = psiClass.containingClass?.qualifiedName,
            tags = null,
            deprecated = psiClass.isDeprecated
        )
    }

    /**
     * Converts a PsiMethod to SymbolInformation using the generic PSI API.
     */
    private fun psiMethodToSymbol(psiMethod: PsiMethod): SymbolInformation? {
        val file = psiMethod.containingFile?.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiMethod.containingFile) ?: return null

        val name = psiMethod.name
        val kind = if (psiMethod.isConstructor) SymbolKind.CONSTRUCTOR else SymbolKind.METHOD

        val startOffset = psiMethod.textRange?.startOffset ?: return null
        val startLine = document.getLineNumber(startOffset)
        val startChar = startOffset - document.getLineStartOffset(startLine)

        return SymbolInformation(
            name = name,
            kind = kind,
            location = Location(
                uri = file.url.replace("file://", "file:///"),
                range = Range(
                    start = Position(line = startLine, character = startChar),
                    end = Position(line = startLine, character = startChar + name.length)
                )
            ),
            containerName = psiMethod.containingClass?.qualifiedName,
            tags = null,
            deprecated = psiMethod.isDeprecated
        )
    }
}
