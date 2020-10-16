/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.asJava

import com.intellij.psi.*
import com.intellij.psi.impl.PsiImplUtil
import com.intellij.psi.impl.PsiSuperMethodImplUtil
import com.intellij.psi.impl.light.LightParameterListBuilder
import com.intellij.psi.javadoc.PsiDocComment
import com.intellij.psi.util.MethodSignature
import com.intellij.psi.util.MethodSignatureBackedByPsiMethod
import org.jetbrains.kotlin.asJava.builder.LightMemberOrigin
import org.jetbrains.kotlin.asJava.classes.*
import org.jetbrains.kotlin.asJava.elements.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.resolve.calls.isUnit
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.types.ConeNullability
import org.jetbrains.kotlin.fir.types.isUnit
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirFunctionSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirPropertyGetterSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirPropertySetterSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.types.KtFirType
import org.jetbrains.kotlin.idea.frontend.api.symbols.*
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtAnnotatedSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtNamedSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtPossibleExtensionSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtTypedSymbol
import org.jetbrains.kotlin.idea.frontend.api.types.KtTypeNullability
import org.jetbrains.kotlin.idea.frontend.api.types.KtTypeWithNullability
import org.jetbrains.kotlin.idea.frontend.api.types.isUnit
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.load.java.JvmAbi.getterName
import org.jetbrains.kotlin.load.java.JvmAbi.setterName
import java.util.*

internal abstract class FirLightMemberImpl<T : PsiMember>(
    override val lightMemberOrigin: LightMemberOrigin?,
    private val containingClass: KtLightClass,
) : KtLightElementBase(containingClass), PsiMember, KtLightMember<T> {

    override val clsDelegate: T
        get() = invalidAccess()

    private val lightIdentifier by lazyPub { KtLightIdentifier(this, kotlinOrigin as? KtNamedDeclaration) }

    override fun hasModifierProperty(name: String): Boolean = modifierList?.hasModifierProperty(name) ?: false

    override fun toString(): String = "${this::class.java.simpleName}:$name"

    override fun getContainingClass() = containingClass

    override fun getNameIdentifier(): PsiIdentifier = lightIdentifier

    override val kotlinOrigin: KtDeclaration? get() = lightMemberOrigin?.originalElement

    override fun getDocComment(): PsiDocComment? = null //TODO()

    override fun isDeprecated(): Boolean = false //TODO()

    abstract override fun getName(): String

    override fun isValid(): Boolean {
        return parent.isValid && lightMemberOrigin?.isValid() != false
    }

    override fun isEquivalentTo(another: PsiElement?): Boolean {
        return this == another ||
                lightMemberOrigin?.isEquivalentTo(another) == true ||
                another is KtLightMember<*> && lightMemberOrigin?.isEquivalentTo(another.lightMemberOrigin) == true
    }
}

internal abstract class FirLightMethod(
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: KtLightClass,
    private val methodIndex: Int
) : FirLightMemberImpl<PsiMethod>(lightMemberOrigin, containingClass), KtLightMethod {

    override fun getBody(): PsiCodeBlock? = null

    override fun getReturnTypeElement(): PsiTypeElement? = null

    abstract fun buildTypeParameterList(): PsiTypeParameterList

    override fun setName(p0: String): PsiElement = cannotModify()

    override fun isVarArgs() = PsiImplUtil.isVarArgs(this)

    override fun getHierarchicalMethodSignature() = PsiSuperMethodImplUtil.getHierarchicalMethodSignature(this)

    override fun findSuperMethodSignaturesIncludingStatic(checkAccess: Boolean): List<MethodSignatureBackedByPsiMethod> =
        PsiSuperMethodImplUtil.findSuperMethodSignaturesIncludingStatic(this, checkAccess)

    override fun findDeepestSuperMethod() = PsiSuperMethodImplUtil.findDeepestSuperMethod(this)

    override fun findDeepestSuperMethods(): Array<out PsiMethod> = PsiSuperMethodImplUtil.findDeepestSuperMethods(this)

    override fun findSuperMethods(): Array<out PsiMethod> = PsiSuperMethodImplUtil.findSuperMethods(this)

    override fun findSuperMethods(checkAccess: Boolean): Array<out PsiMethod> =
        PsiSuperMethodImplUtil.findSuperMethods(this, checkAccess)

    override fun findSuperMethods(parentClass: PsiClass?): Array<out PsiMethod> =
        PsiSuperMethodImplUtil.findSuperMethods(this, parentClass)

    override fun getSignature(substitutor: PsiSubstitutor): MethodSignature =
        MethodSignatureBackedByPsiMethod.create(this, substitutor)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FirLightMethod) return false
        if (methodIndex != other.methodIndex) return false
        if (this.javaClass != other.javaClass) return false
        if (containingClass != other.containingClass) return false
        if (kotlinOrigin === null || other.kotlinOrigin === null) return false
        return kotlinOrigin == other.kotlinOrigin
    }

    override fun accept(visitor: PsiElementVisitor) {
        if (visitor is JavaElementVisitor) {
            visitor.visitMethod(this)
        } else {
            visitor.visitElement(this)
        }
    }

    override fun hashCode(): Int = name.hashCode() + methodIndex
}

