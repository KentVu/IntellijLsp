package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.language.LanguageHandlerRegistry
import com.frenchef.intellijlsp.protocol.models.ParameterInformation
import com.frenchef.intellijlsp.protocol.models.SignatureHelp
import com.frenchef.intellijlsp.protocol.models.SignatureInformation
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Signature Help provider.
 *
 * Supports multiple languages through the LanguageHandler abstraction.
 * Provides full functionality for JVM languages and safely degrades for others.
 */
class SignatureHelpProvider(private val project: Project) {
    private val log = logger<SignatureHelpProvider>()

    /** Gets Signature Help information for the given position. */
    fun getSignatureHelp(psiFile: PsiFile, document: Document, offset: Int): SignatureHelp? {
        return ReadAction.compute<SignatureHelp?, RuntimeException> {
            try {
                val element = psiFile.findElementAt(offset) ?: return@compute null

                val handler = LanguageHandlerRegistry.getHandler(psiFile)

                // Find the call expression containing the element.
                val callExpression = handler.findContainingCallExpression(element)
                if (callExpression == null) {
                    log.debug("No call expression found at offset $offset")
                    return@compute null
                }

                // Get signature information.
                val signatureInfo = handler.getSignatureInfo(callExpression)
                if (signatureInfo == null) {
                    log.debug("No signature info available for call expression")
                    return@compute null
                }

                // Calculate the active parameter index.
                val activeParameter = calculateActiveParameter(handler, callExpression, offset)

                // Convert to LSP SignatureInformation.
                val lspSignature = SignatureInformation(
                    label = signatureInfo.label,
                    documentation = signatureInfo.documentation,
                    parameters = signatureInfo.parameters.map { param ->
                        ParameterInformation(
                            label = "${param.name}: ${param.type}",
                            documentation = param.documentation
                        )
                    },
                    activeParameter = null
                )

                SignatureHelp(
                    signatures = listOf(lspSignature),
                    activeSignature = 0,
                    activeParameter = activeParameter
                )
            } catch (e: Exception) {
                log.warn("Error getting signature help at offset $offset", e)
                null
            }
        }
    }

    /** Calculates the active parameter index. */
    private fun calculateActiveParameter(
        handler: com.frenchef.intellijlsp.language.LanguageHandler,
        callExpression: com.frenchef.intellijlsp.language.CallExpressionInfo,
        offset: Int
    ): Int {
        val args = handler.getCallArgumentsInfo(callExpression)
        if (args.isEmpty()) return 0

        for (arg in args) {
            val textRange = arg.psiElement.textRange ?: continue
            if (offset <= textRange.endOffset) {
                return arg.index
            }
        }

        // If the offset is after all arguments, return the last argument's index (0-based).
        return (args.size - 1).coerceAtLeast(0)
    }
}
