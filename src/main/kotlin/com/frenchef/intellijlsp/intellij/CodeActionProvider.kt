package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.protocol.models.*
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * CodeAction provider compliant with the LSP 3.17 specification.
 *
 * Collects CodeActions from three sources:
 * 1. QuickFixes from DaemonCodeAnalyzer HighlightInfo (diagnostic-related; requires an open file).
 * 2. ProblemDescriptors from InspectionDiagnosticsProvider (does not require an open file).
 * 3. Intentions from IntentionManager (checks availability with isAvailable).
 *
 * Returned CodeActions include a data field used to restore context during codeAction/resolve.
 */
class CodeActionProvider(private val project: Project) {
    private val log = logger<CodeActionProvider>()
    private val inspectionProvider = InspectionDiagnosticsProvider(project)

    private companion object {
        const val MAX_ACTIONS = 50
    }

    /** Gets available code actions within the given range. */
    fun getCodeActions(
        psiFile: PsiFile,
        range: Range,
        @Suppress("UNUSED_PARAMETER") context: CodeActionContext,
        uri: String // File URI used for resolve.
    ): List<CodeAction> {
        try {
            // 1. Prepare data (ReadAction).
            val (document, startOffset, endOffset) =
                ReadAction.compute<Triple<Document, Int, Int>?, RuntimeException> {
                    val doc =
                        PsiDocumentManager.getInstance(project).getDocument(psiFile)
                            ?: return@compute null
                    val start = PsiMapper.positionToOffset(doc, range.start)
                    val end = PsiMapper.positionToOffset(doc, range.end)
                    Triple(doc, start, end)
                }
                    ?: return emptyList()

            val actions = mutableListOf<CodeAction>()
            val seenTitles = mutableSetOf<String>()

            // 2. Collect QuickFixes from HighlightInfo (ReadAction; requires an open file).
            ReadAction.run<RuntimeException> {
                collectQuickFixesFromHighlights(
                    psiFile,
                    document,
                    startOffset,
                    endOffset,
                    uri,
                    actions,
                    seenTitles
                )
            }

            // 3. If HighlightInfo does not provide enough QuickFixes, try the Inspection API.
            if (actions.size < 5) {
                ReadAction.run<RuntimeException> {
                    collectQuickFixesFromInspections(
                        psiFile,
                        document,
                        startOffset,
                        endOffset,
                        uri,
                        actions,
                        seenTitles
                    )
                }
            }

            // 4. Collect intentions from IntentionManager (EDT handled internally).
            // Creating an Editor and checking intention availability require the EDT.
            collectAvailableIntentions(psiFile, document, startOffset, uri, actions, seenTitles)

            log.info("Found ${actions.size} code actions at position $startOffset-$endOffset")
            return actions.take(MAX_ACTIONS)
        } catch (e: Exception) {
            log.warn("Error getting code actions", e)
            return emptyList()
        }
    }

    /** Collects QuickFixes from DaemonCodeAnalyzer HighlightInfo. */
    private fun collectQuickFixesFromHighlights(
        psiFile: PsiFile,
        document: Document,
        startOffset: Int,
        endOffset: Int,
        uri: String,
        actions: MutableList<CodeAction>,
        seenTitles: MutableSet<String>
    ) {
        try {
            // Process highlights with the static method.
            DaemonCodeAnalyzerEx.processHighlights(
                document,
                project,
                null, // severity filter - null means all
                startOffset,
                endOffset
            ) { info: HighlightInfo ->
                if (actions.size >= MAX_ACTIONS) return@processHighlights false

                // Process only highlights with problems (weak warnings and above).
                // Many Kotlin hints that are fixable but not warnings are WEAK_WARNING; otherwise QuickFixes
                // (such as removing val/var from a parameter) would be missed.
                val severity = info.severity
                if (severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal) {
                    // QuickFix requires the exact caret offset; use HighlightInfo's start instead of the request range start.
                    collectQuickFixesFromInfo(info, info.startOffset, uri, actions, seenTitles)
                }
                true // Continue processing.
            }

            log.debug("Collected ${actions.size} quick fixes from highlights")
        } catch (e: Exception) {
            log.debug("Error collecting quick fixes from highlights: ${e.message}")
        }
    }

    /** Collects QuickFixes from a single HighlightInfo. */
    private fun collectQuickFixesFromInfo(
        info: HighlightInfo,
        offset: Int,
        uri: String,
        actions: MutableList<CodeAction>,
        seenTitles: MutableSet<String>
    ) {
        try {
            info.findRegisteredQuickFix<Any?> { descriptor, _ ->
                val action = descriptor.action
                val title = action.text

                if (title.isNotBlank() && title !in seenTitles) {
                    seenTitles.add(title)
                    actions.add(
                        CodeAction(
                            title = title,
                            kind = CodeActionKinds.QUICK_FIX,
                            diagnostics = null,
                            isPreferred = info.severity == HighlightSeverity.ERROR,
                            edit = null,
                            command = null,
                            data =
                                CodeActionData(
                                    uri = uri,
                                    offset = offset,
                                    actionTitle = title,
                                    actionType = "quickfix"
                                )
                        )
                    )
                }
                null
            }
        } catch (e: Exception) {
            log.trace("Error extracting quick fix: ${e.message}")
        }
    }

