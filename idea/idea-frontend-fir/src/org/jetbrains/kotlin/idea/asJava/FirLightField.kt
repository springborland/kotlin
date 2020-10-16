/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.asJava

import com.intellij.lang.Language
import com.intellij.psi.*
import com.intellij.psi.impl.ElementPresentationUtil
import com.intellij.ui.IconManager
import com.intellij.util.IncorrectOperationException
import com.intellij.util.PlatformIcons
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.asJava.builder.LightMemberOrigin
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.classes.cannotModify
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.asJava.elements.KtLightField
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.frontend.api.fir.analyzeWithSymbolAsContext
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirClassOrObjectSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirPropertySymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.utils.firRef
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtClassKind
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtClassOrObjectSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtPropertySymbol
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.kotlin.psi.*
import javax.swing.Icon

internal abstract class FirLightField protected constructor(
    private val containingClass: KtLightClass,
    lightMemberOrigin: LightMemberOrigin?,
) : FirLightMemberImpl<PsiField>(lightMemberOrigin, containingClass), KtLightField {

    override val clsDelegate: PsiField get() = invalidAccess()

    override fun setInitializer(initializer: PsiExpression?) = cannotModify()

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        kotlinOrigin == another || ((another is FirLightField) && another.kotlinOrigin == kotlinOrigin)

    override fun getLanguage(): Language = KotlinLanguage.INSTANCE

    override fun getParent() = containingClass
    override fun getContainingClass() = containingClass
    override fun getContainingFile(): PsiFile? = containingClass.containingFile
    override fun hasInitializer(): Boolean = initializer !== null

    override fun computeConstantValue(): Any? = null //TODO _constantInitializer?.value

    override fun computeConstantValue(visitedVars: MutableSet<PsiVariable>?): Any? = computeConstantValue()

    override fun setName(@NonNls name: String): PsiElement {
        (kotlinOrigin as? KtNamedDeclaration)?.setName(name)
        return this
    }

    override fun toString(): String = "KtLightField:$name"

    override fun getTypeElement(): PsiTypeElement? = null

    @Throws(IncorrectOperationException::class)
    override fun normalizeDeclaration() {
    }

    override fun isVisibilitySupported(): Boolean = true

    override fun getElementIcon(flags: Int): Icon? {
        val baseIcon = IconManager.getInstance().createLayeredIcon(
            this,
            PlatformIcons.VARIABLE_ICON, ElementPresentationUtil.getFlags(
                this,
                false
            )
        )
        return ElementPresentationUtil.addVisibilityIcon(this, flags, baseIcon)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FirLightField) return false
        if (this.javaClass != other.javaClass) return false
        if (containingClass != other.containingClass) return false
        if (kotlinOrigin === null || other.kotlinOrigin === null) return false
        return kotlinOrigin == other.kotlinOrigin
    }

    override fun hashCode(): Int = name.hashCode()

    override fun accept(visitor: PsiElementVisitor) {
        if (visitor is JavaElementVisitor) {
            visitor.visitField(this)
        } else {
            visitor.visitElement(this)
        }
    }
}

internal class FirLightFieldForFirPropertyNode(
    firProperty: KtPropertySymbol,
    containingClass: FirLightClassBase,
    lightMemberOrigin: LightMemberOrigin?,
    isTopLevel: Boolean
) : FirLightField(containingClass, lightMemberOrigin) {

    override val kotlinOrigin: KtDeclaration? = firProperty.psi as? KtDeclaration

    private val _returnedType: PsiType by lazyPub {

        require(firProperty is KtFirPropertySymbol)
        firProperty.firRef.withFir(FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE) {
            it.returnTypeRef.asPsiType(
                it.session,
                TypeMappingMode.DEFAULT,
                this@FirLightFieldForFirPropertyNode
            )
        }
    }

    override fun getType(): PsiType = _returnedType

    private val _name = firProperty.name.asString()
    override fun getName(): String = _name

    private val _modifierList: PsiModifierList by lazyPub {

        val basicModifiers = firProperty.computeModalityForMethod(isTopLevel)
        val modifiers = if (!firProperty.hasAnnotation("kotlin.jvm.JvmField"))
            basicModifiers + PsiModifier.PRIVATE + PsiModifier.FINAL
        else if (firProperty.isVal) basicModifiers + PsiModifier.FINAL else basicModifiers

        FirLightClassModifierList(this, modifiers, emptyList())
    }

    override fun getModifierList(): PsiModifierList? = _modifierList


    override fun getInitializer(): PsiExpression? = null //TODO
}

internal class FirLightFieldForFirObjectNode(
    firObjectNode: KtClassOrObjectSymbol,
    containingClass: KtLightClass,
    lightMemberOrigin: LightMemberOrigin?,
) : FirLightField(containingClass, lightMemberOrigin) {

    override val kotlinOrigin: KtDeclaration? = firObjectNode.psi as? KtDeclaration

    private val _name = if (firObjectNode.classKind == KtClassKind.COMPANION_OBJECT) firObjectNode.name.asString() else "INSTANCE"
    override fun getName(): String = _name

    private val _modifierList: PsiModifierList by lazyPub {
        val modifiers = setOf(firObjectNode.computeVisibility(isTopLevel = false), PsiModifier.STATIC, PsiModifier.FINAL)
        val notNullAnnotation = FirLightSimpleAnnotation("org.jetbrains.annotations.NotNull", this)
        FirLightClassModifierList(this, modifiers, listOf(notNullAnnotation))
    }

    override fun getModifierList(): PsiModifierList? = _modifierList

    private val _type: PsiType = firObjectNode.run {
        require(firObjectNode is KtFirClassOrObjectSymbol)
        firObjectNode.firRef.withFir(FirResolvePhase.TYPES) { firClass ->
            ConeClassLikeTypeImpl(
                firClass.symbol.toLookupTag(),
                firClass.typeParameters.map { ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), isNullable = false) }.toTypedArray(),
                isNullable = false
            ).asPsiType(firClass.session, TypeMappingMode.DEFAULT, this@FirLightFieldForFirObjectNode)
        }
    }

    override fun getType(): PsiType = _type

    override fun getInitializer(): PsiExpression? = null //TODO
}