package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.util.LspLogger
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background code analyzer.
 * 
 * Triggers DaemonCodeAnalyzer for a file even if it is not open in an IDEA editor.
 * 
 * Core strategy:
 * 1. If the file is already open in an editor, call DaemonCodeAnalyzer.restart() directly.
 * 2. Otherwise, open the file in the background and trigger analysis.
 * 
 * Note: This class must access FileEditorManager on the EDT (Event Dispatch Thread).
 */
class BackgroundAnalyzer(private val project: Project) {
    private val log = logger<BackgroundAnalyzer>()

    // Track files currently being analyzed.
    private val analyzingFiles = ConcurrentHashMap<String, AnalysisState>()

    private data class AnalysisState(
        val inProgress: AtomicBoolean = AtomicBoolean(true),
        val wasOpenedByUs: AtomicBoolean = AtomicBoolean(false)
    )

    companion object {
        private const val TAG = "BackgroundAnalyzer"

        // Analysis timeout in milliseconds.
        private const val ANALYSIS_TIMEOUT_MS = 5000L

        // Delay before closing the editor after analysis, in milliseconds.
        private const val CLOSE_DELAY_MS = 2000L
    }

    /**
     * Ensures the file is open in an IntelliJ editor to trigger DaemonCodeAnalyzer.
     * 
     * @param virtualFile File to analyze.
     * @param uri File URI (used for logging and tracking).
     * @return true if the file is ready for analysis.
     */
    fun ensureFileOpenForAnalysis(virtualFile: VirtualFile, uri: String): Boolean {
        // Check whether the file is already open in an editor.
        val isAlreadyOpen = ApplicationManager.getApplication().runReadAction<Boolean> {
            FileEditorManager.getInstance(project).isFileOpen(virtualFile)
        }

        if (isAlreadyOpen) {
            LspLogger.debug(TAG, "File is already open in an editor: $uri")
            triggerAnalysis(virtualFile, uri)
            return true
        }

        // The file is not open; open it in the background.
        LspLogger.info(TAG, "Opening file in the background to trigger analysis: $uri")

        val state = analyzingFiles.getOrPut(uri) { AnalysisState() }

        try {
            // Open the file asynchronously on the EDT (invokeLater does not block the calling thread).
            ApplicationManager.getApplication().invokeLater {
                try {
                    if (project.isDisposed) return@invokeLater

                    // Open the file in the background without taking focus.
                    OpenFileDescriptor(project, virtualFile)
                    val editors = FileEditorManager.getInstance(project).openFile(
                        virtualFile,
                        false,  // Do not request focus.
                        true    // Search for an existing editor.
                    )

                    if (editors.isNotEmpty()) {
                        state.wasOpenedByUs.set(true)
                        LspLogger.info(TAG, "Successfully opened file in the background: $uri")

                        // Trigger analysis.
                        triggerAnalysis(virtualFile, uri)

                        // Keep the editor open so diagnostics can access MarkupModel.
                    } else {
                        LspLogger.warn(TAG, "Could not open file editor: $uri")
                    }

                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LspLogger.error(TAG, "Error opening file: $uri - ${e.message}")
                } finally {
                    state.inProgress.set(false)
                }
            }

            return true
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.warn("Error ensuring file open for analysis: $uri", e)
            return false
        }
    }

    /**
     * Triggers code analysis for the file asynchronously.
     */
    private fun triggerAnalysis(virtualFile: VirtualFile, uri: String) {
        // Trigger asynchronously with invokeLater to avoid blocking.
        ApplicationManager.getApplication().invokeLater {
            try {
                if (project.isDisposed) return@invokeLater

                ReadAction.run<RuntimeException> {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                    if (psiFile != null) {
                        DaemonCodeAnalyzer.getInstance(project).restart(psiFile)
                        LspLogger.info(TAG, "Triggered DaemonCodeAnalyzer.restart(): $uri")
                    } else {
                        LspLogger.warn(TAG, "Could not find PsiFile: $uri")
                    }
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LspLogger.error(TAG, "Error triggering analysis: $uri - ${e.message}")
            }
        }
    }

    /**
     * Schedules the editor to close if we opened it.
     */
    private fun scheduleEditorClose(virtualFile: VirtualFile, uri: String, state: AnalysisState) {
        // Use a coroutine to delay closing.
        CoroutineScope(Dispatchers.Default).launch {
            delay(CLOSE_DELAY_MS)

            // Check whether it should be closed.
            if (state.wasOpenedByUs.get()) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        if (project.isDisposed) return@invokeLater

                        // Check whether the file is still open.
                        val editorManager = FileEditorManager.getInstance(project)
                        if (editorManager.isFileOpen(virtualFile)) {
                            // Do not close the file here because closing it clears the MarkupModel.
                            // Keep it open so later diagnostic requests can access the highlights.
                            LspLogger.debug(TAG, "Keeping file open to retain analysis results: $uri")
                        }
                    } catch (e: Exception) {
                        LspLogger.debug(TAG, "Error checking editor state: ${e.message}")
                    } finally {
                        analyzingFiles.remove(uri)
                    }
                }
            }
        }
    }

    /**
     * Waits for file analysis to finish.
     * 
     * @param uri File URI.
     * @param timeoutMs Timeout in milliseconds.
     * @return true if analysis finishes before the timeout.
     */
    fun waitForAnalysis(uri: String, timeoutMs: Long = ANALYSIS_TIMEOUT_MS): Boolean {
        val state = analyzingFiles[uri] ?: return true // No analysis is in progress.

        val startTime = System.currentTimeMillis()
        while (state.inProgress.get()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                LspLogger.warn(TAG, "Timed out waiting for analysis: $uri")
                return false
            }
            Thread.sleep(100)
        }
        return true
    }

    /**
     * Gets highlight information for a file.
     */
    fun getHighlights(
        virtualFile: VirtualFile,
        startOffset: Int = 0,
        endOffset: Int = Int.MAX_VALUE
    ): List<HighlightInfo> {
        return ReadAction.compute<List<HighlightInfo>, RuntimeException> {
            try {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return@compute emptyList()
                val actualEndOffset = endOffset.coerceAtMost(document.textLength)

                val highlights = mutableListOf<HighlightInfo>()
                DaemonCodeAnalyzerEx.processHighlights(
                    document,
                    project,
                    null,
                    startOffset,
                    actualEndOffset
                ) { info ->
                    highlights.add(info)
                    true
                }

                LspLogger.debug(TAG, "Retrieved ${highlights.size} highlights")
                highlights
            } catch (e: Exception) {
                log.debug("Error getting highlights: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Cleans up resources.
     */
    fun dispose() {
        analyzingFiles.clear()
    }
}
