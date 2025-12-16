package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.util.LspLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache manager for decompiled files.
 *
 * Supported protocols:
 * - jar:// - Classes in JAR files (e.g. jar://...util.jar!/com/example/Foo.class).
 * - jrt:// - Classes in JDK modules (Java 9+, e.g. jrt://jdk-path!/java.base/java/lang/String.class).
 *
 * Features:
 * 1. Materializes decompiled content from jar:// or jrt:// into the .lsp-cache/ directory.
 * 2. Maintains bidirectional mappings between file:// and jar://|jrt:// URIs.
 * 3. Provides transparent conversion for all LSP handlers.
 */
object DecompileCache {
    private const val TAG = "DecompileCache"

    // Thread-safe bidirectional mappings.
    private val cacheToJar = ConcurrentHashMap<String, String>()  // file:// → jar://
    private val jarToCache = ConcurrentHashMap<String, String>()  // jar:// → file://

    // Cache directory name.
    private const val CACHE_DIR_NAME = ".lsp-cache"

    /**
     * Materializes a class file from JAR/JRT to a local file if it is not already cached.
     *
     * @param sourceUrl Source file URL. Supported formats:
     *                  - jar://...util.jar!/com/.../Project.class
     *                  - jrt://jdk-path!/java.base/java/lang/String.class
     * @param project IntelliJ Project context.
     * @return The cached file's file:// URI, or null on failure.
     */
    fun materializeIfNeeded(jarUrl: String, project: Project): String? {
        // Fast path: already cached.
        jarToCache[jarUrl]?.let { return it }

        synchronized(this) {
            // Double-check after acquiring the lock.
            jarToCache[jarUrl]?.let { return it }

            try {
                // 1. Get the VirtualFile from the JAR.
                val jarVf = VirtualFileManager.getInstance().findFileByUrl(jarUrl)
                if (jarVf == null) {
                    LspLogger.warn(TAG, "Could not find JAR VirtualFile: $jarUrl")
                    return null
                }

                // 2. Get the decompiled content (handled automatically by IntelliJ).
                // Do not use ReadAction.compute because callers are usually already in a read lock.
                // Callers must ensure a read lock if this method is called outside one.
                val psiFile = PsiManager.getInstance(project).findFile(jarVf)
                val content = psiFile?.text

                if (content.isNullOrEmpty()) {
                    LspLogger.warn(TAG, "Could not get decompiled content: $jarUrl")
                    return null
                }

                // 3. Compute the cache file path.
                val cachePath = computeCachePath(jarUrl, project)
                if (cachePath == null) {
                    LspLogger.warn(TAG, "Could not compute cache path: $jarUrl")
                    return null
                }

                // 4. Write the cached file.
                Files.createDirectories(cachePath.parent)
                Files.writeString(cachePath, content)
                LspLogger.info(TAG, "Materialized decompiled content: $jarUrl -> $cachePath")

                // 5. Refresh VFS asynchronously to avoid blocking or causing a deadlock inside the read lock.
                // LocalFileSystem will discover the written file when the cache is accessed later.
                com.intellij.openapi.application.invokeLater {
                    com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                        .refreshAndFindFileByNioFile(cachePath)
                }

                // 6. Record bidirectional mappings using normalized URIs.
                val fileUri = normalizeUri(cachePath.toUri().toString())
                cacheToJar[fileUri] = jarUrl
                jarToCache[jarUrl] = fileUri
                LspLogger.info(TAG, "Recorded mapping: $fileUri -> $jarUrl")

                return fileUri

            } catch (e: Exception) {
                LspLogger.error(TAG, "Failed to materialize decompiled content: $jarUrl - ${e.message}")
                return null
            }
        }
    }

    /**
     * Gets the original JAR VirtualFile from a cached file URI.
     *
     * @param cacheUri Cached file's file:// URI.
     * @return The VirtualFile in the original JAR, or null.
     */
    fun getOriginalVirtualFile(cacheUri: String): VirtualFile? {
        val normalizedUri = normalizeUri(cacheUri)
        val jarUrl = cacheToJar[normalizedUri]
        if (jarUrl == null) {
            LspLogger.debug(TAG, "No mapping found for cache URI: $cacheUri (normalized: $normalizedUri)")
            LspLogger.debug(TAG, "Current mapping keys: ${cacheToJar.keys.take(5)}")
            return null
        }
        return VirtualFileManager.getInstance().findFileByUrl(jarUrl)
    }

