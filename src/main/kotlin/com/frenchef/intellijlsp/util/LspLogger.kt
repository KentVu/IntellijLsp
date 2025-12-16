package com.frenchef.intellijlsp.util

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * LSP logging manager.
 * 
 * Features:
 * 1. Writes logs to a file for external inspection.
 * 2. Keeps an in-memory log queue for the Tool Window.
 * 3. Supports different log levels.
 */
object LspLogger {
    private val log = logger<LspLogger>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")

    // Log queue for the Tool Window.
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private const val MAX_QUEUE_SIZE = 1000

    // Log file.
    private var logFile: File? = null
    private var logWriter: PrintWriter? = null

    // Log listeners.
    private val listeners = mutableListOf<(LogEntry) -> Unit>()

    data class LogEntry(
        val timestamp: Date,
        val level: LogLevel,
        val category: String,
        val message: String
    ) {
        fun format(): String {
            return "[${dateFormat.format(timestamp)}] [$level] [$category] $message"
        }
    }

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * Initializes the log file.
     * @param project Project instance (used to determine the log path).
     */
    @Synchronized
    fun init(project: Project) {
        try {
            // Get the custom path from settings.
            val settings = com.frenchef.intellijlsp.config.LspSettings.getInstance()
            val customPath = settings.logFilePath

            // Determine the log directory.
            val logDir = if (customPath.isNotBlank()) {
                File(customPath)
            } else {
                // Default to the user's home directory.
                File(System.getProperty("user.home"), ".intellij-lsp-logs")
            }

            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            val projectName = project.name.replace("[^a-zA-Z0-9]".toRegex(), "_")
            logFile = File(logDir, "lsp-${projectName}.log")

            // Open in append mode.
            logWriter = PrintWriter(FileWriter(logFile, true), true)

            info("LspLogger", "=".repeat(80))
            info("LspLogger", "LSP Logger initialized for project: ${project.name}")
            info("LspLogger", "Log file: ${logFile?.absolutePath}")
            info("LspLogger", "=".repeat(80))

            log.info("LSP log file created at: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            log.error("Failed to initialize LspLogger", e)
        }
    }

    /**
     * Closes the log.
     */
    @Synchronized
    fun close() {
        try {
            logWriter?.close()
            logWriter = null
            logFile = null
        } catch (e: Exception) {
            log.warn("Error closing LspLogger", e)
        }
    }

    /**
     * Gets the log file path.
     */
    fun getLogFilePath(): String? = logFile?.absolutePath

    /**
     * Adds a log listener (for the Tool Window).
     */
    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Removes a log listener.
     */
    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Gets the most recent log entries.
     */
    fun getRecentLogs(): List<LogEntry> = logQueue.toList()

    /**
     * Clears the log queue.
     */
    fun clearLogs() {
        logQueue.clear()
    }

    // Logging methods.
    fun debug(category: String, message: String) = write(LogLevel.DEBUG, category, message)
    fun info(category: String, message: String) = write(LogLevel.INFO, category, message)
    fun warn(category: String, message: String) = write(LogLevel.WARN, category, message)
    fun error(category: String, message: String) = write(LogLevel.ERROR, category, message)

    private fun write(level: LogLevel, category: String, message: String) {
        val entry = LogEntry(Date(), level, category, message)

        // Add to the queue.
        logQueue.add(entry)
        while (logQueue.size > MAX_QUEUE_SIZE) {
            logQueue.poll()
        }

        // Write to the file.
        try {
            logWriter?.println(entry.format())
        } catch (e: Exception) {
            // Ignore write errors.
        }

        // Notify listeners.
        listeners.forEach {
            try {
                it(entry)
            } catch (_: Exception) {
            }
        }

        // Also write to the IDEA log.
        when (level) {
            LogLevel.DEBUG -> log.debug("[LSP:$category] $message")
            LogLevel.INFO -> log.info("[LSP:$category] $message")
            LogLevel.WARN -> log.warn("[LSP:$category] $message")
            LogLevel.ERROR -> log.error("[LSP:$category] $message")
        }
    }
}
