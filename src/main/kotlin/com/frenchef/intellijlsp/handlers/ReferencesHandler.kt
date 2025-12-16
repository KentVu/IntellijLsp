package com.frenchef.intellijlsp.handlers

import com.frenchef.intellijlsp.intellij.DocumentManager
import com.frenchef.intellijlsp.intellij.LspDecompiledUriResolver
import com.frenchef.intellijlsp.intellij.PsiMapper
import com.frenchef.intellijlsp.protocol.JsonRpcHandler
import com.frenchef.intellijlsp.protocol.LspGson
import com.frenchef.intellijlsp.protocol.models.Location
import com.frenchef.intellijlsp.protocol.models.ReferenceParams
import com.google.gson.JsonElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.search.searches.ReferencesSearch

/**
 * Handles textDocument/references requests.
 * Provides "find all references" functionality.
 */
class ReferencesHandler(
    private val project: Project,
    private val jsonRpcHandler: JsonRpcHandler,
    private val documentManager: DocumentManager
) {
    private val log = logger<ReferencesHandler>()
    private val gson = LspGson.instance

    fun register() {
        jsonRpcHandler.registerRequestHandler("textDocument/references", this::handleReferences)
    }

    /**
     * Handle textDocument/references request.
     */
    private fun handleReferences(params: JsonElement?): JsonElement? {
        if (params == null || params.isJsonNull) {
            return null
        }

        try {
            val refParams = gson.fromJson(params, ReferenceParams::class.java)
            val uri = refParams.textDocument.uri
            val position = refParams.position
            val includeDeclaration = refParams.context.includeDeclaration
            
            log.debug("References requested for $uri at line ${position.line}, char ${position.character}")

            // Use the shared URI resolver: map cache files to JAR PSI, but calculate offsets from cached file text.
            val resolved = LspDecompiledUriResolver.resolveForRequest(project, uri) ?: return null

            val psiFile = resolved.analysisPsiFile
            val fileText = resolved.fileText

            val element = PsiMapper.getPsiElementAtPosition(psiFile, fileText, position)
            
            if (element == null) {
                log.debug("No PSI element found at position")
                return null
            }
            
            // Get references
            val locations = getReferences(element, includeDeclaration)
            
            if (locations.isEmpty()) {
                log.debug("No references found")
                return null
            }
            
            return gson.toJsonTree(locations)
            
        } catch (e: Exception) {
            log.error("Error handling references", e)
            return null
        }
    }

    /**
     * Get all references to a PSI element.
     */
    private fun getReferences(element: com.intellij.psi.PsiElement, includeDeclaration: Boolean): List<Location> {
        return ReadAction.compute<List<Location>, RuntimeException> {
            val locations = mutableListOf<Location>()
            
            try {
                // Find the searchable target element (PsiNamedElement).
                val targetElement = findSearchableElement(element)
                if (targetElement == null) {
                    log.warn("No searchable element found for: ${element.javaClass.simpleName}")
                    return@compute locations
                }

                log.info("Searching references for: ${targetElement.javaClass.simpleName}, name='${(targetElement as? com.intellij.psi.PsiNamedElement)?.name}'")

                // Include the declaration location.
                if (includeDeclaration) {
                    val declLocation = PsiMapper.psiElementToLocation(targetElement, project)
                    if (declLocation != null) {
                        locations.add(declLocation)
                        log.info("Added declaration location")
                    }
                }

                // Search for references within the project scope.
                val searchScope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
                val query = ReferencesSearch.search(targetElement, searchScope)
                val allRefs = query.findAll()

                log.info("Found ${allRefs.size} reference(s) in project")

                for (reference in allRefs.take(100)) { // Limit to 100 references
                    val refElement = reference.element
                    val location = PsiMapper.psiElementToLocation(refElement, project)
                    
                    if (location != null) {
                        locations.add(location)
                        log.debug("  Reference at: ${location.uri}:${location.range.start.line}")
                    }
                }

                log.info("Returning ${locations.size} location(s)")
                
            } catch (e: Exception) {
                log.warn("Error getting references", e)
            }
            
            locations
        }
    }

    /**
     * Find a searchable element (PsiNamedElement) by walking up the PSI tree.
     */
    private fun findSearchableElement(element: com.intellij.psi.PsiElement): com.intellij.psi.PsiElement? {
        var current: com.intellij.psi.PsiElement? = element

        // First try to resolve the reference.
        val resolved = element.reference?.resolve()
        if (resolved != null && resolved is com.intellij.psi.PsiNamedElement) {
            log.info("Resolved reference to: ${resolved.javaClass.simpleName}")
            return resolved
        }

        // Walk up the tree to find a PsiNamedElement.
        while (current != null && current !is com.intellij.psi.PsiFile) {
            if (current is com.intellij.psi.PsiNamedElement) {
                log.info("Found PsiNamedElement: ${current.javaClass.simpleName}, name='${current.name}'")
                return current
            }
            current = current.parent
        }

        return null
    }
}
