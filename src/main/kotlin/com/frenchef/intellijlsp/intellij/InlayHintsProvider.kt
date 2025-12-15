package com.frenchef.intellijlsp.intellij

import com.frenchef.intellijlsp.protocol.models.InlayHint
import com.frenchef.intellijlsp.protocol.models.InlayHintKinds
import com.frenchef.intellijlsp.protocol.models.Position
import com.frenchef.intellijlsp.protocol.models.Range
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.uast.*

/**
 * Inlay Hints implemented with UAST.
 *
 * Provides unified type inference and parameter name hints across Java and Kotlin.
 */
class InlayHintsProvider(private val project: Project) {
    private val log = logger<InlayHintsProvider>()

    private companion object {
        const val MAX_HINTS = 500
    }

    /** Gets Inlay Hints for the given range. */
    fun getInlayHints(psiFile: PsiFile, range: Range): List<InlayHint> {
        return ReadAction.compute<List<InlayHint>, RuntimeException> {
            try {
                val document =
                    PsiDocumentManager.getInstance(project).getDocument(psiFile)
                        ?: return@compute emptyList()

                val startOffset = PsiMapper.positionToOffset(document, range.start)
                val endOffset = PsiMapper.positionToOffset(document, range.end)

                val hints = mutableListOf<InlayHint>()

                // Traverse PSI elements in the range and convert them to UAST.
                collectHintsFromPsi(psiFile, startOffset, endOffset, document, hints)

                log.info("Found ${hints.size} inlay hints")
                hints.take(MAX_HINTS)
            } catch (e: Exception) {
                log.warn("Error getting inlay hints", e)
                emptyList()
            }
        }
    }

    /** Collects Inlay Hints from PSI and processes them uniformly with UAST. */
    private fun collectHintsFromPsi(
        psiFile: PsiFile,
        startOffset: Int,
        endOffset: Int,
        document: com.intellij.openapi.editor.Document,
        hints: MutableList<InlayHint>
    ) {
        // Collect parameter name hints for method calls.
        PsiTreeUtil.collectElements(psiFile) { element ->
            val range = element.textRange
            range != null && range.startOffset >= startOffset && range.endOffset <= endOffset
        }
            .forEach { element ->
                if (hints.size >= MAX_HINTS) return@forEach

                // Convert with UAST.
                when (val uElement = element.toUElement()) {
                    is UCallExpression -> collectParameterHints(uElement, document, hints)
                    is UVariable -> {
                        if (shouldShowTypeHint(uElement)) {
                            collectTypeHint(uElement, document, hints)
                        }
                    }
                }
            }
    }

    /** Checks whether a type hint should be shown. */
    private fun shouldShowTypeHint(node: UVariable): Boolean {
        // Show a type hint for variables with an initializer.
        return node.uastInitializer != null
    }

    /** Collects parameter name hints. */
    private fun collectParameterHints(
        call: UCallExpression,
        document: com.intellij.openapi.editor.Document,
        hints: MutableList<InlayHint>
    ) {
        val method = call.resolve() ?: return
        val uMethod = method.toUElement() as? UMethod ?: return
        val parameters = uMethod.uastParameters
        val arguments = call.valueArguments

        for ((index, arg) in arguments.withIndex()) {
            if (index >= parameters.size) break
            if (hints.size >= MAX_HINTS) break

            val param = parameters[index]
            val paramName = param.name ?: continue

            // Skip named arguments.
            if (isNamedArgument(arg)) continue

            val argPsi = arg.sourcePsi ?: continue
            val argOffset = argPsi.textRange?.startOffset ?: continue

            val line = document.getLineNumber(argOffset)
            val char = argOffset - document.getLineStartOffset(line)

            hints.add(
                InlayHint(
                    position = Position(line = line, character = char),
                    label = "$paramName:",
                    kind = InlayHintKinds.PARAMETER,
                    paddingLeft = false,
                    paddingRight = true
                )
            )
        }
    }

    /** Checks whether this is a named argument. */
    private fun isNamedArgument(arg: UExpression): Boolean {
        // Simplified implementation: check whether the source PSI contains an identifier before "=".
        val sourcePsi = arg.sourcePsi ?: return false
        val text = sourcePsi.text
        return text.contains("=") && !text.startsWith("=")
    }

    /** Collects type hints. */
    private fun collectTypeHint(
        variable: UVariable,
        document: com.intellij.openapi.editor.Document,
        hints: MutableList<InlayHint>
    ) {
        val sourcePsi = variable.sourcePsi ?: return
        val nameIdentifier = (sourcePsi as? PsiNameIdentifierOwner)?.nameIdentifier ?: return
        val endOffset = nameIdentifier.textRange?.endOffset ?: return

        val type = variable.type
        val typeText = type.presentableText

        // Skip explicit type declarations.
        if (hasExplicitType(variable)) return

        val line = document.getLineNumber(endOffset)
        val char = endOffset - document.getLineStartOffset(line)

        hints.add(
            InlayHint(
                position = Position(line = line, character = char),
                label = ": $typeText",
                kind = InlayHintKinds.TYPE,
                paddingLeft = false,
                paddingRight = true
            )
        )
    }

    /** Checks whether the variable has an explicit type declaration. */
    private fun hasExplicitType(variable: UVariable): Boolean {
        val sourcePsi = variable.sourcePsi ?: return true
        val text = sourcePsi.text
        // Simplified check: look for type declaration syntax in the source.
        return text.contains(":") && !text.startsWith(":")
    }
}
