package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.protocol.models.*
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UDeclaration
import org.jetbrains.uast.toUElement

/**
 * Phase 10: Rename Refactoring Provider (T010)
 *
 * IntelliJ integration for LSP rename. Uses ReferencesSearch to find references and generate a WorkspaceEdit.
 */
class RenameProvider(private val project: Project) {
    private val log = logger<RenameProvider>()

    /**
     * Prepares for a rename by checking whether the symbol can be renamed.
     *
     * @return PrepareRenameResult containing the range and placeholder, or null if the symbol cannot be renamed.
     */
    fun prepareRename(file: PsiFile, position: Position): PrepareRenameResult? =
        ReadAction.compute<PrepareRenameResult?, RuntimeException> {
            try {
                val document =
                    PsiDocumentManager.getInstance(project).getDocument(file)
                        ?: return@compute null
                val offset = document.getLineStartOffset(position.line) + position.character

                // Find the element at the position.
                val element = file.findElementAt(offset) ?: return@compute null
                val namedElement = findNamedElement(element) ?: return@compute null

                // Check whether it can be renamed.
                if (!isRenameable(namedElement)) {
                    log.debug("Element is not renameable: ${namedElement.javaClass.simpleName}")
                    return@compute null
                }

                // Get the name and range.
                val name = getElementName(namedElement) ?: return@compute null
                val nameRange = getNameRange(namedElement, document) ?: return@compute null

                log.debug("prepareRename: found renameable element '$name'")
                PrepareRenameResult(range = nameRange, placeholder = name)
            } catch (e: Exception) {
                log.warn("Error in prepareRename", e)
                null
            }
        }

    /**
     * Performs the rename and generates a WorkspaceEdit.
     *
     * @return WorkspaceEdit containing all locations to change, or null on failure.
     */
    fun rename(file: PsiFile, position: Position, newName: String): RenameResult? =
        ReadAction.compute<RenameResult?, RuntimeException> {
            try {
                val document =
                    PsiDocumentManager.getInstance(project).getDocument(file)
                        ?: return@compute null
                val offset = document.getLineStartOffset(position.line) + position.character

                // Find the element at the position.
                val element = file.findElementAt(offset) ?: return@compute null
                val namedElement = findNamedElement(element) ?: return@compute null

                // Check whether it can be renamed.
                if (!isRenameable(namedElement)) {
                    return@compute RenameResult.Error("Element is not renameable")
                }

                // Check for conflicts.
                val conflict = checkConflicts(namedElement, newName)
                if (conflict != null) {
                    return@compute RenameResult.Error(conflict)
                }

                // Collect all reference locations.
                val edits = mutableMapOf<String, MutableList<TextEdit>>()

                // 1. Add a change for the declaration.
                val declRange = getNameRange(namedElement, document)
                if (declRange != null) {
                    val uri =
                        file.virtualFile?.url?.replace("file://", "file:///")
                            ?: return@compute null
                    edits.getOrPut(uri) { mutableListOf() }.add(TextEdit(declRange, newName))
                }

                // 2. Find all references and add changes for them.
                val scope = GlobalSearchScope.projectScope(project)
                val references = ReferencesSearch.search(namedElement, scope).findAll()

                for (reference in references) {
                    val refElement = reference.element
                    val refFile = refElement.containingFile ?: continue
                    val refDoc =
                        PsiDocumentManager.getInstance(project).getDocument(refFile)
                            ?: continue
                    val refUri =
                        refFile.virtualFile?.url?.replace("file://", "file:///") ?: continue

                    val refRange = getElementRange(refElement, refDoc)
                    if (refRange != null) {
                        edits
                            .getOrPut(refUri) { mutableListOf() }
                            .add(TextEdit(refRange, newName))
                    }
                }

                log.info(
                    "rename: generated ${edits.values.sumOf { it.size }} edits across ${edits.size} files"
                )
                RenameResult.Success(WorkspaceEdit(changes = edits))
            } catch (e: Exception) {
                log.error("Error in rename", e)
                RenameResult.Error(e.message ?: "Unknown error")
            }
        }

    /** Finds the parent named element (PsiNamedElement). */
    private fun findNamedElement(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            // Try to get a unified representation with UAST.
            val uElement = current.toUElement()
            if (uElement is UDeclaration) {
                return current
            }
            // Fall back to PsiNamedElement.
            if (current is PsiNamedElement && current.name != null) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /** Checks whether the element can be renamed. */
    private fun isRenameable(element: PsiElement): Boolean {
        // Must be a named element.
        if (element !is PsiNamedElement) return false

        // Must be writable (not from an external library).
        if (!element.isWritable) {
            log.debug("Element is not writable (external library)")
            return false
        }

        // Check whether it comes from a library.
        val virtualFile = element.containingFile?.virtualFile
        if (virtualFile != null) {
            val scope = GlobalSearchScope.projectScope(project)
            if (!scope.contains(virtualFile)) {
                log.debug("Element is from external library")
                return false
            }
        }

        return true
    }

    /** Checks for rename conflicts using UAST (supports Java and Kotlin). */
    private fun checkConflicts(element: PsiElement, newName: String): String? {
        // Use UAST to find the containing class.
        val uClass = element.parent?.toUElement() as? UClass
        val psiClass = uClass?.javaPsi ?: (element.parent as? PsiClass)

        if (psiClass != null) {
            // Check for conflicts with class members.
            for (member in psiClass.allMethods) {
                if (member.name == newName && member != element) {
                    return "A method named '$newName' already exists in this class"
                }
            }
            for (field in psiClass.allFields) {
                if (field.name == newName && field != element) {
                    return "A field named '$newName' already exists in this class"
                }
            }
        }
        return null
    }

    /** Gets the element name. */
    private fun getElementName(element: PsiElement): String? {
        return when (element) {
            is PsiNamedElement -> element.name
            else -> null
        }
    }

    /** Gets the element name range. */
    private fun getNameRange(
        element: PsiElement,
        document: com.intellij.openapi.editor.Document
    ): Range? {
        val nameIdentifier =
            when (element) {
                is PsiNameIdentifierOwner -> element.nameIdentifier
                else -> null
            }

        val targetElement = nameIdentifier ?: element
        return getElementRange(targetElement, document)
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

/** Result of a Rename operation. */
sealed class RenameResult {
    data class Success(val edit: WorkspaceEdit) : RenameResult()
    data class Error(val message: String) : RenameResult()
}
