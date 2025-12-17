package com.frenchef.intellijlsp.language

import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * JVM language handler - uses UAST to support Java/Kotlin/Groovy/Scala.
 * 
 * Encapsulates UAST-specific logic so providers/handlers do not depend directly on the UAST API.
 */
class JvmLanguageHandler : LanguageHandler {
    private val log = logger<JvmLanguageHandler>()

    companion object {
        private val SUPPORTED_LANGUAGES = setOf("JAVA", "kotlin", "Groovy", "Scala")
    }

    override fun supports(file: PsiFile): Boolean {
        return file.language.id in SUPPORTED_LANGUAGES
    }

    override fun findContainingFunction(element: PsiElement): FunctionInfo? {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            // Try converting to UAST.
            val uElement = current.toUElement()
            if (uElement is UMethod) {
                return uMethodToFunctionInfo(uElement)
            }
            // Fall back to plain Java PSI.
            if (current is PsiMethod) {
                return psiMethodToFunctionInfo(current)
            }
            current = current.parent
        }
        return null
    }

    override fun findContainingClass(element: PsiElement): ClassInfo? {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            val uElement = current.toUElement()
            if (uElement is UClass) {
                return psiClassToClassInfo(uElement.javaPsi)
            }
            if (current is PsiClass) {
                return psiClassToClassInfo(current)
            }
            current = current.parent
        }
        return null
    }

    override fun getCallExpressions(function: FunctionInfo): List<CallExpressionInfo> {
        val results = mutableListOf<CallExpressionInfo>()

        val uMethod = function.psiElement.toUElement() as? UMethod
        if (uMethod == null) {
            log.debug("Cannot convert to UMethod: ${function.name}")
            return results
        }

        uMethod.accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                val sourcePsi = node.sourcePsi ?: return false
                val resolved = node.resolve()

                results.add(
                    CallExpressionInfo(
                    psiElement = sourcePsi,
                    methodName = node.methodName ?: "<unknown>",
                    resolvedTarget = resolved?.let { psiMethodToFunctionInfo(it) }
                ))
                return false // Continue traversal.
            }
        })

        return results
    }

    override fun getSuperTypes(classInfo: ClassInfo): List<ClassInfo> {
        val psiClass = classInfo.psiElement as? PsiClass ?: return emptyList()
        return psiClass.supers.mapNotNull { superClass ->
            // Filter out java.lang.Object unless explicitly needed.
            if (superClass.qualifiedName == "java.lang.Object") null
            else psiClassToClassInfo(superClass)
        }
    }

    override fun getSubTypes(classInfo: ClassInfo): List<ClassInfo> {
        val psiClass = classInfo.psiElement as? PsiClass ?: return emptyList()
        val project = psiClass.project
        val scope = GlobalSearchScope.projectScope(project)

        return try {
            ClassInheritorsSearch.search(psiClass, scope, false)
                .findAll()
                .take(100) // Limit the number of results.
                .map { psiClassToClassInfo(it) }
        } catch (e: Exception) {
            log.warn("Error searching for subtypes of ${classInfo.name}", e)
            emptyList()
        }
    }

    override fun resolveReference(element: PsiElement): PsiElement? {
        // Try to resolve the reference.
        val reference = element.reference ?: return null
        return reference.resolve()
    }

    override fun findContainingCallExpression(element: PsiElement): CallExpressionInfo? {
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            val uElement = current.toUElement()
            if (uElement is UCallExpression) {
                val resolved = uElement.resolve()
                return CallExpressionInfo(
                    psiElement = current,
                    methodName = uElement.methodName ?: "<unknown>",
                    resolvedTarget = resolved?.let { psiMethodToFunctionInfo(it) }
                )
            }
            current = current.parent
        }
        return null
    }

    override fun getSignatureInfo(callExpression: CallExpressionInfo): SignatureInfo? {
        val uCall = callExpression.psiElement.toUElement() as? UCallExpression ?: return null
        val resolved = uCall.resolve() ?: return null
        val uMethod = resolved.toUElement() as? UMethod ?: return null

        val params = uMethod.uastParameters.map { param ->
            ParameterInfo(
                name = param.name ?: "",
                type = param.type.presentableText,
                documentation = null
            )
        }

        val paramStr = params.joinToString(", ") { "${it.name}: ${it.type}" }
        val returnType = uMethod.returnType?.presentableText ?: "Unit"
        val label = "${uMethod.name}($paramStr): $returnType"

        // Get the document.
        val documentation = getMethodDocumentation(uMethod)

        return SignatureInfo(
            label = label,
            documentation = documentation,
            parameters = params,
            returnType = returnType
        )
    }

    override fun getCallArgumentsInfo(callExpression: CallExpressionInfo): List<ArgumentInfo> {
        val uCall = callExpression.psiElement.toUElement() as? UCallExpression ?: return emptyList()
        val resolved = uCall.resolve() ?: return emptyList()
        val uMethod = resolved.toUElement() as? UMethod ?: return emptyList()

        val parameters = uMethod.uastParameters
        val arguments = uCall.valueArguments
        val results = mutableListOf<ArgumentInfo>()

        for ((index, arg) in arguments.withIndex()) {
            if (index >= parameters.size) break

            val param = parameters[index]
            val paramName = param.name ?: continue
            val argPsi = arg.sourcePsi ?: continue

            // Simplified named-argument detection.
            val argText = argPsi.text
            val isNamed = argText.contains("=") && !argText.startsWith("=")

            results.add(
                ArgumentInfo(
                    psiElement = argPsi,
                    parameterName = paramName,
                    index = index,
                    isNamed = isNamed
                )
            )
        }

        return results
    }

    /** Gets method documentation. */
    private fun getMethodDocumentation(uMethod: UMethod): String? {
        val javaPsi = uMethod.javaPsi
        val docComment = (javaPsi as? PsiDocCommentOwner)?.docComment
        if (docComment != null) {
            return docComment.text
                .replace("/**", "")
                .replace("*/", "")
                .replace(Regex("^\\s*\\*\\s?", RegexOption.MULTILINE), "")
                .trim()
        }
        return null
    }

    override fun getVariablesNeedingTypeHints(
        file: PsiFile,
        startOffset: Int,
        endOffset: Int
    ): List<VariableTypeHintInfo> {
        val results = mutableListOf<VariableTypeHintInfo>()

        PsiTreeUtil.collectElements(file) { element ->
            val range = element.textRange
            range != null && range.startOffset >= startOffset && range.endOffset <= endOffset
        }.forEach { element ->
            val uVariable = element.toUElement() as? UVariable ?: return@forEach

            // Check whether a type hint is needed.
            if (uVariable.uastInitializer == null) return@forEach
            if (hasExplicitType(uVariable)) return@forEach

            val sourcePsi = uVariable.sourcePsi ?: return@forEach
            val nameIdentifier = (sourcePsi as? PsiNameIdentifierOwner)?.nameIdentifier ?: return@forEach
            val insertOffset = nameIdentifier.textRange?.endOffset ?: return@forEach

            val typeText = uVariable.type.presentableText

            results.add(
                VariableTypeHintInfo(
                    insertOffset = insertOffset,
                    typeText = typeText,
                    hasExplicitType = false
                )
            )
        }

        return results
    }

    /** Checks whether the variable has an explicit type declaration. */
    private fun hasExplicitType(variable: UVariable): Boolean {
        val sourcePsi = variable.sourcePsi ?: return true
        val text = sourcePsi.text
        // Simplified check: look for type declaration syntax in the source.
        return text.contains(":") && !text.startsWith(":")
    }

    // ============ Conversion helper methods ============

    private fun uMethodToFunctionInfo(uMethod: UMethod): FunctionInfo {
        val javaPsi = uMethod.javaPsi
        return FunctionInfo(
            psiElement = javaPsi,
            name = javaPsi.name,
            containingClass = javaPsi.containingClass?.let { psiClassToClassInfo(it) },
            nameIdentifier = javaPsi.nameIdentifier,
            isConstructor = javaPsi.isConstructor
        )
    }

    private fun psiMethodToFunctionInfo(psiMethod: PsiMethod): FunctionInfo {
        return FunctionInfo(
            psiElement = psiMethod,
            name = psiMethod.name,
            containingClass = psiMethod.containingClass?.let { psiClassToClassInfo(it) },
            nameIdentifier = psiMethod.nameIdentifier,
            isConstructor = psiMethod.isConstructor
        )
    }

    private fun psiClassToClassInfo(psiClass: PsiClass): ClassInfo {
        return ClassInfo(
            psiElement = psiClass,
            name = psiClass.name ?: "<anonymous>",
            qualifiedName = psiClass.qualifiedName,
            nameIdentifier = psiClass.nameIdentifier
        )
    }
}
