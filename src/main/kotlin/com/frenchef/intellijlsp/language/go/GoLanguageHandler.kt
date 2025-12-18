package com.frenchef.intellijlsp.language.go

import com.frenchef.intellijlsp.language.*
import com.goide.GoLanguage
import com.goide.psi.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/**
 * Go language handler.
 *
 * Based on the GoParser.kt implementation in draw-graph.
 */
class GoLanguageHandler : LanguageHandler {

    override fun supports(file: PsiFile): Boolean {
        return file.language.id == GoLanguage.INSTANCE.id
    }

    override fun findContainingFunction(element: PsiElement): FunctionInfo? {
        // If the element is itself a function, use it directly (do not use strict=true parent lookup).
        val func = (element as? GoFunctionOrMethodDeclaration)
            ?: PsiTreeUtil.getParentOfType(element, GoFunctionOrMethodDeclaration::class.java, false)
            ?: return null

        // Get type information for the method receiver.
        val classInfo = if (func is GoMethodDeclaration) {
            val receiver = func.receiver
            val receiverType = receiver?.type
            val receiverTypeName = receiverType?.text?.replace("*", "") ?: ""

            // Try to resolve the receiver type to an actual GoTypeSpec.
            // GoType must be resolved through a reference or another mechanism.
            val typeSpec: GoTypeSpec? = when (val type = receiverType) {
                is GoPointerType -> {
                    val innerType = type.type
                    (innerType as? GoTypeReferenceExpression)?.resolve() as? GoTypeSpec
                }

                is GoTypeReferenceExpression -> type.resolve() as? GoTypeSpec
                else -> null
            }

            if (typeSpec != null) {
                ClassInfo(
                    psiElement = typeSpec,
                    name = typeSpec.name ?: receiverTypeName,
                    qualifiedName = typeSpec.qualifiedName,
                    nameIdentifier = typeSpec.nameIdentifier
                )
            } else if (receiverTypeName.isNotEmpty()) {
                // Fall back to returning basic information if resolution fails.
                ClassInfo(
                    psiElement = receiver ?: func,
                    name = receiverTypeName,
                    qualifiedName = null,
                    nameIdentifier = receiver
                )
            } else {
                null
            }
        } else {
            null
        }

        return FunctionInfo(
            psiElement = func,
            name = func.name ?: "<anonymous>",
            containingClass = classInfo,
            nameIdentifier = func.nameIdentifier
        )
    }

    override fun findContainingClass(element: PsiElement): ClassInfo? {
        // Prefer the element itself when it is a type definition.
        val typeSpec = (element as? GoTypeSpec)
            ?: PsiTreeUtil.getParentOfType(element, GoTypeSpec::class.java, false)
            ?: return null
        return ClassInfo(
            psiElement = typeSpec,
            name = typeSpec.name ?: "<anonymous>",
            qualifiedName = typeSpec.qualifiedName,
            nameIdentifier = typeSpec.nameIdentifier
        )
    }

    override fun getCallExpressions(function: FunctionInfo): List<CallExpressionInfo> {
        // Use GoCallExpr rather than GoReferenceExpression as the entry point.
        // This avoids misclassifying plain references (e.g. passing or assigning a function value) as calls.
        val element = function.psiElement
        val callExprs = PsiTreeUtil.findChildrenOfType(element, GoCallExpr::class.java)

        val result = mutableListOf<CallExpressionInfo>()
        for (call in callExprs) {
            val expr = call.expression
            val resolved = resolveCalleeExpression(expr)

            if (resolved is GoFunctionDeclaration || resolved is GoMethodDeclaration) {
                result.add(
                    CallExpressionInfo(
                        psiElement = call,
                        methodName = expr.text ?: "<unknown>",
                        resolvedTarget = findContainingFunction(resolved)
                    )
                )
            }
        }
        return result
    }

    override fun getSuperTypes(classInfo: ClassInfo): List<ClassInfo> {
        // Go embedding and interfaces are not supported yet.
        return emptyList()
    }

    override fun getSubTypes(classInfo: ClassInfo): List<ClassInfo> {
        return emptyList()
    }

    override fun resolveReference(element: PsiElement): PsiElement? {
        return (element as? GoReferenceExpression)?.resolve()
    }

    override fun findContainingCallExpression(element: PsiElement): CallExpressionInfo? {
        // If the element is itself a call expression, use it directly (do not use strict=true parent lookup).
        val call = (element as? GoCallExpr)
            ?: PsiTreeUtil.getParentOfType(element, GoCallExpr::class.java, false)
            ?: return null

        // Try to get and resolve the reference from GoCallExpr.
        // Supports multiple callee forms, including direct references and selector expressions.
        val expr = call.expression
        val resolved: PsiElement? = resolveCalleeExpression(expr)

        val target = if (resolved != null) findContainingFunction(resolved) else null

        return CallExpressionInfo(
            psiElement = call,
            methodName = expr.text ?: "<unknown>",
            resolvedTarget = target
        )
    }

    /** Resolves a call expression's callee, supporting multiple forms. */
    private fun resolveCalleeExpression(expr: GoExpression?): PsiElement? {
        // Go selector expressions (e.g. a.Method() or pkg.Func()) are also handled
        // by GoReferenceExpression, with a qualifier.
        // GoReferenceExpressionBase is the base class for all reference expressions.
        return when (expr) {
            is GoReferenceExpressionBase -> expr.resolve()
            else -> null
        }
    }

    override fun getSignatureInfo(callExpression: CallExpressionInfo): SignatureInfo? {
        val target = callExpression.resolvedTarget?.psiElement as? GoSignatureOwner ?: return null

        val signature = target.signature ?: return null
        val params = signature.parameters.parameterDeclarationList.flatMap { decl ->
            decl.paramDefinitionList.mapNotNull { def ->
                val paramName = def.identifier.text ?: def.text
                val typeText = decl.type?.text ?: ""
                ParameterInfo(
                    name = paramName,
                    type = typeText,
                    documentation = null
                )
            }
        }

        val returnType = signature.result?.type?.text
        val label = buildString {
            append((target as? GoNamedElement)?.name ?: "<anonymous>")
            append("(")
            append(params.joinToString(", ") { "${it.name}: ${it.type}" })
            append(")")
            if (!returnType.isNullOrBlank()) {
                append(": ")
                append(returnType)
            }
        }

        return SignatureInfo(
            label = label,
            documentation = null,
            parameters = params,
            returnType = returnType
        )
    }

    override fun getCallArgumentsInfo(callExpression: CallExpressionInfo): List<ArgumentInfo> {
        val call = callExpression.psiElement as? GoCallExpr ?: return emptyList()
        val args = call.argumentList.expressionList

        val target = callExpression.resolvedTarget?.psiElement as? GoSignatureOwner
        val signature = target?.signature
        val paramNames = signature?.parameters?.parameterDeclarationList.orEmpty().flatMap { decl ->
            decl.paramDefinitionList.mapNotNull { def ->
                def.identifier.text ?: def.text
            }
        }

        return args.mapIndexed { index, arg ->
            ArgumentInfo(
                psiElement = arg,
                parameterName = paramNames.getOrNull(index) ?: "arg$index",
                index = index,
                isNamed = false
            )
        }
    }

    override fun getVariablesNeedingTypeHints(
        file: PsiFile,
        startOffset: Int,
        endOffset: Int
    ): List<VariableTypeHintInfo> {
        // Go type inference requires a more complex implementation, so return an empty list for now.
        // The Go compiler infers types automatically; IDEs generally use built-in type inference APIs.
        return emptyList()
    }
}
