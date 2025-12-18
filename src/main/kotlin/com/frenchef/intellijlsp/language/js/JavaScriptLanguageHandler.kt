package com.frenchef.intellijlsp.language.js

import com.frenchef.intellijlsp.language.*
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSNewExpression
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class JavaScriptLanguageHandler : LanguageHandler {

    override fun supports(file: PsiFile): Boolean {
        // Support both JavaScript and TypeScript
        val langId = file.language.id
        return langId == "JavaScript" || langId == "TypeScript" || langId == "ECMAScript 6"
    }

    override fun findContainingFunction(element: PsiElement): FunctionInfo? {
        // If the element is itself a function, return it directly (do not use strict=true parent lookup).
        val jsFunction = (element as? JSFunction)
            ?: PsiTreeUtil.getParentOfType(element, JSFunction::class.java, false)
            ?: return null
        val containingClass = findContainingClass(jsFunction)

        return FunctionInfo(
            psiElement = jsFunction,
            name = jsFunction.name ?: "<anonymous>",
            containingClass = containingClass,
            nameIdentifier = jsFunction.nameIdentifier,
            isConstructor = false
        )
    }

    override fun findContainingClass(element: PsiElement): ClassInfo? {
        // Prefer the element itself when it is a class.
        val jsClass = (element as? JSClass)
            ?: PsiTreeUtil.getParentOfType(element, JSClass::class.java, false)
            ?: return null
        return ClassInfo(
            psiElement = jsClass,
            name = jsClass.name ?: "<anonymous>",
            qualifiedName = jsClass.qualifiedName,
            nameIdentifier = jsClass.nameIdentifier
        )
    }

    override fun getCallExpressions(function: FunctionInfo): List<CallExpressionInfo> {
        val jsFunction = function.psiElement as? JSFunction ?: return emptyList()
        val results = mutableListOf<CallExpressionInfo>()

        PsiTreeUtil.findChildrenOfType(jsFunction, JSCallExpression::class.java).forEach { call ->
            results.add(callExpressionToInfo(call))
        }

        PsiTreeUtil.findChildrenOfType(jsFunction, JSNewExpression::class.java).forEach { newExpr ->
            results.add(newExpressionToInfo(newExpr))
        }

        return results
    }

    override fun getSuperTypes(classInfo: ClassInfo): List<ClassInfo> {
        val jsClass = classInfo.psiElement as? JSClass ?: return emptyList()
        return jsClass.superClasses.map { superClass ->
            ClassInfo(
                psiElement = superClass,
                name = superClass.name ?: "<anonymous>",
                qualifiedName = superClass.qualifiedName,
                nameIdentifier = superClass.nameIdentifier
            )
        }
    }

    override fun getSubTypes(classInfo: ClassInfo): List<ClassInfo> {
        // Finding subclasses requires searching effectively; keep safe-fail for now
        return emptyList()
    }

    override fun resolveReference(element: PsiElement): PsiElement? {
        return element.reference?.resolve()
    }

    override fun findContainingCallExpression(element: PsiElement): CallExpressionInfo? {
        // Prefer the element itself when it is a call expression to avoid selecting an outer nested call.
        // For foo(bar()), if the element is bar(), return bar() rather than foo().
        if (element is JSCallExpression) return callExpressionToInfo(element)
        if (element is JSNewExpression) return newExpressionToInfo(element)

        // Then search for a parent call expression (strict=false includes the element itself).
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java, false)
        if (call != null) return callExpressionToInfo(call)

        val newExpr = PsiTreeUtil.getParentOfType(element, JSNewExpression::class.java, false)
        if (newExpr != null) return newExpressionToInfo(newExpr)

        return null
    }

    override fun getSignatureInfo(callExpression: CallExpressionInfo): SignatureInfo? {
        val targetFunction = resolveTargetFunction(callExpression) ?: return null

        val params = extractParameters(targetFunction)
        val paramStr = params.joinToString(", ") { "${it.name}: ${it.type}" }
        val label = "${targetFunction.name ?: "<anonymous>"}($paramStr)"

        return SignatureInfo(
            label = label,
            documentation = null,
            parameters = params,
            returnType = null
        )
    }

    override fun getCallArgumentsInfo(callExpression: CallExpressionInfo): List<ArgumentInfo> {
        val targetFunction = resolveTargetFunction(callExpression)
        val params = targetFunction?.let { extractParameters(it) }.orEmpty()

        val args: Array<out JSExpression> = when (val psi = callExpression.psiElement) {
            is JSNewExpression -> psi.arguments
            is JSCallExpression -> psi.arguments
            else -> emptyArray()
        }

        return args.mapIndexed { index, arg ->
            val paramName = params.getOrNull(index)?.name ?: "arg$index"
            ArgumentInfo(
                psiElement = arg,
                parameterName = paramName,
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
        // JS/TS type hints require deeper type inference; omit them for now to avoid false positives.
        return emptyList()
    }

    private fun callExpressionToInfo(call: JSCallExpression): CallExpressionInfo {
        val callee = call.methodExpression
        val resolved = callee?.let { resolveReference(it) }
        val target = if (resolved is JSFunction) findContainingFunction(resolved) else null

        return CallExpressionInfo(
            psiElement = call,
            methodName = callee?.text ?: "<unknown>",
            resolvedTarget = target
        )
    }

    private fun newExpressionToInfo(newExpr: JSNewExpression): CallExpressionInfo {
        val callee = newExpr.methodExpression
        val resolved = callee?.let { resolveReference(it) }
        val target = if (resolved is JSFunction) findContainingFunction(resolved) else null

        return CallExpressionInfo(
            psiElement = newExpr,
            methodName = callee?.text ?: "<constructor>",
            resolvedTarget = target
        )
    }

    private fun resolveTargetFunction(callExpression: CallExpressionInfo): JSFunction? {
        val direct = callExpression.resolvedTarget?.psiElement as? JSFunction
        if (direct != null) return direct

        val callee: PsiElement? = when (val psi = callExpression.psiElement) {
            is JSNewExpression -> psi.methodExpression
            is JSCallExpression -> psi.methodExpression
            else -> null
        }

        return callee?.let { resolveReference(it) } as? JSFunction
    }

    private fun extractParameters(function: JSFunction): List<ParameterInfo> {
        val parameters = function.parameterList?.parameters ?: emptyArray()

        return parameters.map { param ->
            ParameterInfo(
                name = param.name ?: "",
                type = param.jsType?.typeText ?: "any",
                documentation = null
            )
        }
    }
}