internal class FirLightAccessorMethodForFirNode(
    firPropertyAccessor: KtPropertyAccessorSymbol,
    firContainingProperty: KtPropertySymbol,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    isTopLevel: Boolean
) : FirLightMethodForFirNode(
    firPropertyAccessor,
    lightMemberOrigin,
    containingClass,
    if (firPropertyAccessor is KtPropertyGetterSymbol) METHOD_INDEX_FOR_GETTER else METHOD_INDEX_FOR_SETTER
) {
    private fun String.abiName(firPropertyAccessor: KtPropertyAccessorSymbol) =
        if (firPropertyAccessor is KtPropertyGetterSymbol) getterName(this)
        else setterName(this)

    //TODO add JvmName
    private val _name: String by lazyPub {
        firContainingProperty.name.identifier
            .abiName(firPropertyAccessor)
    }

    override fun getName(): String = _name

    override fun isVarArgs(): Boolean = false

    override val kotlinOrigin: KtDeclaration? =
        (firPropertyAccessor.psi ?: firContainingProperty.psi) as? KtDeclaration

    private val _annotations: List<PsiAnnotation> by lazyPub {
        val accessorSite =
            if (firPropertyAccessor is KtPropertyGetterSymbol) AnnotationUseSiteTarget.PROPERTY_GETTER
            else AnnotationUseSiteTarget.PROPERTY_SETTER
        firContainingProperty.computeAnnotations(this, NullabilityType.Unknown, accessorSite)
    }

    private val _modifiers: Set<String> by lazyPub {

        val modifiers = firPropertyAccessor.computeModalityForMethod(isTopLevel) +
                firPropertyAccessor.computeVisibility(isTopLevel)

        val isJvmStatic =
            _annotations.any { it.qualifiedName == "kotlin.jvm.JvmStatic" }

        if (isJvmStatic) return@lazyPub modifiers + PsiModifier.STATIC

        modifiers
    }

    private val _modifierList: PsiModifierList by lazyPub {
        FirLightClassModifierList(this, _modifiers, _annotations)
    }

    override fun getModifierList(): PsiModifierList = _modifierList

    override fun isConstructor(): Boolean = false

    private val _returnedType: PsiType? by lazyPub {
        if (firPropertyAccessor is KtPropertySetterSymbol) return@lazyPub PsiType.VOID
        return@lazyPub firPropertyAccessor.asPsiType(this@FirLightAccessorMethodForFirNode)
    }

    override fun getReturnType(): PsiType? = _returnedType
}

