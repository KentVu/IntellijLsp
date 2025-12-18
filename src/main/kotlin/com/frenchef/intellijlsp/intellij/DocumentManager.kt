package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.protocol.models.TextDocumentContentChangeEvent
import com.frenchef.intellijlsp.util.LspUriUtil
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages document synchronization between LSP client and IntelliJ.
 * Tracks document versions and applies incremental changes.
 */
class DocumentManager(private val project: Project) {
    private val log = logger<DocumentManager>()

    // Map of URI to document info
    private val documents = ConcurrentHashMap<String, DocumentInfo>()

    /**
     * Open a document and track it.
     */
    fun openDocument(uri: String, languageId: String, version: Int, text: String) {
        log.info("Opening document: $uri (version $version)")

        val docInfo = DocumentInfo(
            uri = uri,
            languageId = languageId,
            version = version,
            content = text
        )

        documents[uri] = docInfo
    }

    /**
     * Close a document and stop tracking it.
     */
    fun closeDocument(uri: String) {
        log.info("Closing document: $uri")
        documents.remove(uri)
    }

    /**
     * Apply incremental changes to a document.
     */
    fun changeDocument(uri: String, version: Int, changes: List<TextDocumentContentChangeEvent>) {
        val docInfo = documents[uri]

        if (docInfo == null) {
            log.warn("Attempted to change unopened document: $uri")
            return
        }

        log.debug("Changing document: $uri (version $version, ${changes.size} changes)")

        var content = docInfo.content

        for (change in changes) {
            content = if (change.range == null) {
                // Full document update
                change.text
            } else {
                // Incremental update
                applyIncrementalChange(content, change)
            }
        }

        documents[uri] = docInfo.copy(version = version, content = content)
    }

    /**
     * Save a document (trigger IntelliJ VFS refresh).
     */
    fun saveDocument(uri: String) {
        log.info("Saving document: $uri")

        val physicalFile = getPhysicalVirtualFile(uri)
        if (physicalFile != null) {
            physicalFile.refresh(false, false)
        }
    }

    /**
     * Get the current version of a document.
     */
    fun getDocumentVersion(uri: String): Int? {
        return documents[uri]?.version
    }

    /**
     * Get the content of a tracked document.
     */
    fun getDocumentContent(uri: String): String? {
        return documents[uri]?.content
    }

    /**
     * Check if a document is tracked.
     */
    fun isDocumentOpen(uri: String): Boolean {
        return documents.containsKey(uri)
    }

    /**
     * Get the VirtualFile for a URI.
     *
     * Supports reverse lookup for decompilation cache URIs:
     * if the URI refers to a cached file, returns the VirtualFile from the original JAR.
     */
    fun getVirtualFile(uri: String): VirtualFile? {
        val normalized = LspUriUtil.normalize(uri)

        return try {
            // Check first whether this is a decompilation cache URI and return the original JAR VirtualFile.
            val originalVf = DecompileCache.getOriginalVirtualFile(normalized)
            if (originalVf != null) {
                log.debug("Reverse lookup for cache URI: $normalized -> ${originalVf.url}")
                return originalVf
            }

            // For regular files, the client may send file:///f%3A/...; decode it before passing it to VFS.
            val path = URI(normalized).path
            VirtualFileManager.getInstance().findFileByUrl("file://$path")
        } catch (e: Exception) {
            log.warn("Failed to get VirtualFile for URI: $uri (normalized: $normalized)", e)
            null
        }
    }

    /**
     * Get the IntelliJ Document for a URI.
     *
     * Note: For decompiled cache files, this must return the Document for the cached file on disk,
     * not the JAR VirtualFile resolved by cache->JAR lookup (JAR files often have no Document).
     */
    fun getIntellijDocument(uri: String): Document? {
        val physicalFile = getPhysicalVirtualFile(uri) ?: return null

        return ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(physicalFile)
        }
    }

    /**
     * Gets the physical file on disk without performing cache->JAR lookup.
     */
    private fun getPhysicalVirtualFile(uri: String): VirtualFile? {
        val normalized = LspUriUtil.normalize(uri)

        return try {
            val localPath = LspUriUtil.toLocalPath(normalized) ?: return null

            // First try to find in VFS (fast)
            var file = LocalFileSystem.getInstance().findFileByPath(localPath)

            // If not found, try to refresh and find (slower but handles new files)
            if (file == null) {
                file = LocalFileSystem.getInstance().refreshAndFindFileByPath(localPath)
            }

            file
        } catch (e: Exception) {
            log.warn("Failed to get physical VirtualFile for URI: $uri (normalized: $normalized)", e)
            null
        }
    }

    /**
     * Apply an incremental change to document content.
     */
    private fun applyIncrementalChange(content: String, change: TextDocumentContentChangeEvent): String {
        val range = change.range ?: return change.text

        val lines = content.split("\n")
        val startLine = range.start.line
        val startChar = range.start.character
        val endLine = range.end.line
        val endChar = range.end.character

        if (startLine < 0 || startLine >= lines.size) {
            log.warn("Invalid start line: $startLine (document has ${lines.size} lines)")
            return content
        }

        if (endLine < 0 || endLine >= lines.size) {
            log.warn("Invalid end line: $endLine (document has ${lines.size} lines)")
            return content
        }

        // Build the new content
        val result = StringBuilder()

        // Add lines before the change
        for (i in 0 until startLine) {
            result.append(lines[i])
            if (i < lines.size - 1) result.append("\n")
        }

        // Add the part of the start line before the change
        if (startLine < lines.size) {
            val startLineContent = lines[startLine]
            if (startChar <= startLineContent.length) {
                result.append(startLineContent.substring(0, startChar))
            }
        }

        // Add the new text
        result.append(change.text)

        // Add the part of the end line after the change
        if (endLine < lines.size) {
            val endLineContent = lines[endLine]
            if (endChar <= endLineContent.length) {
                result.append(endLineContent.substring(endChar))
            }
        }

        // Add lines after the change
        for (i in endLine + 1 until lines.size) {
            result.append("\n")
            result.append(lines[i])
        }

        return result.toString()
    }

    /**
     * Information about a tracked document.
     */
    private data class DocumentInfo(
        val uri: String,
        val languageId: String,
        val version: Int,
        val content: String
    )
}
