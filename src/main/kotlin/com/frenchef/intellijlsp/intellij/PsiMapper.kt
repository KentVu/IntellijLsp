package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.protocol.models.Location
import com.frenchef.intellijlsp.protocol.models.Position
import com.frenchef.intellijlsp.protocol.models.Range
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.net.URI
import java.nio.file.Path

/**
 * Converts between LSP types and IntelliJ PSI types.
 */
object PsiMapper {
    private val log = logger<PsiMapper>()

    /**
     * Convert LSP Position to IntelliJ offset (Document version).
     */
    fun positionToOffset(document: Document, position: Position): Int {
        return ReadAction.compute<Int, RuntimeException> {
            val line = position.line.coerceIn(0, document.lineCount - 1)
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            val offset = lineStart + position.character

            offset.coerceIn(lineStart, lineEnd)
        }
    }

    /**
     * Convert LSP Position to IntelliJ offset (text version).
     * Used for decompiled JAR files that do not have a Document.
     */
    fun positionToOffset(text: String, position: Position): Int {
        if (text.isEmpty()) return 0
        val targetLine = position.line.coerceAtLeast(0)
        val targetChar = position.character.coerceAtLeast(0)

        var line = 0
        var index = 0
        val length = text.length

        while (index < length && line < targetLine) {
            if (text[index++] == '\n') line++
        }

        val lineStart = index
        while (index < length && text[index] != '\n') index++
        val lineEnd = index

        return (lineStart + targetChar).coerceIn(lineStart, lineEnd)
    }

    /**
     * Convert IntelliJ offset to LSP Position (Document version).
     */
    fun offsetToPosition(document: Document, offset: Int): Position {
        return ReadAction.compute<Position, RuntimeException> {
            val line = document.getLineNumber(offset)
            val lineStart = document.getLineStartOffset(line)
            val character = offset - lineStart

            Position(line = line, character = character)
        }
    }

    /**
     * Convert IntelliJ offset to LSP Position (text version).
     */
    fun offsetToPosition(text: String, offset: Int): Position {
        val safeOffset = offset.coerceIn(0, text.length)
        var line = 0
        var lineStart = 0

        var i = 0
        while (i < safeOffset) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
            i++
        }

