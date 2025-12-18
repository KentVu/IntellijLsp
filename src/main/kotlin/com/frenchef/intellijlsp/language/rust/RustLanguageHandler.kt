package com.frenchef.intellijlsp.language.rust

import com.frenchef.intellijlsp.language.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.rust.lang.RsLanguage
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.patText

/**
 * Rust language handler.
 *
 * Provides LSP support for Rust, including:
 * - Call Hierarchy.
 * - Type Hierarchy.
 * - Go to Definition.
 *
 * Based on the parsing logic in RustParser.kt.
 */
class RustLanguageHandler : LanguageHandler {

    override fun supports(file: PsiFile): Boolean {
        return file.language.id == RsLanguage.id
    }

    override fun findContainingFunction(element: PsiElement): FunctionInfo? {
        // If the element is itself a function, use it directly (do not use strict=true parent lookup).
        val rsFunction = (element as? RsFunction)
            ?: PsiTreeUtil.getParentOfType(element, RsFunction::class.java, false)
            ?: return null

        // Get the containing impl block (similar to a class).
        val implItem = PsiTreeUtil.getParentOfType(rsFunction, RsImplItem::class.java)
        val containingClass = if (implItem != null) findImplClassInfo(implItem) else null

        return FunctionInfo(
            psiElement = rsFunction,
            name = rsFunction.name ?: "<anonymous>",
            containingClass = containingClass,
            nameIdentifier = rsFunction.nameIdentifier,
            isConstructor = rsFunction.name == "new"  // Rust convention: new() acts as a constructor.
        )
    }

    override fun findContainingClass(element: PsiElement): ClassInfo? {
        // Rust's "class" concept may be:
        // 1. An impl block (RsImplItem).
        // 2. A struct definition (RsStructItem).
        // 3. A trait definition (RsTraitItem).
        // 4. A module (RsModItem).

        // Check the element itself first (avoids returning null when selectionRange hits a class node).
        if (element is RsImplItem) return findImplClassInfo(element)
        if (element is RsStructItem) return ClassInfo(
            psiElement = element,
            name = element.name ?: "<anonymous>",
            qualifiedName = buildQualifiedName(element),
            nameIdentifier = element.nameIdentifier
        )
        if (element is RsTraitItem) return ClassInfo(
            psiElement = element,
            name = element.name ?: "<anonymous>",
            qualifiedName = buildQualifiedName(element),
            nameIdentifier = element.nameIdentifier
        )

        // Search parent nodes (strict=false includes the element itself).
        val implItem = PsiTreeUtil.getParentOfType(element, RsImplItem::class.java, false)
        if (implItem != null) {
            return findImplClassInfo(implItem)
        }

        val structItem = PsiTreeUtil.getParentOfType(element, RsStructItem::class.java, false)
        if (structItem != null) {
            return ClassInfo(
                psiElement = structItem,
                name = structItem.name ?: "<anonymous>",
                qualifiedName = buildQualifiedName(structItem),
                nameIdentifier = structItem.nameIdentifier
            )
        }

        val traitItem = PsiTreeUtil.getParentOfType(element, RsTraitItem::class.java, false)
        if (traitItem != null) {
            return ClassInfo(
                psiElement = traitItem,
                name = traitItem.name ?: "<anonymous>",
                qualifiedName = buildQualifiedName(traitItem),
                nameIdentifier = traitItem.nameIdentifier
            )
        }

        return null
    }

    /**
     * Gets class information from an impl block.
     * See RustParser.classMap().
     */
    private fun findImplClassInfo(implItem: RsImplItem): ClassInfo? {
        // The firstChild of an impl block is usually a type reference.
        val typeName = implItem.typeReference?.text ?: return null
        val mod = PsiTreeUtil.getParentOfType(implItem, RsModItem::class.java)?.name

        val qualifiedName = if (mod != null) "$mod::$typeName" else typeName

        return ClassInfo(
            psiElement = implItem,
            name = typeName,
            qualifiedName = qualifiedName,
            nameIdentifier = implItem.typeReference
        )
    }

