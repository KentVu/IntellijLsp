package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.protocol.models.Position
import com.frenchef.intellijlsp.protocol.models.Range
import com.frenchef.intellijlsp.protocol.models.SymbolKind
import com.frenchef.intellijlsp.protocol.models.TypeHierarchyItem
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import org.jetbrains.uast.UClass
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType

/**
 * Phase 10: Type Hierarchy Provider (T020)
 *
 * IntelliJ integration for LSP Type Hierarchy. Uses the PsiClass API to get supertypes and ClassInheritorsSearch to get subtypes.
 * Includes a visited set to prevent infinite recursion caused by inheritance cycles.
 */
class TypeHierarchyProvider(private val project: Project) {
    private val log = logger<TypeHierarchyProvider>()

    private companion object {
        const val MAX_RESULTS = 100
    }

    /** Prepares the type hierarchy, returning the class at the position as a TypeHierarchyItem. */
    fun prepareTypeHierarchy(file: PsiFile, position: Position): List<TypeHierarchyItem>? =
        ReadAction.compute<List<TypeHierarchyItem>?, RuntimeException> {
            try {
                val document =
                    PsiDocumentManager.getInstance(project).getDocument(file)
                        ?: return@compute null
                val offset = document.getLineStartOffset(position.line) + position.character

                // Find the element at the position.
                val element = file.findElementAt(offset) ?: return@compute null
                val psiClass = findClass(element) ?: return@compute null

                val item = classToItem(psiClass, document) ?: return@compute null
                log.debug("prepareTypeHierarchy: found class '${item.name}'")
                listOf(item)
            } catch (e: Exception) {
                log.warn("Error in prepareTypeHierarchy", e)
                null
            }
        }

    /** Gets supertypes (superclasses and interfaces); uses a visited set to prevent cycles. */
    fun getSupertypes(item: TypeHierarchyItem): List<TypeHierarchyItem> =
        ReadAction.compute<List<TypeHierarchyItem>, RuntimeException> {
            try {
                val psiClass = findClassByItem(item) ?: return@compute emptyList()
                val results = mutableListOf<TypeHierarchyItem>()
                val visited = mutableSetOf<String>()

                // Add the current class to visited to prevent cycles.
                psiClass.qualifiedName?.let { visited.add(it) }

                // Add the superclass.
                psiClass.superClass?.let { superClass ->
                    val key = superClass.qualifiedName
                    if (key != null && !visited.contains(key) && results.size < MAX_RESULTS) {
                        visited.add(key)
                        val doc =
                            superClass.containingFile?.let {
                                PsiDocumentManager.getInstance(project).getDocument(it)
                            }
                        if (doc != null) {
                            classToItem(superClass, doc)?.let { results.add(it) }
                        }
                    }
                }

                // Add interfaces.
                for (iface in psiClass.interfaces) {
                    if (results.size >= MAX_RESULTS) break
                    val key = iface.qualifiedName
                    if (key != null && !visited.contains(key)) {
                        visited.add(key)
                        val doc =
                            iface.containingFile?.let {
                                PsiDocumentManager.getInstance(project).getDocument(it)
                            }
                        if (doc != null) {
                            classToItem(iface, doc)?.let { results.add(it) }
                        }
                    }
                }

                log.debug("getSupertypes: found ${results.size} supertypes")
                results
            } catch (e: Exception) {
                log.warn("Error in getSupertypes", e)
                emptyList()
            }
        }

    /** Gets subtypes (subclasses and implementations); uses a visited set to prevent cycles. */
    fun getSubtypes(item: TypeHierarchyItem): List<TypeHierarchyItem> =
        ReadAction.compute<List<TypeHierarchyItem>, RuntimeException> {
            try {
                val psiClass = findClassByItem(item) ?: return@compute emptyList()
                val results = mutableListOf<TypeHierarchyItem>()
                val visited = mutableSetOf<String>()

                // Add the current class to visited to prevent cycles.
                psiClass.qualifiedName?.let { visited.add(it) }

                val scope = GlobalSearchScope.projectScope(project)
                // Get only direct subclasses (deep = false).
                val inheritors = ClassInheritorsSearch.search(psiClass, scope, false).findAll()

                for (inheritor in inheritors) {
                    if (results.size >= MAX_RESULTS) break

                    val key = inheritor.qualifiedName
                    if (key != null && !visited.contains(key)) {
                        visited.add(key)
                        val doc =
                            inheritor.containingFile?.let {
                                PsiDocumentManager.getInstance(project).getDocument(it)
                            }
                        if (doc != null) {
                            classToItem(inheritor, doc)?.let { results.add(it) }
                        }
                    }
                }

                log.debug("getSubtypes: found ${results.size} subtypes")
                results
            } catch (e: Exception) {
                log.warn("Error in getSubtypes", e)
                emptyList()
            }
        }

    /** Finds the parent class using UAST (supports Java and Kotlin). */
    private fun findClass(element: PsiElement): PsiClass? {
        // First try to find it with UAST (supports Kotlin).
        val uClass = element.toUElementOfType<UClass>()
        if (uClass != null) {
            return uClass.javaPsi
        }

        // Search upward for a UClass.
        var current: PsiElement? = element
        while (current != null) {
            val uElement = current.toUElement()
            if (uElement is UClass) {
                return uElement.javaPsi
            }
            if (current is PsiClass) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /** Finds a class from a TypeHierarchyItem. */
    private fun findClassByItem(item: TypeHierarchyItem): PsiClass? {
        val virtualFile =
            com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                .findFileByUrl(item.uri.replace("file:///", "file://"))
                ?: return null

        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val offset =
            document.getLineStartOffset(item.selectionRange.start.line) +
                    item.selectionRange.start.character
        val element = psiFile.findElementAt(offset) ?: return null

        return findClass(element)
    }

    /** Converts a PsiClass to a TypeHierarchyItem. */
    private fun classToItem(
        psiClass: PsiClass,
        document: com.intellij.openapi.editor.Document
    ): TypeHierarchyItem? {
        val file = psiClass.containingFile?.virtualFile ?: return null
        val textRange = psiClass.textRange ?: return null
        val nameIdentifier = psiClass.nameIdentifier ?: return null
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

        val kind =
            when {
                psiClass.isInterface -> SymbolKind.INTERFACE
                psiClass.isEnum -> SymbolKind.ENUM
                else -> SymbolKind.CLASS
            }

        return TypeHierarchyItem(
            name = psiClass.name ?: return null,
            kind = kind,
            detail = psiClass.qualifiedName,
            uri = uri,
            range =
                Range(
                    start = Position(line = startLine, character = startChar),
                    end = Position(line = endLine, character = endChar)
                ),
            selectionRange =
                Range(
                    start = Position(line = selStartLine, character = selStartChar),
                    end = Position(line = selEndLine, character = selEndChar)
                )
        )
    }
}