        return Position(line = line, character = safeOffset - lineStart)
    }

    /**
     * Convert IntelliJ TextRange to LSP Range (Document version).
     */
    fun textRangeToRange(document: Document, textRange: TextRange): Range {
        return ReadAction.compute<Range, RuntimeException> {
            val start = offsetToPosition(document, textRange.startOffset)
            val end = offsetToPosition(document, textRange.endOffset)

            Range(start = start, end = end)
        }
    }

    /**
     * Convert IntelliJ TextRange to LSP Range (text version).
     */
    fun textRangeToRange(text: String, textRange: TextRange): Range {
        val start = offsetToPosition(text, textRange.startOffset)
        val end = offsetToPosition(text, textRange.endOffset)
        return Range(start = start, end = end)
    }

    /**
     * Convert LSP Range to IntelliJ TextRange.
     */
    fun rangeToTextRange(document: Document, range: Range): TextRange {
        return ReadAction.compute<TextRange, RuntimeException> {
            val startOffset = positionToOffset(document, range.start)
            val endOffset = positionToOffset(document, range.end)

            TextRange(startOffset, endOffset)
        }
    }

    /**
     * Convert VirtualFile to URI string.
     *
     * @param virtualFile VirtualFile to convert.
     * @param project Optional Project context used to materialize JAR files.
     * @return Standard file:// URI; JAR files are materialized in the cache.
     */
    fun virtualFileToUri(virtualFile: VirtualFile, project: com.intellij.openapi.project.Project? = null): String? {
        val url = virtualFile.url
        // Materialize JAR/JRT files into the cache.
        if (DecompileCache.isJarUrl(url)) {
            if (project == null) {
                log.warn("JAR URI requires project context for materialization: $url")
                return null
            }
            val cacheUri = DecompileCache.materializeIfNeeded(url, project)
            if (cacheUri != null) {
                log.info("Materialized JAR URI: $url -> $cacheUri")
                return cacheUri
            }
            log.warn("Failed to materialize JAR URI: $url")
            return null
        }

        // Use Path.toUri() for a stable file:/// URI for local files.
        return try {
            if (virtualFile.isInLocalFileSystem) {
                Path.of(virtualFile.path).toUri().toString()
            } else {
                url
            }
        } catch (e: Exception) {
            log.warn("VirtualFile URI conversion failed for: $url", e)
            null
        }
    }

    /**
     * Convert PsiElement to LSP Location.
     *
     * @param element PSI element to convert.
     * @param project Optional Project context used to materialize JAR files.
     */
    fun psiElementToLocation(element: PsiElement, project: com.intellij.openapi.project.Project? = null): Location? {
        return ReadAction.compute<Location?, RuntimeException> {
            val containingFile = element.containingFile ?: return@compute null
            val virtualFile = containingFile.virtualFile ?: return@compute null
            val textRange = element.textRange ?: return@compute null

            val uri = virtualFileToUri(virtualFile, project) ?: return@compute null

            // For decompiled JAR files, do not rely on Document, which may be unavailable; calculate the Range from PSI text.
            val fileText = containingFile.text ?: return@compute null
            val range = textRangeToRange(fileText, textRange)

            Location(uri = uri, range = range)
        }
    }

    /**
     * Get the PsiElement at a specific position in a file (Document version).
     */
    fun getPsiElementAtPosition(psiFile: PsiFile, document: Document, position: Position): PsiElement? {
        return ReadAction.compute<PsiElement?, RuntimeException> {
            val offset = positionToOffset(document, position)
            psiFile.findElementAt(offset)
        }
    }

    /**
     * Get the PsiElement at a specific position in a file (text version).
     * Used for decompiled JAR files that do not have a Document.
     */
    fun getPsiElementAtPosition(psiFile: PsiFile, fileText: String, position: Position): PsiElement? {
        return ReadAction.compute<PsiElement?, RuntimeException> {
            val offset = positionToOffset(fileText, position)
            psiFile.findElementAt(offset)
        }
    }

    /**
     * Get the word range at a position (Document version).
     */
    fun getWordRangeAtPosition(document: Document, position: Position): Range? {
        return ReadAction.compute<Range?, RuntimeException> {
            val offset = positionToOffset(document, position)
            val text = document.text

            if (offset < 0 || offset >= text.length) {
                return@compute null
            }

            var start = offset
            var end = offset

            while (start > 0 && isWordCharacter(text[start - 1])) {
                start--
            }

            while (end < text.length && isWordCharacter(text[end])) {
                end++
            }

            if (start >= end) {
                return@compute null
            }

            val startPos = offsetToPosition(document, start)
            val endPos = offsetToPosition(document, end)

            Range(start = startPos, end = endPos)
        }
    }

    /**
     * Get the word range at a position (text version).
     */
    fun getWordRangeAtPosition(fileText: String, position: Position): Range? {
        val offset = positionToOffset(fileText, position)
        if (offset < 0 || offset >= fileText.length) return null

        var start = offset
        var end = offset

        while (start > 0 && isWordCharacter(fileText[start - 1])) start--
        while (end < fileText.length && isWordCharacter(fileText[end])) end++

        if (start >= end) return null

        return Range(
            start = offsetToPosition(fileText, start),
            end = offsetToPosition(fileText, end)
        )
    }

    /**
     * Check if a character is part of a word.
     */
    private fun isWordCharacter(c: Char): Boolean {
        return c.isLetterOrDigit() || c == '_'
    }

    /**
     * Get PsiFile from URI string.
     * Cache file URIs must resolve back to a jar VirtualFile to support indexing and reference searches.
     */
    fun getPsiFile(project: com.intellij.openapi.project.Project, uri: String): PsiFile? {
        return ReadAction.compute<PsiFile?, RuntimeException> {
            try {
                // Resolve the cache file URI back to a jar VirtualFile.
                val originalVf = DecompileCache.getOriginalVirtualFile(uri)
                val virtualFile =
                    originalVf ?: run {
                        val rawPath = URI(uri).path ?: return@compute null
                        val localPath =
                            if (rawPath.length >= 3 && rawPath[0] == '/' && rawPath[2] == ':') rawPath.substring(1) else rawPath
                        LocalFileSystem.getInstance().findFileByPath(localPath)
                    } ?: return@compute null

                com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            } catch (e: Exception) {
                log.warn("Failed to get PSI file for URI: $uri", e)
                null
            }
        }
    }

    /**
     * Get Document from PsiFile.
     */
    fun getDocument(psiFile: PsiFile): Document? {
        return ReadAction.compute<Document?, RuntimeException> {
            val virtualFile = psiFile.virtualFile ?: return@compute null
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                .getDocument(virtualFile)
        }
    }

    /**
     * Convert PsiElement to LSP Range within a file.
     */
    fun elementToRange(element: PsiElement, psiFile: PsiFile): Range {
        val textRange = element.textRange ?: return Range(Position(0, 0), Position(0, 0))
        val fileText = psiFile.text ?: return Range(Position(0, 0), Position(0, 0))
        return textRangeToRange(fileText, textRange)
    }

    /**
     * Convert PsiElement to LSP Location (alias for psiElementToLocation).
     */
    fun elementToLocation(element: PsiElement, project: com.intellij.openapi.project.Project? = null): Location? {
        return psiElementToLocation(element, project)
    }
}