    override fun getCallExpressions(function: FunctionInfo): List<CallExpressionInfo> {
        val rsFunction = function.psiElement as? RsFunction ?: return emptyList()
        val results = mutableListOf<CallExpressionInfo>()

        // Use RsCallExpr and RsMethodCall as entry points.
        // This avoids misclassifying a function item used as a value as a call.

        // Handle function calls (fn_name() or path::fn_name()).
        val callExprs = PsiTreeUtil.findChildrenOfType(rsFunction, RsCallExpr::class.java)
        for (call in callExprs) {
            val pathExpr = call.expr as? RsPathExpr
            val resolved = pathExpr?.path?.reference?.resolve()
            if (resolved is RsFunction) {
                val resolvedFunctionInfo = findContainingFunction(resolved)
                if (resolvedFunctionInfo != null) {
                    results.add(
                        CallExpressionInfo(
                            psiElement = call,
                            methodName = pathExpr.path.referenceName ?: "",
                            resolvedTarget = resolvedFunctionInfo
                        )
                    )
                }
            }
        }

        // Handle method calls (receiver.method()).
        val methodCalls = PsiTreeUtil.findChildrenOfType(rsFunction, RsMethodCall::class.java)
        for (methodCall in methodCalls) {
            val resolved = methodCall.reference.resolve()
            if (resolved is RsFunction) {
                val resolvedFunctionInfo = findContainingFunction(resolved)
                if (resolvedFunctionInfo != null) {
                    results.add(
                        CallExpressionInfo(
                            psiElement = methodCall,
                            methodName = methodCall.referenceName,
                            resolvedTarget = resolvedFunctionInfo
                        )
                    )
                }
            }
        }

        return results
    }

    override fun getSuperTypes(classInfo: ClassInfo): List<ClassInfo> {
        val results = mutableListOf<ClassInfo>()

        // For impl blocks, find the implemented trait.
        val implItem = classInfo.psiElement as? RsImplItem
        if (implItem != null) {
            val traitRef = implItem.traitRef
            if (traitRef != null) {
                val resolved = traitRef.path.reference?.resolve()
                if (resolved is RsTraitItem) {
                    results.add(
                        ClassInfo(
                            psiElement = resolved,
                            name = resolved.name ?: "<anonymous>",
                            qualifiedName = buildQualifiedName(resolved),
                            nameIdentifier = resolved.nameIdentifier
                        )
                    )
                }
            }
        }

        // For traits, find supertraits.
        val traitItem = classInfo.psiElement as? RsTraitItem
        traitItem?.typeParamBounds?.let { bounds ->
            bounds.polyboundList.forEach { polybound ->
                val resolved = polybound.bound.traitRef?.path?.reference?.resolve()
                if (resolved is RsTraitItem) {
                    results.add(
                        ClassInfo(
                            psiElement = resolved,
                            name = resolved.name ?: "<anonymous>",
                            qualifiedName = buildQualifiedName(resolved),
                            nameIdentifier = resolved.nameIdentifier
                        )
                    )
                }
            }
        }

        return results
    }

    override fun getSubTypes(classInfo: ClassInfo): List<ClassInfo> {
        // Finding subtypes requires a global search, so return an empty list for now.
        // Follow the safe-failure principle.
        return emptyList()
    }

    override fun resolveReference(element: PsiElement): PsiElement? {
        // Handle path references.
        if (element is RsPath) {
            return element.reference?.resolve()
        }

        // Handle method calls.
        if (element is RsMethodCall) {
            return element.reference.resolve()
        }

        // Handle cases where the parent element is a reference.
        val parentPath = element.parent as? RsPath
        if (parentPath != null) {
            return parentPath.reference?.resolve()
        }

        return null
    }

    override fun findContainingCallExpression(element: PsiElement): CallExpressionInfo? {
        // Use the element directly if it is an RsCallExpr.
        val callExpr = (element as? RsCallExpr)
            ?: PsiTreeUtil.getParentOfType(element, RsCallExpr::class.java, false)
        if (callExpr != null) {
            val path = callExpr.expr as? RsPathExpr
            if (path != null) {
                val resolved = path.path.reference?.resolve() as? RsFunction
                return CallExpressionInfo(
                    psiElement = callExpr,
                    methodName = path.path.referenceName ?: "",
                    resolvedTarget = resolved?.let { findContainingFunction(it) }
                )
            }
        }

        // Use the element directly if it is an RsMethodCall.
        val methodCall = (element as? RsMethodCall)
            ?: PsiTreeUtil.getParentOfType(element, RsMethodCall::class.java, false)
        if (methodCall != null) {
            val resolved = methodCall.reference.resolve() as? RsFunction
            return CallExpressionInfo(
                psiElement = methodCall,
                methodName = methodCall.referenceName,
                resolvedTarget = resolved?.let { findContainingFunction(it) }
            )
        }

        return null
    }

    override fun getSignatureInfo(callExpression: CallExpressionInfo): SignatureInfo? {
        val resolvedTarget = callExpression.resolvedTarget?.psiElement as? RsFunction
            ?: return null

        val parameters = mutableListOf<ParameterInfo>()

        // Get the parameter list.
        resolvedTarget.valueParameterList?.valueParameterList?.forEach { param ->
            parameters.add(
                ParameterInfo(
                    name = param.patText ?: "",
                    type = param.typeReference?.text ?: "",
                    documentation = null
                )
            )
        }

        // Build the signature label.
        val paramText = parameters.joinToString(", ") { "${it.name}: ${it.type}" }
        val returnType = resolvedTarget.retType?.typeReference?.text
        val label = "fn ${resolvedTarget.name}($paramText)" +
                (if (returnType != null) " -> $returnType" else "")

        return SignatureInfo(
            label = label,
            documentation = resolvedTarget.documentation,
            parameters = parameters,
            returnType = returnType
        )
    }