    /**
     * Gets the original JAR URL.
     *
     * @param cacheUri Cached file's file:// URI.
     * @return The original jar:// URL, or null.
     */
    fun getOriginalUrl(cacheUri: String): String? {
        return cacheToJar[normalizeUri(cacheUri)]
    }

    /**
     * Checks whether a URI refers to a cached file.
     */
    fun isCacheUri(uri: String): Boolean {
        return cacheToJar.containsKey(normalizeUri(uri))
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

        // Normalize Windows drive letters, e.g. file:///F: -> file:///f:.
        if (normalized.startsWith("file:///") && normalized.length > 9) {
            val driveLetter = normalized[8]
            if (driveLetter.isUpperCase()) {
                normalized = normalized.substring(0, 8) + driveLetter.lowercaseChar() + normalized.substring(9)
            }
        }

        return normalized
    }

    /**
     * Checks whether a URL refers to a decompilable class file (JAR or JRT).
     */
    fun isDecompilableUrl(url: String): Boolean {
        return url.startsWith("jar://") || url.startsWith("jrt://") || url.contains(".jar!")
    }

    /**
     * Checks whether a URL refers to a class file in a JAR (legacy method name).
     */
    fun isJarUrl(url: String): Boolean = isDecompilableUrl(url)

    /**
     * Computes the cache file path.
     *
     * Supported formats:
     * - jar://.../foo.jar!/com/example/Foo.class
     * - jrt://jdk-path!/java.base/java/lang/String.class
     *
     * Output: <project>/.lsp-cache/<source-hash>/<package>/<ClassName>.java
     */
    private fun computeCachePath(sourceUrl: String, project: Project): Path? {
        val projectBase = project.basePath ?: return null

        val (sourcePath, classPath) = when {
            sourceUrl.startsWith("jrt://") -> {
                // JRT URL: jrt://jdk-path!/module/package/Class.class
                val parts = sourceUrl.split("!/")
                if (parts.size != 2) return null
                val jdkPath = parts[0].removePrefix("jrt://")
                val moduleAndClass = parts[1]
                // Remove the module prefix (e.g. java.base/) to get the class path.
                val classPathPart = if (moduleAndClass.contains("/")) {
                    moduleAndClass.substringAfter("/")
                } else {
                    moduleAndClass
                }
                Pair(jdkPath, classPathPart)
            }

            sourceUrl.startsWith("jar://") || sourceUrl.contains(".jar!") -> {
                // JAR URL: jar://.../foo.jar!/com/example/Foo.class
                val parts = sourceUrl.split("!/")
                if (parts.size != 2) return null
                val jarPath = parts[0].removePrefix("jar://")
                Pair(jarPath, parts[1])
            }

            else -> return null
        }

        // Use a hash of the source path as the directory name to avoid long paths.
        val sourceHash = md5Short(sourcePath)

        // Convert the class path, e.g. com/example/Foo.class -> com/example/Foo.java.
        val javaPath = classPath
            .removeSuffix(".class")
            .plus(".java")

        return Path.of(projectBase, CACHE_DIR_NAME, sourceHash, javaPath)
    }

    /**
     * Computes a short MD5 hash.
     */
    private fun md5Short(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /**
     * Clears the project's decompilation cache.
     */
    fun clearCache(project: Project) {
        val projectBase = project.basePath ?: return
        val cacheDir = Path.of(projectBase, CACHE_DIR_NAME)

        try {
            if (Files.exists(cacheDir)) {
                Files.walk(cacheDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
                LspLogger.info(TAG, "Cleared decompilation cache: $cacheDir")
            }

            // Remove this project's entries from the mapping table.
            val prefix = cacheDir.toUri().toString()
            cacheToJar.keys.filter { it.startsWith(prefix) }.forEach {
                val jarUrl = cacheToJar.remove(it)
                jarUrl?.let { jarToCache.remove(it) }
            }

        } catch (e: Exception) {
            LspLogger.warn(TAG, "Failed to clear cache: $cacheDir - ${e.message}")
        }
    }
}
