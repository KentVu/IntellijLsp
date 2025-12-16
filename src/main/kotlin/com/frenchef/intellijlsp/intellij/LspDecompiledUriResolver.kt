package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.util.LspLogger
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.net.URI

/**
 * LSP decompiled-file URI resolver.
 * 
 * Core logic:
 * - Externally (to the client), always provide file:// URIs for materialized cache files.
 * - Internally (for analysis), use jar:// VirtualFiles/PsiFiles for resolution and project-wide reference searches.
 */
object LspDecompiledUriResolver {
    private const val TAG = "UriResolver"

    /**
     * Resolution result.
     * @param clientUri URI seen by the client (file:// cache file).
     * @param analysisPsiFile PsiFile used for semantic analysis (indexed PSI from the decompiled JAR).
     * @param fileText Text used to calculate offsets (cache file text, matching the client).
     */
    data class Resolved(
        val clientUri: String,
        val analysisPsiFile: PsiFile,
        val fileText: String
    )

    /**
     * Handles an incoming request URI:
     * - For a .lsp-cache file:// URI, map to the jar:// PsiFile for semantic analysis and use the cache file text.
     * - For a direct jar:// URI, materialize it as file:// first, then handle it as above.
     * - For a regular file:// URI, return the regular PsiFile.
     */
    fun resolveForRequest(project: Project, uri: String): Resolved? {
        val normalizedUri = normalizeUri(uri)
        LspLogger.info(TAG, "Resolving request URI: $normalizedUri")

        // Case 1: A direct jar:// or jrt:// URL; materialize it first, then handle it as a cache file.
        if (DecompileCache.isDecompilableUrl(normalizedUri)) {
            LspLogger.info(TAG, "Detected decompilable URL (jar/jrt); materializing it first")
            val cacheUri = DecompileCache.materializeIfNeeded(normalizedUri, project) ?: return null
            return resolveForRequest(project, cacheUri)
        }

        // Case 2: A cache file; analyze with the original JAR PSI and calculate offsets from the cache file text.
        if (DecompileCache.isCacheUri(normalizedUri)) {
            LspLogger.info(TAG, "Detected cache URI; resolving the original JAR PSI")

            // Get the physical VirtualFile and text for the cache file.
            val cacheVf = findPhysicalFile(normalizedUri)
            val cacheText = if (cacheVf != null) {
                ReadAction.compute<String, RuntimeException> {
                    FileDocumentManager.getInstance().getDocument(cacheVf)?.text
                        ?: cacheVf.inputStream.bufferedReader().readText()
                }
            } else {
                LspLogger.warn(TAG, "Could not find physical cache file: $normalizedUri")
                return null
            }

            // Get the original JAR VirtualFile and PsiFile for semantic analysis.
            val originalVf = DecompileCache.getOriginalVirtualFile(normalizedUri)
            if (originalVf == null) {
                LspLogger.warn(TAG, "Could not resolve original JAR VirtualFile: $normalizedUri")
                return null
            }

            val analysisPsi = ReadAction.compute<PsiFile?, RuntimeException> {
                PsiManager.getInstance(project).findFile(originalVf)
            }
            if (analysisPsi == null) {
                LspLogger.warn(TAG, "Could not get original JAR PsiFile: ${originalVf.url}")
                return null
            }

            LspLogger.info(TAG, "Successfully resolved cache URI: $normalizedUri -> ${originalVf.url}")
            return Resolved(
                clientUri = normalizedUri,
                analysisPsiFile = analysisPsi,
                fileText = cacheText
            )
        }

        // Case 3: A regular file; return it directly.
        val vf = findPhysicalFile(normalizedUri) ?: return null
        val psi = ReadAction.compute<PsiFile?, RuntimeException> {
            PsiManager.getInstance(project).findFile(vf)
        } ?: return null

        val text = ReadAction.compute<String, RuntimeException> { psi.text ?: "" }

        return Resolved(
            clientUri = normalizedUri,
            analysisPsiFile = psi,
            fileText = text
        )
    }

    /**
     * Maps an outgoing navigation URI from a jar:// target to an openable file:// URI (.lsp-cache).
     */
    fun toClientUri(project: Project, targetFile: VirtualFile): String? {
        val url = targetFile.url
        return if (DecompileCache.isJarUrl(url)) {
            val cacheUri = DecompileCache.materializeIfNeeded(url, project)
            LspLogger.info(TAG, "Outgoing URI mapping: $url -> $cacheUri")
            cacheUri
        } else {
            // Regular file; use the standard file:// format.
            try {
                java.nio.file.Path.of(targetFile.path).toUri().toString()
            } catch (e: Exception) {
                url
            }
        }
    }

    /**
     * Finds the physical file's VirtualFile.
     */
    private fun findPhysicalFile(uri: String): VirtualFile? {
        return try {
            val rawPath = URI(uri).path ?: return null
            val localPath = if (rawPath.length >= 3 && rawPath[0] == '/' && rawPath[2] == ':') {
                rawPath.substring(1)
            } else {
                rawPath
            }
            LocalFileSystem.getInstance().findFileByPath(localPath)
        } catch (e: Exception) {
            LspLogger.warn(TAG, "Could not resolve URI: $uri - ${e.message}")
            null
        }
    }

    /**
     * Normalizes a URI for consistent comparison:
     * - URL-decodes characters (e.g. %3A -> :).
     * - Lowercases drive letters.
     * - Uses forward slashes.
     * - Removes trailing slashes.
     */
    private fun normalizeUri(uri: String): String {
        // URL-decode first to handle encoded characters such as %3A.
        var normalized = try {
            java.net.URLDecoder.decode(uri, "UTF-8")
        } catch (e: Exception) {
            uri
        }

        normalized = normalized
            .replace("\\", "/")
            .trimEnd('/')

        if (normalized.startsWith("file:///") && normalized.length > 9) {
            val driveLetter = normalized[8]
            if (driveLetter.isUpperCase()) {
                normalized = normalized.substring(0, 8) + driveLetter.lowercaseChar() + normalized.substring(9)
            }
        }

        return normalized
    }
}
