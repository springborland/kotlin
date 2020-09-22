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
    firProperty: FirProperty,
    containingClass: FirLightClassBase,
    lightMemberOrigin: LightMemberOrigin?,
    isTopLevel: Boolean
) : FirLightField(containingClass, lightMemberOrigin) {

    init {
        require(firProperty.hasBackingField)
    }

    override val kotlinOrigin: KtDeclaration? = firProperty.psi as? KtDeclaration

    private val lazyInitializers = mutableListOf<Lazy<*>>()
    private inline fun <T> getAndAddLazy(crossinline initializer: () -> T): Lazy<T> =
        lazyPub { initializer() }.also { lazyInitializers.add(it) }

    private val _returnedType: PsiType by getAndAddLazy {
        firProperty.returnTypeRef.asPsiType(
            firProperty.session,
            TypeMappingMode.DEFAULT,
            this@FirLightFieldForFirPropertyNode
        )
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

    init {
        //We should force computations on all lazy delegates to release descriptor on the end of ctor call
        with(lazyInitializers) {
            forEach { it.value }
            clear()
        }
    }
}

internal class FirLightFieldForFirObjectNode(
    firObjectNode: FirRegularClass,
    containingClass: KtLightClass,
    lightMemberOrigin: LightMemberOrigin?,
) : FirLightField(containingClass, lightMemberOrigin) {

    override val kotlinOrigin: KtDeclaration? = firObjectNode.psi as? KtDeclaration

    private val _name = if (firObjectNode.isCompanion) firObjectNode.name.asString() else "INSTANCE"
    override fun getName(): String = _name

    private val _modifierList: PsiModifierList by lazyPub {
        val modifiers = setOf(firObjectNode.computeVisibility(isTopLevel = false), PsiModifier.STATIC, PsiModifier.FINAL)
        val notNullAnnotation = FirLightSimpleAnnotation("org.jetbrains.annotations.NotNull", this)
        FirLightClassModifierList(this, modifiers, listOf(notNullAnnotation))
    }

    override fun getModifierList(): PsiModifierList? = _modifierList

    private val _type: PsiType = firObjectNode.run {
        ConeClassLikeTypeImpl(
            symbol.toLookupTag(),
            typeParameters.map { ConeTypeParameterTypeImpl(it.symbol.toLookupTag(), isNullable = false) }.toTypedArray(),
            isNullable = false
        ).asPsiType(session, TypeMappingMode.DEFAULT, this@FirLightFieldForFirObjectNode)
    }

    override fun getType(): PsiType = _type

    override fun getInitializer(): PsiExpression? = null //TODO
}