internal class FirLightSimpleMethodForFirNode(
    firFunction: KtFunctionSymbol,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    methodIndex: Int,
    isTopLevel: Boolean,
    argumentsSkipMask: BitSet? = null
) : FirLightMethodForFirNode(
    firFunction = firFunction,
    lightMemberOrigin = lightMemberOrigin,
    containingClass = containingClass,
    methodIndex = methodIndex,
    argumentsSkipMask = argumentsSkipMask
) {

    private val _name: String by lazyPub {
        firFunction.name.asString()
    }

    override fun getName(): String = _name

    private val _annotations: List<PsiAnnotation> by lazyPub {
        firFunction.computeAnnotations(this, firFunction.type.nullabilityType)
    }

    private val _modifiers: Set<String> by lazyPub {

        val isInlineOnly =
            _annotations.any { it.qualifiedName == "kotlin.internal.InlineOnly" }

        if (isInlineOnly) return@lazyPub setOf(PsiModifier.FINAL, PsiModifier.PRIVATE)

        val modifiers = firFunction.computeModalityForMethod(isTopLevel = isTopLevel) +
                firFunction.computeVisibility(isTopLevel = isTopLevel)

        val isJvmStatic =
            _annotations.any { it.qualifiedName == "kotlin.jvm.JvmStatic" }

        if (isJvmStatic) return@lazyPub modifiers + PsiModifier.STATIC

        modifiers
    }


    private val _modifierList: PsiModifierList by lazyPub {
        FirLightClassModifierList(this, _modifiers, _annotations)
    }

    override fun getModifierList(): PsiModifierList = _modifierList

    override fun isConstructor(): Boolean = false

    private val _returnedType: PsiType? by lazyPub {
        firFunction.asPsiType(this@FirLightSimpleMethodForFirNode)
    }

    override fun getReturnType(): PsiType? = _returnedType
}

internal class FirLightConstructorForFirNode(
    firFunction: KtConstructorSymbol,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    methodIndex: Int,
) : FirLightMethodForFirNode(firFunction, lightMemberOrigin, containingClass, methodIndex) {

    private val _name: String? = containingClass.name

    override fun getName(): String = _name ?: ""

    override fun isConstructor(): Boolean = true

    private val _annotations: List<PsiAnnotation> by lazyPub {
        firFunction.computeAnnotations(this, NullabilityType.Unknown)
    }

    private val _modifiers: Set<String> by lazyPub {
        setOf(firFunction.computeVisibility(isTopLevel = false))
    }

    private val _modifierList: PsiModifierList by lazyPub {
        FirLightClassModifierList(this, _modifiers, _annotations)
    }

    override fun getModifierList(): PsiModifierList = _modifierList

    override fun getReturnType(): PsiType? = null
}

internal abstract class FirLightMethodForFirNode(
    firFunction: KtFunctionLikeSymbol,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    methodIndex: Int,
    argumentsSkipMask: BitSet? = null
) : FirLightMethod(
    lightMemberOrigin,
    containingClass,
    methodIndex
) {
    override val isMangled: Boolean = false

    private var _isVarArgs: Boolean = firFunction.valueParameters.any { it.isVararg }

    override fun isVarArgs(): Boolean = _isVarArgs

    override fun getTypeParameters(): Array<PsiTypeParameter> = emptyArray() //TODO

    override fun hasTypeParameters(): Boolean = false //TODO

    override fun getTypeParameterList(): PsiTypeParameterList? = null //TODO

    private val _parametersList by lazyPub {
        val builder = LightParameterListBuilder(manager, language)

        FirLightParameterForReceiver.tryGet(firFunction, this)?.let {
            builder.addParameter(it)
        }

        firFunction.valueParameters.mapIndexed { index, parameter ->
            val needToSkip = argumentsSkipMask?.get(index) == true
            if (!needToSkip) {
                builder.addParameter(
                    FirLightParameterForFirNode(
                        parameter = parameter,
                        method = this@FirLightMethodForFirNode
                    )
                )
            }
        }

        builder
    }

    override fun getParameterList(): PsiParameterList = _parametersList

    override val kotlinOrigin: KtDeclaration? = firFunction.psi as? KtDeclaration

    override fun buildTypeParameterList(): PsiTypeParameterList =
        KotlinLightTypeParameterListBuilder(this) //TODO()

    override fun getThrowsList(): PsiReferenceList =
        KotlinLightReferenceListBuilder(manager, language, PsiReferenceList.Role.THROWS_LIST) //TODO()

    override fun getDefaultValue(): PsiAnnotationMemberValue? = null //TODO()
}