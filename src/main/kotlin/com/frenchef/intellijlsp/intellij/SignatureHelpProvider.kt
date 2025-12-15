package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.protocol.models.ParameterInformation
import com.frenchef.intellijlsp.protocol.models.SignatureHelp
import com.frenchef.intellijlsp.protocol.models.SignatureInformation
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocCommentOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement

/**
 * Signature Help implemented with UAST.
 *
 * Provides unified function signature information across Java and Kotlin.
 */
class SignatureHelpProvider(private val project: Project) {
    private val log = logger<SignatureHelpProvider>()

    /** Gets Signature Help information for the given position. */
    fun getSignatureHelp(psiFile: PsiFile, document: Document, offset: Int): SignatureHelp? {
        return ReadAction.compute<SignatureHelp?, RuntimeException> {
            try {
                // Find the PSI element at the offset.
                val element = psiFile.findElementAt(offset) ?: return@compute null

                // Search upward for a function call expression, using UAST for uniform handling.
                val callExpression = findCallExpression(element) ?: return@compute null

                // Convert PSI to UAST.
                val uCall = callExpression.toUElement() as? UCallExpression
                if (uCall != null) {
                    return@compute getSignatureHelpFromUast(uCall, offset)
                }

                null
            } catch (e: Exception) {
                log.warn("Error getting signature help at offset $offset", e)
                null
            }
        }
    }

    /** Finds the function call expression containing the given element. */
    private fun findCallExpression(element: PsiElement): PsiElement? {
        var current: PsiElement? = element
        while (current != null) {
            // Use UAST to check whether this is a call expression.
            val uElement = current.toUElement()
            if (uElement is UCallExpression) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /** Gets Signature Help from a UAST call expression. */
    private fun getSignatureHelpFromUast(uCall: UCallExpression, offset: Int): SignatureHelp? {
        // Get the called method.
        val resolvedMethod = uCall.resolve() ?: return null
        val uMethod = resolvedMethod.toUElement() as? UMethod ?: return null

        // Build signature information.
        val signatureInfo = buildSignatureInfo(uMethod)

        // Calculate the active parameter index.
        val activeParameter = calculateActiveParameter(uCall, offset)

        return SignatureHelp(
            signatures = listOf(signatureInfo),
            activeSignature = 0,
            activeParameter = activeParameter
        )
    }

    /** Builds method signature information. */
    private fun buildSignatureInfo(uMethod: UMethod): SignatureInformation {
        val params = uMethod.uastParameters
        val paramInfos =
            params.map { param ->
                ParameterInformation(
                    label = "${param.name}: ${param.type.presentableText}",
                    documentation = null
                )
            }

        // Build the complete signature.
        val paramStr = params.joinToString(", ") { "${it.name}: ${it.type.presentableText}" }
        val returnType = uMethod.returnType?.presentableText ?: "Unit"
        val label = "${uMethod.name}($paramStr): $returnType"

        return SignatureInformation(
            label = label,
            documentation = getMethodDocumentation(uMethod),
            parameters = paramInfos,
            activeParameter = null
        )
    }

    /** Calculates the active parameter index. */
    private fun calculateActiveParameter(uCall: UCallExpression, offset: Int): Int {
        val args = uCall.valueArguments
        if (args.isEmpty()) return 0

        // Simplified implementation: estimate from the number of parameters.
        // A complete implementation must determine which parameter range contains the offset.
        for ((index, arg) in args.withIndex()) {
            val sourcePsi = arg.sourcePsi ?: continue
            val textRange = sourcePsi.textRange ?: continue
            if (offset <= textRange.endOffset) {
                return index
            }
        }

        return args.size.coerceAtMost(0)
    }

    /** Gets the method documentation. */
    private fun getMethodDocumentation(uMethod: UMethod): String? {
        val javaPsi = uMethod.javaPsi
        if (javaPsi is PsiDocCommentOwner) {
            val docComment = javaPsi.docComment
            if (docComment != null) {
                return docComment
                    .text
                    .replace("/**", "")
                    .replace("*/", "")
                    .replace(Regex("^\\s*\\*\\s?", RegexOption.MULTILINE), "")
                    .trim()
            }
        }
        return null
    }
}
