package com.frenchef.intellijlsp.language.xml

import com.frenchef.intellijlsp.language.*
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.diagnostic.logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * XML language handler.
 *
 * Unlike programming languages, XML is a markup language:
 * - It has no function/method concept, so findContainingFunction returns null.
 * - XmlTag can be treated as a structured element and returned as ClassInfo.
 * - Basic reference resolution is supported (e.g. DTD/Schema references).
 */
class XmlLanguageHandler : LanguageHandler {

    private val log = logger<XmlLanguageHandler>()

    override fun supports(file: PsiFile): Boolean {
        // Support XML language files.
        return file.language.id == XMLLanguage.INSTANCE.id || file is XmlFile
    }

    override fun findContainingFunction(element: PsiElement): FunctionInfo? {
        // XML has no function concept, so return null.
        log.debug("XmlLanguageHandler.findContainingFunction: XML has no function concept; returning null")
        return null
    }

    override fun findContainingClass(element: PsiElement): ClassInfo? {
        // Treat XmlTag as a "class" concept.
        val xmlTag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false)
            ?: (element as? XmlTag)
            ?: return null

        return ClassInfo(
            psiElement = xmlTag,
            name = xmlTag.name,
            qualifiedName = getTagQualifiedName(xmlTag),
            // Use the tag's name element rather than firstChild (which may be the '<' token).
            nameIdentifier = xmlTag.children.find { it.text == xmlTag.name }
        )
    }

    override fun getCallExpressions(function: FunctionInfo): List<CallExpressionInfo> {
        // XML has no function call concept.
        log.debug("XmlLanguageHandler.getCallExpressions: XML has no function calls; returning an empty list")
        return emptyList()
    }

    override fun getSuperTypes(classInfo: ClassInfo): List<ClassInfo> {
        // XML has no class inheritance hierarchy.
        // A parent tag could be returned as a "supertype", but return an empty list to keep the semantics clear.
        log.debug("XmlLanguageHandler.getSuperTypes: XML has no inheritance hierarchy; returning an empty list")
        return emptyList()
    }

    override fun getSubTypes(classInfo: ClassInfo): List<ClassInfo> {
        // XML has no class inheritance hierarchy.
        log.debug("XmlLanguageHandler.getSubTypes: XML has no inheritance hierarchy; returning an empty list")
        return emptyList()
    }

    override fun resolveReference(element: PsiElement): PsiElement? {
        // Try to resolve XML references (e.g. DTD/Schema or attribute-value references).
        return try {
            element.reference?.resolve()
        } catch (e: Exception) {
            log.debug("XmlLanguageHandler.resolveReference failed: ${e.message}")
            null
        }
    }

    override fun findContainingCallExpression(element: PsiElement): CallExpressionInfo? {
        // XML has no call expressions.
        log.debug("XmlLanguageHandler.findContainingCallExpression: XML has no call expressions; returning null")
        return null
    }

    override fun getSignatureInfo(callExpression: CallExpressionInfo): SignatureInfo? {
        // XML has no function signatures.
        return null
    }

    override fun getCallArgumentsInfo(callExpression: CallExpressionInfo): List<ArgumentInfo> {
        // XML has no function parameters.
        return emptyList()
    }

    override fun getVariablesNeedingTypeHints(
        file: PsiFile,
        startOffset: Int,
        endOffset: Int
    ): List<VariableTypeHintInfo> {
        // XML has no variable types.
        return emptyList()
    }

    // ============ Helper methods ============

    /**
     * Gets the fully qualified name of an XML tag as a path.
     * For example: project/dependencies/dependency
     */
    private fun getTagQualifiedName(tag: XmlTag): String {
        val path = mutableListOf<String>()
        var current: XmlTag? = tag

        while (current != null) {
            path.add(0, current.name)
            current = current.parentTag
        }

        return path.joinToString("/")
    }
}
