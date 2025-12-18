package com.frenchef.intellijlsp.language

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Language handler interface defining language-specific symbol resolution methods.
 * 
 * Each language (or language group) should implement this interface to provide language-specific AST traversal and symbol resolution.
 * This allows LSP features such as Call Hierarchy and Type Hierarchy to work across languages.
 */
interface LanguageHandler {

    /** Checks whether the handler supports the file's language. */
    fun supports(file: PsiFile): Boolean

    /** Finds the method or function containing the specified element. */
    fun findContainingFunction(element: PsiElement): FunctionInfo?

    /** Finds the class or type containing the specified element. */
    fun findContainingClass(element: PsiElement): ClassInfo?

    /** Gets all call expressions in a function. */
    fun getCallExpressions(function: FunctionInfo): List<CallExpressionInfo>

    /** Gets a class's supertypes (superclasses and implemented interfaces). */
    fun getSuperTypes(classInfo: ClassInfo): List<ClassInfo>

    /** Gets a class's subtypes (subclasses and implementations). */
    fun getSubTypes(classInfo: ClassInfo): List<ClassInfo>

    /** Attempts to resolve the target of an element's reference. */
    fun resolveReference(element: PsiElement): PsiElement?

    /** Finds the call expression containing the specified element. */
    fun findContainingCallExpression(element: PsiElement): CallExpressionInfo?

    /** Gets Signature Help information (for textDocument/signatureHelp). */
    fun getSignatureInfo(callExpression: CallExpressionInfo): SignatureInfo?

    /** Gets argument information for a call expression (for Inlay Hints). */
    fun getCallArgumentsInfo(callExpression: CallExpressionInfo): List<ArgumentInfo>

    /** Gets variables that need type hints (for Inlay Hints). */
    fun getVariablesNeedingTypeHints(file: PsiFile, startOffset: Int, endOffset: Int): List<VariableTypeHintInfo>

    /** Gets all call expressions in the specified range (for efficient Inlay Hints collection). */
    fun getCallExpressionsInRange(file: PsiFile, startOffset: Int, endOffset: Int): List<CallExpressionInfo> {
        // Default implementation returns an empty list; language handlers should override this for efficiency.
        return emptyList()
    }
}

/**
 * Unified representation of function/method information.
 * 
 * Abstracts function concepts across languages (Java methods, Kotlin functions, JS functions, etc.).
 */
data class FunctionInfo(
    /** Original PSI element. */
    val psiElement: PsiElement,
    /** Function name. */
    val name: String,
    /** Class containing this function, if any. */
    val containingClass: ClassInfo?,
    /** Name identifier element (for precise positioning). */
    val nameIdentifier: PsiElement?,
    /** Whether this is a constructor. */
    val isConstructor: Boolean = false
)

/**
 * Unified representation of class/type information.
 * 
 * Abstracts class concepts across languages (Java classes, Kotlin classes, JS classes/prototypes, etc.).
 */
data class ClassInfo(
    /** Original PSI element. */
    val psiElement: PsiElement,
    /** Class name. */
    val name: String,
    /** Fully qualified name, if available. */
    val qualifiedName: String?,
    /** Name identifier element. */
    val nameIdentifier: PsiElement?
)

/**
 * Unified representation of a call expression.
 * 
 * Represents a function/method call.
 */
data class CallExpressionInfo(
    /** PSI element for the call expression. */
    val psiElement: PsiElement,
    /** Name of the called method. */
    val methodName: String,
    /** Resolved target function, if resolvable. */
    val resolvedTarget: FunctionInfo?
)

/**
 * Signature information for textDocument/signatureHelp.
 */
data class SignatureInfo(
    /** Complete signature label. */
    val label: String,
    /** Documentation. */
    val documentation: String?,
    /** Parameter list. */
    val parameters: List<ParameterInfo>,
    /** Return type. */
    val returnType: String?
)

/**
 * Parameter information.
 */
data class ParameterInfo(
    /** Parameter name. */
    val name: String,
    /** Parameter type. */
    val type: String,
    /** Parameter documentation. */
    val documentation: String?
)

/**
 * Call argument information for Inlay Hints.
 */
data class ArgumentInfo(
    /** PSI element at the argument position. */
    val psiElement: PsiElement,
    /** Parameter name. */
    val parameterName: String,
    /** Parameter index. */
    val index: Int,
    /** Whether this is a named argument. */
    val isNamed: Boolean
)

/**
 * Variable type hint information for Inlay Hints.
 */
data class VariableTypeHintInfo(
    /** Offset after the variable name (where the type hint is inserted). */
    val insertOffset: Int,
    /** Type text. */
    val typeText: String,
    /** Whether the type is explicitly declared. */
    val hasExplicitType: Boolean
)
