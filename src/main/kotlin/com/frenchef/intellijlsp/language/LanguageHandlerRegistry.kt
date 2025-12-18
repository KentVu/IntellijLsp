package com.frenchef.intellijlsp.language

import com.frenchef.intellijlsp.language.xml.XmlLanguageHandler
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.PsiFile

/**
 * Registry for registering and looking up language handlers.
 *
 * Looks up a handler for a file in priority order.
 * The JVM handler takes precedence; the generic handler is the fallback.
 *
 * Uses lazy loading: handlers are instantiated only when their language plugins are present,
 * avoiding ClassNotFoundException in IDEs without the Go/Rust/JS plugins.
 */
object LanguageHandlerRegistry {
    private val log = logger<LanguageHandlerRegistry>()
    private val handlers = mutableListOf<LanguageHandler>()
    private val genericHandler = GenericLanguageHandler()

    // PluginId mapping for language plugins.
    private object PluginIds {
        const val GO = "org.jetbrains.plugins.go"
        const val RUST = "org.rust.lang"
        const val JAVASCRIPT = "JavaScript"
    }

    init {
        // Register default handlers in priority order (earlier entries have higher priority).
        // JVM and XML are built into the platform and need no checks.
        registerDefault(JvmLanguageHandler())
        registerDefault(XmlLanguageHandler())

        // Register optional language handlers only when their plugins are present.
        tryRegisterOptionalHandler(PluginIds.JAVASCRIPT) {
            Class.forName("com.frenchef.intellijlsp.language.js.JavaScriptLanguageHandler")
                .getDeclaredConstructor().newInstance() as LanguageHandler
        }

        tryRegisterOptionalHandler(PluginIds.GO) {
            Class.forName("com.frenchef.intellijlsp.language.go.GoLanguageHandler")
                .getDeclaredConstructor().newInstance() as LanguageHandler
        }

        tryRegisterOptionalHandler(PluginIds.RUST) {
            Class.forName("com.frenchef.intellijlsp.language.rust.RustLanguageHandler")
                .getDeclaredConstructor().newInstance() as LanguageHandler
        }

        // GenericLanguageHandler is the fallback and is not added to the list.
    }

    /**
     * Registers a language handler.
     * Newly registered handlers take precedence over existing ones.
     */
    fun register(handler: LanguageHandler) {
        handlers.add(0, handler)
        log.info("Registered LanguageHandler (high priority): ${handler.javaClass.simpleName}")
    }

    /**
     * Registers default language handlers.
     * Registration order determines priority (the first registered handler takes precedence).
     */
    private fun registerDefault(handler: LanguageHandler) {
        handlers.add(handler)
        log.info("Registered LanguageHandler (default): ${handler.javaClass.simpleName}")
    }

    /**
     * Attempts to register handlers for optional language plugins.
     * A handler is instantiated only if its plugin is installed and enabled.
     */
    private fun tryRegisterOptionalHandler(pluginId: String, factory: () -> LanguageHandler) {
        try {
            val plugin = PluginManagerCore.getPlugin(PluginId.getId(pluginId))
            if (plugin != null && plugin.isEnabled) {
                val handler = factory()
                handlers.add(handler)
                log.info("Registered optional LanguageHandler: ${handler.javaClass.simpleName} (plugin: $pluginId)")
            } else {
                log.info("Skipped LanguageHandler for plugin $pluginId (not installed or disabled)")
            }
        } catch (e: ClassNotFoundException) {
            log.info("Skipped LanguageHandler for plugin $pluginId (class not found: ${e.message})")
        } catch (e: NoClassDefFoundError) {
            log.info("Skipped LanguageHandler for plugin $pluginId (dependency not found: ${e.message})")
        } catch (e: Exception) {
            log.warn("Failed to register LanguageHandler for plugin $pluginId: ${e.message}")
        }
    }

    /**
     * Gets the handler for the specified file.
     * Returns the generic handler if no specialized handler applies.
     */
    fun getHandler(file: PsiFile): LanguageHandler {
        val handler = handlers.find { it.supports(file) }
        if (handler != null) {
            log.debug("Using ${handler.javaClass.simpleName} for ${file.language.id}")
            return handler
        }
        log.debug("Using GenericLanguageHandler (fallback) for ${file.language.id}")
        return genericHandler
    }

    /**
     * Checks whether a specialized handler supports the file.
     */
    fun hasSpecializedHandler(file: PsiFile): Boolean {
        return handlers.any { it.supports(file) }
    }
}