    override fun getCallArgumentsInfo(callExpression: CallExpressionInfo): List<ArgumentInfo> {
        val results = mutableListOf<ArgumentInfo>()
        val resolvedTarget = callExpression.resolvedTarget?.psiElement as? RsFunction
            ?: return emptyList()

        // Get the argument list at the call site.
        val callExpr = callExpression.psiElement as? RsCallExpr
        val methodCall = callExpression.psiElement as? RsMethodCall

        val argList = callExpr?.valueArgumentList ?: methodCall?.valueArgumentList

        val params = resolvedTarget.valueParameterList?.valueParameterList ?: emptyList()
        val args = argList?.exprList ?: emptyList()

        args.forEachIndexed { index, arg ->
            val paramName = params.getOrNull(index)?.patText ?: "arg$index"
            results.add(
                ArgumentInfo(
                    psiElement = arg,
                    parameterName = paramName,
                    index = index,
                    isNamed = false  // Rust does not support named arguments.
                )
            )
        }

        return results
    }

    override fun getVariablesNeedingTypeHints(
        file: PsiFile,
        startOffset: Int,
        endOffset: Int
    ): List<VariableTypeHintInfo> {
        val results = mutableListOf<VariableTypeHintInfo>()

        // Find let bindings in the range.
        PsiTreeUtil.findChildrenOfType(file, RsLetDecl::class.java).forEach { letDecl ->
            val nameRange = letDecl.pat?.textRange ?: return@forEach

            // Check whether it is within the range.
            if (nameRange.startOffset < startOffset || nameRange.endOffset > endOffset) {
                return@forEach
            }

            // Check whether an explicit type declaration already exists.
            val hasExplicitType = letDecl.typeReference != null
            if (hasExplicitType) {
                return@forEach
            }

            // Try to infer the type (simplified; a full implementation needs a type inference engine).
            val inferredType = letDecl.expr?.let { inferType(it) } ?: return@forEach

            results.add(
                VariableTypeHintInfo(
                    insertOffset = nameRange.endOffset,
                    typeText = inferredType,
                    hasExplicitType = false
                )
            )
        }

        return results
    }

    /**
     * Builds a qualified name.
     */
    private fun buildQualifiedName(element: PsiElement): String? {
        val parts = mutableListOf<String>()

        // Get the element name.
        when (element) {
            is RsStructItem -> element.name?.let { parts.add(it) }
            is RsTraitItem -> element.name?.let { parts.add(it) }
            is RsEnumItem -> element.name?.let { parts.add(it) }
        }

        // Get the parent module.
        var current: PsiElement? = element.parent
        while (current != null) {
            if (current is RsModItem) {
                current.name?.let { parts.add(0, it) }
            }
            current = current.parent
        }

        return if (parts.isNotEmpty()) parts.joinToString("::") else null
    }

    /**
     * Performs simple type inference (for Inlay Hints).
     */
    private fun inferType(expr: RsExpr): String? {
        return when (expr) {
            is RsLitExpr -> when {
                expr.kind is RsLiteralKind.Integer -> "i32"
                expr.kind is RsLiteralKind.Float -> "f64"
                expr.kind is RsLiteralKind.Boolean -> "bool"
                expr.kind is RsLiteralKind.String -> "&str"
                expr.kind is RsLiteralKind.Char -> "char"
                else -> null
            }

            is RsPathExpr -> {
                // Try to resolve the type from the path.
                when (val resolved = expr.path.reference?.resolve()) {
                    is RsStructItem -> resolved.name
                    is RsEnumItem -> resolved.name
                    else -> null
                }
            }

            else -> null
        }
    }
}

// Gets the function's documentation comment.
private val RsFunction.documentation: String?
    get() = this.outerDocElements.joinToString("\n") { it.text.trimStart('/').trim() }
        .takeIf { it.isNotEmpty() }

// Gets an external documentation element.
private val RsFunction.outerDocElements: List<PsiElement>
    get() {
        val result = mutableListOf<PsiElement>()
        var sibling = this.prevSibling
        while (sibling != null) {
            if (sibling is PsiWhiteSpace) {
                sibling = sibling.prevSibling
                continue
            }
            if (sibling is RsOuterAttr && sibling.text.startsWith("///")) {
                result.add(0, sibling)
                sibling = sibling.prevSibling
            } else {
                break
            }
        }
        return result
    }

// Helper type extensions.
private typealias PsiWhiteSpace = com.intellij.psi.PsiWhiteSpace
