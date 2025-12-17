package com.frenchef.intellijlsp.language

import com.frenchef.intellijlsp.language.go.GoLanguageHandler
import com.frenchef.intellijlsp.language.js.JavaScriptLanguageHandler
import com.frenchef.intellijlsp.language.rust.RustLanguageHandler
import com.frenchef.intellijlsp.language.xml.XmlLanguageHandler

import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiFile

/**
 * Registry for registering and looking up language handlers.
 * 
 * Looks up a handler for a file in priority order.
 * The JVM handler takes precedence; the generic handler is the fallback.
 */
object LanguageHandlerRegistry {
    private val log = logger<LanguageHandlerRegistry>()
    private val handlers = mutableListOf<LanguageHandler>()
    private val genericHandler = GenericLanguageHandler()

    init {
        // Register default handlers in priority order.
        register(JvmLanguageHandler())
        register(JavaScriptLanguageHandler())
        register(GoLanguageHandler())
        register(RustLanguageHandler())
        register(XmlLanguageHandler())
        // GenericLanguageHandler is the fallback and is not added to the list.
    }

    /**
     * Registers a language handler.
     * Newly registered handlers take precedence over existing ones.
     */
    fun register(handler: LanguageHandler) {
        handlers.add(0, handler)
        log.info("Registered LanguageHandler: ${handler.javaClass.simpleName}")
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