    /** Collects QuickFixes from InspectionDiagnosticsProvider (does not require an open file). */
    private fun collectQuickFixesFromInspections(
        psiFile: PsiFile,
        document: Document,
        startOffset: Int,
        endOffset: Int,
        uri: String,
        actions: MutableList<CodeAction>,
        seenTitles: MutableSet<String>
    ) {
        try {
            val diagnosticsWithDescriptors = inspectionProvider.getDiagnosticsWithDescriptors(psiFile, document)

            for (item in diagnosticsWithDescriptors) {
                if (actions.size >= MAX_ACTIONS) break

                val diagStartOffset = PsiMapper.positionToOffset(document, item.diagnostic.range.start)
                val diagEndOffset = PsiMapper.positionToOffset(document, item.diagnostic.range.end)

                // Check whether the diagnostic is within the requested range.
                if (diagEndOffset < startOffset || diagStartOffset > endOffset) continue

                val fixes = item.descriptor.fixes?.filterIsInstance<LocalQuickFix>() ?: continue

                for (fix in fixes) {
                    if (actions.size >= MAX_ACTIONS) break

                    val title = fix.name
                    if (title.isNotBlank() && title !in seenTitles) {
                        seenTitles.add(title)

                        val isError = item.descriptor.highlightType == ProblemHighlightType.ERROR ||
                                item.descriptor.highlightType == ProblemHighlightType.GENERIC_ERROR

                        actions.add(
                            CodeAction(
                                title = title,
                                kind = CodeActionKinds.QUICK_FIX,
                                diagnostics = null,
                                isPreferred = isError,
                                edit = null,
                                command = null,
                                data = CodeActionData(
                                    uri = uri,
                                    offset = diagStartOffset,
                                    actionTitle = title,
                                    actionType = "inspection_quickfix"
                                )
                            )
                        )
                        log.debug("Collected inspection QuickFix: $title")
                    }
                }
            }

            log.debug("Collected ${actions.size} quick fixes from inspections")
        } catch (e: Exception) {
            log.debug("Error collecting quick fixes from inspections: ${e.message}")
        }
    }

    /** Collects intentions available at the specified position. */
    private fun collectAvailableIntentions(
        psiFile: PsiFile,
        document: Document,
        offset: Int,
        uri: String,
        actions: MutableList<CodeAction>,
        seenTitles: MutableSet<String>
    ) {
        // Creating an Editor and checking intentions must run on the EDT.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            var tempEditor: Editor? = null
            try {
                tempEditor = EditorFactory.getInstance().createEditor(document, project)
                tempEditor.caretModel.moveToOffset(offset)

                val intentionManager = IntentionManager.getInstance()

                // Get all registered intentions and check their availability.
                for (intention in intentionManager.intentionActions) {
                    if (actions.size >= MAX_ACTIONS) break

                    try {
                        if (intention.isAvailable(project, tempEditor, psiFile)) {
                            val title = intention.text
                            if (title.isNotBlank() && title !in seenTitles) {
                                seenTitles.add(title)
                                actions.add(intentionToCodeAction(intention, uri, offset))
                                log.debug("Intention available: $title")
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore intentions whose availability check fails.
                    }
                }
            } catch (e: Exception) {
                log.warn("Error collecting intentions: ${e.message}")
            } finally {
                if (tempEditor != null && !tempEditor.isDisposed) {
                    EditorFactory.getInstance().releaseEditor(tempEditor)
                }
            }
        }
    }

    /** Converts an IntentionAction to an LSP CodeAction. */
    private fun intentionToCodeAction(
        intention: IntentionAction,
        uri: String,
        offset: Int
    ): CodeAction {
        val kind = determineCodeActionKind(intention)

        return CodeAction(
            title = intention.text,
            kind = kind,
            diagnostics = null,
            isPreferred = isPreferredAction(intention),
            edit = null,
            command = null,
            data =
                CodeActionData(
                    uri = uri,
                    offset = offset,
                    actionTitle = intention.text,
                    actionType = "intention"
                )
        )
    }

    /** Determines the CodeAction kind from the intention type. */
    private fun determineCodeActionKind(intention: IntentionAction): String {
        val className = intention.javaClass.simpleName.lowercase()
        val text = intention.text.lowercase()

        return when {
            className.contains("fix") || className.contains("quick") -> CodeActionKinds.QUICK_FIX
            text.contains("fix") || text.contains("suppress") -> CodeActionKinds.QUICK_FIX
            text.contains("extract") -> CodeActionKinds.REFACTOR_EXTRACT
            text.contains("inline") -> CodeActionKinds.REFACTOR_INLINE
            text.contains("convert") ||
                    text.contains("change") ||
                    text.contains("replace") ||
                    text.contains("remove") -> CodeActionKinds.REFACTOR_REWRITE

            text.contains("import") && (text.contains("organize") || text.contains("optimize")) ->
                CodeActionKinds.SOURCE_ORGANIZE_IMPORTS

            className.contains("refactor") || text.contains("refactor") -> CodeActionKinds.REFACTOR
            else -> CodeActionKinds.QUICK_FIX
        }
    }

    /** Checks whether the Intention should be marked as preferred. */
    private fun isPreferredAction(intention: IntentionAction): Boolean {
        val text = intention.text.lowercase()
        return text.contains("fix") && !text.contains("suppress")
    }
}
