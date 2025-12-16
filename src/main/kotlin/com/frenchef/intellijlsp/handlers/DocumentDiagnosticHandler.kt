package com.frenchef.intellijlsp.handlers

import com.frenchef.intellijlsp.intellij.BackgroundAnalyzer
import com.frenchef.intellijlsp.intellij.DiagnosticsProvider
import com.frenchef.intellijlsp.intellij.DocumentManager
import com.frenchef.intellijlsp.intellij.InspectionDiagnosticsProvider
import com.frenchef.intellijlsp.protocol.JsonRpcHandler
import com.frenchef.intellijlsp.protocol.LspGson
import com.frenchef.intellijlsp.protocol.models.Diagnostic
import com.frenchef.intellijlsp.protocol.models.DocumentDiagnosticParams
import com.frenchef.intellijlsp.protocol.models.FullDocumentDiagnosticReport
import com.frenchef.intellijlsp.protocol.models.UnchangedDocumentDiagnosticReport
import com.frenchef.intellijlsp.util.LspLogger
import com.google.gson.JsonElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles textDocument/diagnostic requests (LSP 3.17 pull diagnostics).
 * 
 * This endpoint lets the client request diagnostics, unlike publishDiagnostics (push diagnostics).
 * Use it when:
 * 1. The client needs diagnostics immediately after opening a file.
 * 2. The client requests diagnostics after an agent edits a file in the background.
 * 3. Diagnostics must be retrieved synchronously instead of waiting for server notifications.
 */
class DocumentDiagnosticHandler(
    private val project: Project,
    private val jsonRpcHandler: JsonRpcHandler,
    private val documentManager: DocumentManager
) {
    private val log = logger<DocumentDiagnosticHandler>()
    private val gson = LspGson.instance

    // Background analyzer.
    private val backgroundAnalyzer = BackgroundAnalyzer(project)

    // Diagnostics providers.
    private val diagnosticsProvider = DiagnosticsProvider(project)
    private val inspectionProvider = InspectionDiagnosticsProvider(project)

    // Cache resultIds for incremental updates.
    private val resultIdCache = ConcurrentHashMap<String, String>()
    private val diagnosticsCache = ConcurrentHashMap<String, List<Diagnostic>>()

    companion object {
        private const val TAG = "DocumentDiagnostic"
    }

    fun register() {
        jsonRpcHandler.registerRequestHandler("textDocument/diagnostic", this::handleDocumentDiagnostic)
        log.info("DocumentDiagnosticHandler registered for textDocument/diagnostic")
    }

    /**
     * Handles a textDocument/diagnostic request.
     */
    private fun handleDocumentDiagnostic(params: JsonElement?): JsonElement? {
        if (params == null || params.isJsonNull) {
            log.warn("textDocument/diagnostic called with null params")
            return null
        }

        return try {
            val request = gson.fromJson(params, DocumentDiagnosticParams::class.java)
            val uri = request.textDocument.uri

            LspLogger.info(TAG, "Received diagnostic request: $uri")

            // Check whether an unchanged report can be returned.
            val previousResultId = request.previousResultId
            if (previousResultId != null) {
                val cachedResultId = resultIdCache[uri]
                if (cachedResultId == previousResultId) {
                    // Diagnostics have not changed; return an unchanged report.
                    LspLogger.debug(TAG, "Diagnostics unchanged; returning unchanged report: $uri")
                    return gson.toJsonTree(UnchangedDocumentDiagnosticReport(resultId = cachedResultId))
                }
            }

            // Get diagnostics.
            val diagnostics = getDiagnosticsForFile(uri)

            // Generate a new resultId.
            val newResultId = "${System.currentTimeMillis()}-${diagnostics.hashCode()}"
            resultIdCache[uri] = newResultId
            diagnosticsCache[uri] = diagnostics

            LspLogger.info(TAG, "Returning ${diagnostics.size} diagnostics: $uri")

            // Return a full report.
            val report = FullDocumentDiagnosticReport(
                resultId = newResultId,
                items = diagnostics
            )

            gson.toJsonTree(report)
        } catch (e: Exception) {
            log.error("Error handling textDocument/diagnostic", e)
            null
        }
    }

    /**
     * Gets diagnostics for a file.
     * 
     * Strategy:
     * 1. Open the file in an IntelliJ editor to trigger DaemonCodeAnalyzer.
     * 2. Try to get diagnostics from MarkupModel (requires analysis results for an open file).
     * 3. If MarkupModel has no results, use the Inspection API.
     */
    /**
     * Gets diagnostics for a file.
     * 
     * Strategy:
     * 1. Open the file in an IntelliJ editor to trigger DaemonCodeAnalyzer.
     * 2. Poll MarkupModel for diagnostics (wait up to 1 second).
     * 3. If MarkupModel has no results, fall back to the Inspection API.
     */
    private fun getDiagnosticsForFile(uri: String): List<Diagnostic> {
        val virtualFile = documentManager.getVirtualFile(uri)
        val document = documentManager.getIntellijDocument(uri)

        if (virtualFile == null || document == null) {
            LspLogger.warn(TAG, "Could not get file or document: $uri")
            return emptyList()
        }

        if (!virtualFile.isValid) return emptyList()

        // Step 1: Ensure the file is open in an IntelliJ editor.
        backgroundAnalyzer.ensureFileOpenForAnalysis(virtualFile, uri)

        // Step 2 & 3: Poll MarkupModel.
        var diagnostics = emptyList<Diagnostic>()
        var attempts = 0
        val maxAttempts = 10 // 10 * 100ms = 1s max

        while (attempts < maxAttempts) {
            attempts++
            Thread.sleep(100)

            if (!virtualFile.isValid) return emptyList()

            val currentDiagnostics = diagnosticsProvider.getDiagnosticsByUri(uri, virtualFile, document)
            if (currentDiagnostics.isNotEmpty()) {
                // Diagnostics found; return them immediately.
                LspLogger.debug(TAG, "MarkupModel returned ${currentDiagnostics.size} diagnostics quickly (attempt $attempts): $uri")
                diagnostics = currentDiagnostics
                break
            }
        }

        if (diagnostics.isNotEmpty()) {
            return diagnostics
        }

        if (!virtualFile.isValid) return emptyList()

        // Retry MarkupModel if polling ended without results (there may be no errors, or analysis may still be running).
        diagnostics = diagnosticsProvider.getDiagnosticsByUri(uri, virtualFile, document)
        if (diagnostics.isNotEmpty()) {
            LspLogger.debug(TAG, "MarkupModel finally returned ${diagnostics.size} diagnostics: $uri")
            return diagnostics
        }

        // Step 4: If MarkupModel still has no diagnostics, try the Inspection API.
        // This fallback is safe when the file has no errors; the Inspection API returns an empty list.
        LspLogger.debug(TAG, "Trying the Inspection API: $uri")
        try {
            diagnostics = ReadAction.compute<List<Diagnostic>, RuntimeException> {
                if (!virtualFile.isValid) return@compute emptyList()
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile != null) {
                    inspectionProvider.getDiagnostics(psiFile, document)
                } else {
                    emptyList()
                }
            }
            LspLogger.debug(TAG, "Inspection API returned ${diagnostics.size} diagnostics: $uri")
        } catch (e: Exception) {
            LspLogger.warn(TAG, "Inspection API error: ${e.message}")
        }

        return diagnostics
    }

    /**
     * Cleans up resources.
     */
    fun dispose() {
        resultIdCache.clear()
        diagnosticsCache.clear()
        backgroundAnalyzer.dispose()
    }
}
