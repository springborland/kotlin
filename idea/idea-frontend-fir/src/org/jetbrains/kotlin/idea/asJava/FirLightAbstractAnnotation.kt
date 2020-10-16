/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.asJava

import com.intellij.psi.*
import org.jetbrains.kotlin.asJava.classes.cannotModify
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.asJava.elements.*
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtAnnotationCall
import org.jetbrains.kotlin.psi.*

abstract class FirLightAbstractAnnotation(parent: PsiElement) :
    KtLightElementBase(parent), PsiAnnotation, KtLightElement<KtCallElement, PsiAnnotation> {

    override val clsDelegate: PsiAnnotation
        get() = invalidAccess()

    override fun getOwner() = parent as? PsiAnnotationOwner

    override fun findAttributeValue(name: String?): PsiAnnotationMemberValue? = null //TODO()

    override fun findDeclaredAttributeValue(name: String?): PsiAnnotationMemberValue? = null //TODO()

    private val KtExpression.nameReference: KtNameReferenceExpression?
        get() = when (this) {
            is KtConstructorCalleeExpression -> constructorReferenceExpression as? KtNameReferenceExpression
            else -> this as? KtNameReferenceExpression
        }

    private val _nameReferenceElement: PsiJavaCodeReferenceElement by lazyPub {
        val ktElement = kotlinOrigin?.navigationElement ?: this
        val reference = (kotlinOrigin as? KtAnnotationEntry)?.typeReference?.reference
            ?: (kotlinOrigin?.calleeExpression?.nameReference)?.references?.firstOrNull()

        if (reference != null) FirLightPsiJavaCodeReferenceElementWithReference(ktElement, reference)
        else FirLightPsiJavaCodeReferenceElementWithNoReference(ktElement)
    }

    override fun getNameReferenceElement(): PsiJavaCodeReferenceElement = _nameReferenceElement

    private class FirAnnotationParameterList(parent: PsiAnnotation) : KtLightElementBase(parent), PsiAnnotationParameterList {
        override val kotlinOrigin: KtElement? = null
        override fun getAttributes(): Array<PsiNameValuePair> = emptyArray() //TODO()
    }

    private val annotationParameterList: PsiAnnotationParameterList = FirAnnotationParameterList(this)

    override fun getParameterList(): PsiAnnotationParameterList = annotationParameterList

    override fun delete() {
        kotlinOrigin?.delete()
    }

    override fun toString() = "@$qualifiedName"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other::class.java != this::class.java) return false
        return kotlinOrigin == (other as FirLightAnnotationForFirNode).kotlinOrigin
    }

    override fun hashCode() = kotlinOrigin.hashCode()

    override fun <T : PsiAnnotationMemberValue?> setDeclaredAttributeValue(attributeName: String?, value: T?) = cannotModify()
}

class FirLightSimpleAnnotation(
    private val fqName: String?,
    parent: PsiElement
) : FirLightAbstractAnnotation(parent) {

    override val kotlinOrigin: KtCallElement? = null

    override fun getQualifiedName(): String? = fqName

    override fun getName(): String? = fqName
}

class FirLightAnnotationForFirNode(
    private val annotationCall: KtAnnotationCall,
    parent: PsiElement,
) : FirLightAbstractAnnotation(parent) {

    override val kotlinOrigin: KtCallElement? = annotationCall.psi

    override fun getQualifiedName(): String? = annotationCall.classId?.asSingleFqName()?.asString()

    override fun getName(): String? = qualifiedName
}