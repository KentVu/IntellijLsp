package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.protocol.models.Diagnostic
import com.frenchef.intellijlsp.protocol.models.DiagnosticSeverity
import com.frenchef.intellijlsp.util.LspLogger
import com.intellij.codeInspection.*
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Diagnostics provider based on the Inspection API.
 * Does not require an open editor and can run inspections on any PsiFile.
 * 
 * Advantages:
 * - The file does not need to be open in an editor.
 * - Can retrieve ProblemDescriptors containing QuickFix information.
 * 
 * Disadvantages:
 * - Slower because all inspections must run.
 * - Results may not exactly match editor highlights.
 */
class InspectionDiagnosticsProvider(private val project: Project) {
    private val log = logger<InspectionDiagnosticsProvider>()

    /**
     * Cached inspection results used to retrieve QuickFixes during CodeAction resolve.
     */
    data class DiagnosticWithDescriptor(
        val diagnostic: Diagnostic,
        val descriptor: ProblemDescriptor,
        val inspection: LocalInspectionTool
    )

    /**
     * Gets diagnostics while retaining ProblemDescriptors for QuickFixes.
     */
    fun getDiagnosticsWithDescriptors(psiFile: PsiFile, document: Document): List<DiagnosticWithDescriptor> {
        return ReadAction.compute<List<DiagnosticWithDescriptor>, RuntimeException> {
            try {
                val results = mutableListOf<DiagnosticWithDescriptor>()
                val seen = mutableSetOf<String>()

                val inspectionManager = InspectionManager.getInstance(project)
                val profile = InspectionProjectProfileManager.getInstance(project).currentProfile

                LspLogger.info("Inspection", "Using profile: ${profile.name} for file: ${psiFile.name}")

                // Get all enabled local inspection tools.
                val enabledTools = getEnabledLocalInspections(profile, psiFile)

                LspLogger.info("Inspection", "Found ${enabledTools.size} enabled local inspections for ${psiFile.name}")

                for ((tool, wrapper) in enabledTools) {
                    try {
                        // Run the inspection.
                        val problems = tool.processFile(psiFile, inspectionManager)

                        if (problems.isNotEmpty()) {
                            LspLogger.debug("Inspection", "${tool.shortName} found ${problems.size} problems")
                        }

                        for (problem in problems) {
                            val diagnostic = problemDescriptorToDiagnostic(problem, document, wrapper)
                            if (diagnostic != null) {
                                val key = "${diagnostic.range.start.line}:${diagnostic.range.start.character}:" +
                                        "${diagnostic.range.end.line}:${diagnostic.range.end.character}:" +
                                        "${diagnostic.message}:${diagnostic.severity?.value}"
                                if (seen.add(key)) {
                                    results.add(DiagnosticWithDescriptor(diagnostic, problem, tool))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        log.debug("Error running inspection ${tool.shortName}: ${e.message}")
                    }
                }

                LspLogger.info(
                    "Inspection",
                    "Total: ${results.size} diagnostics from ${enabledTools.size} inspections for ${psiFile.name}"
                )
                results
            } catch (e: Exception) {
                log.warn("Error getting inspection diagnostics", e)
                emptyList()
            }
        }
    }

    /**
     * Gets the diagnostic list without descriptors (for publishing).
     */
    fun getDiagnostics(psiFile: PsiFile, document: Document): List<Diagnostic> {
        return getDiagnosticsWithDescriptors(psiFile, document).map { it.diagnostic }
    }

    /**
     * Gets diagnostics for a file.
     */
    fun getDiagnosticsByUri(uri: String, virtualFile: VirtualFile, document: Document): List<Diagnostic> {
        return ReadAction.compute<List<Diagnostic>, RuntimeException> {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                if (psiFile != null) {
                    getDiagnostics(psiFile, document)
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                log.warn("Error getting diagnostics for $uri", e)
                emptyList()
            }
        }
    }

    /**
     * Gets enabled local inspection tools.
     */
    private fun getEnabledLocalInspections(
        profile: InspectionProfileImpl,
        psiFile: PsiFile
    ): List<Pair<LocalInspectionTool, InspectionToolWrapper<*, *>>> {
        val result = mutableListOf<Pair<LocalInspectionTool, InspectionToolWrapper<*, *>>>()

        for (toolWrapper in profile.getInspectionTools(psiFile)) {
            // Use isToolEnabled to check whether the tool is enabled.
            val key = com.intellij.codeInsight.daemon.HighlightDisplayKey.find(toolWrapper.shortName)
            if (key == null || !profile.isToolEnabled(key, psiFile)) continue

            val tool = toolWrapper.tool
            if (tool is LocalInspectionTool) {
                // Check whether the inspection applies to this file.
                if (tool.isSuppressedFor(psiFile)) continue
                result.add(Pair(tool, toolWrapper))
            }
        }

        return result
    }

    /**
     * Converts a ProblemDescriptor to an LSP Diagnostic.
     */
    private fun problemDescriptorToDiagnostic(
        descriptor: ProblemDescriptor,
        document: Document,
        wrapper: InspectionToolWrapper<*, *>
    ): Diagnostic? {
        try {
            val psiElement = descriptor.psiElement ?: return null
            val textRange = descriptor.textRangeInElement?.shiftRight(psiElement.textOffset)
                ?: psiElement.textRange
                ?: return null

            if (textRange.startOffset < 0 || textRange.endOffset > document.textLength) {
                return null
            }

            val range = PsiMapper.textRangeToRange(document, textRange)
            val severity = highlightTypeToDiagnosticSeverity(descriptor.highlightType)
            val message = descriptor.descriptionTemplate ?: return null

            if (message.isBlank()) return null

            return Diagnostic(
                range = range,
                severity = severity,
                code = wrapper.shortName,
                source = "IntelliJ",
                message = message
            )
        } catch (e: Exception) {
            log.debug("Error converting ProblemDescriptor to Diagnostic: ${e.message}")
            return null
        }
    }

    /**
     * Converts a highlight type to diagnostic severity.
     */
    private fun highlightTypeToDiagnosticSeverity(highlightType: ProblemHighlightType): DiagnosticSeverity {
        return when (highlightType) {
            ProblemHighlightType.ERROR,
            ProblemHighlightType.GENERIC_ERROR -> DiagnosticSeverity.ERROR

            ProblemHighlightType.WARNING,
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> DiagnosticSeverity.WARNING

            ProblemHighlightType.WEAK_WARNING,
            ProblemHighlightType.INFORMATION -> DiagnosticSeverity.INFORMATION

            else -> DiagnosticSeverity.HINT
        }
    }

    /**
     * Finds the corresponding ProblemDescriptor by position and message (for CodeAction resolve).
     */
    fun findProblemDescriptor(
        psiFile: PsiFile,
        document: Document,
        offset: Int,
        message: String
    ): Pair<ProblemDescriptor, LocalInspectionTool>? {
        val diagnosticsWithDescriptors = getDiagnosticsWithDescriptors(psiFile, document)

        for (item in diagnosticsWithDescriptors) {
            val startOffset = PsiMapper.positionToOffset(document, item.diagnostic.range.start)
            val endOffset = PsiMapper.positionToOffset(document, item.diagnostic.range.end)

            // Check whether the offset is in range and the message matches.
            if (offset in startOffset..endOffset && item.diagnostic.message == message) {
                return Pair(item.descriptor, item.inspection)
            }
        }

        return null
    }

    /**
     * Gets the list of QuickFixes for a ProblemDescriptor.
     */
    fun getQuickFixes(descriptor: ProblemDescriptor): Array<out LocalQuickFix>? {
        return descriptor.fixes?.filterIsInstance<LocalQuickFix>()?.toTypedArray()
    }
}
