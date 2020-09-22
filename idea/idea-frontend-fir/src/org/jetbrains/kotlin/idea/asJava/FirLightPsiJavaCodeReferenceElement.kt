/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.asJava

import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.util.IncorrectOperationException

internal class FirLightPsiJavaCodeReferenceElementWithReference(private val ktElement: PsiElement, reference: PsiReference):
    FirLightPsiJavaCodeReferenceElementBase(ktElement),
    PsiReference by reference {

    override fun getElement(): PsiElement = ktElement
}

internal class FirLightPsiJavaCodeReferenceElementWithNoReference(private val ktElement: PsiElement):
    FirLightPsiJavaCodeReferenceElementBase(ktElement),
    PsiReference {

    override fun getElement(): PsiElement = ktElement

    override fun getRangeInElement(): TextRange = ktElement.textRange

    override fun resolve(): PsiElement? = null

    override fun getCanonicalText(): String = "<no-text>"

    override fun handleElementRename(newElementName: String): PsiElement = element

    @Throws(IncorrectOperationException::class)
    override fun bindToElement(element: PsiElement): PsiElement =
        throw IncorrectOperationException("can't rename FirLightPsiJavaCodeReferenceElementWithNoReference")

    override fun isReferenceTo(element: PsiElement): Boolean = false

    override fun isSoft(): Boolean = false
}

internal abstract class FirLightPsiJavaCodeReferenceElementBase(private val ktElement: PsiElement) :
    PsiElement by ktElement,
    PsiJavaCodeReferenceElement {

    override fun multiResolve(incompleteCode: Boolean): Array<JavaResolveResult> = emptyArray()

    override fun processVariants(processor: PsiScopeProcessor) { }

    override fun advancedResolve(incompleteCode: Boolean): JavaResolveResult =
        JavaResolveResult.EMPTY

    override fun getQualifier(): PsiElement? = null

    override fun getReferenceName(): String? = null

    override fun getReferenceNameElement(): PsiElement? = null

    override fun getParameterList(): PsiReferenceParameterList? = null

    override fun getTypeParameters(): Array<PsiType> = emptyArray()

    override fun isQualified(): Boolean = false

    override fun getQualifiedName(): String? = null
}