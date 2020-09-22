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
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.resolve.calls.isUnit
import org.jetbrains.kotlin.fir.types.ConeNullability
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
    firPropertyAccessor: FirPropertyAccessor,
    firContainingProperty: FirProperty,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    isTopLevel: Boolean
) : FirLightNotConstructorMethodForFirNode(
    firPropertyAccessor,
    lightMemberOrigin,
    containingClass,
    if (firPropertyAccessor.isGetter) METHOD_INDEX_FOR_GETTER else METHOD_INDEX_FOR_SETTER
) {
    private fun String.abiName(propertyAccessor: FirPropertyAccessor) =
        if (propertyAccessor.isGetter) getterName(this)
        else setterName(this)

    //TODO add JvmName
    private val _name: String by getAndAddLazy {
        firContainingProperty.name.identifier
            .abiName(firPropertyAccessor)
    }

    override fun getName(): String = _name

    override fun isVarArgs(): Boolean = false

    override val kotlinOrigin: KtDeclaration? =
        (firPropertyAccessor.psi ?: firContainingProperty.psi) as? KtDeclaration

    private val _annotations: List<PsiAnnotation> by getAndAddLazy {
        val accessorSite =
            if (firPropertyAccessor.isGetter) AnnotationUseSiteTarget.PROPERTY_GETTER
            else AnnotationUseSiteTarget.PROPERTY_SETTER
        firContainingProperty.computeAnnotations(this, ConeNullability.UNKNOWN, accessorSite)
    }

    private val _modifiers: Set<String> by getAndAddLazy {

        val modifiers = firPropertyAccessor.computeModalityForMethod(isTopLevel) +
                firPropertyAccessor.computeVisibility(isTopLevel)

        val isJvmStatic =
            _annotations.any { it.qualifiedName == "kotlin.jvm.JvmStatic" }

        if (isJvmStatic) return@getAndAddLazy modifiers + PsiModifier.STATIC

        modifiers
    }

    private val _modifierList: PsiModifierList by getAndAddLazy {
        FirLightClassModifierList(this, _modifiers, _annotations)
    }

    override fun getModifierList(): PsiModifierList = _modifierList
}

internal class FirLightSimpleMethodForFirNode(
    firFunction: FirSimpleFunction,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    methodIndex: Int,
    isTopLevel: Boolean,
    argumentsSkipMask: BitSet? = null
) : FirLightNotConstructorMethodForFirNode(
    firFunction = firFunction,
    lightMemberOrigin = lightMemberOrigin,
    containingClass = containingClass,
    methodIndex = methodIndex,
    argumentsSkipMask = argumentsSkipMask
) {

    private val _name: String by getAndAddLazy {
        firFunction.name.asString()
    }

    override fun getName(): String = _name


    private val _annotations: List<PsiAnnotation> by getAndAddLazy {
        firFunction.computeAnnotations(this, firFunction.returnTypeRef.nullabilityForJava)
    }

    private val _modifiers: Set<String> by getAndAddLazy {

        val isInlineOnly =
            _annotations.any { it.qualifiedName == "kotlin.internal.InlineOnly" }

        if (isInlineOnly) return@getAndAddLazy setOf(PsiModifier.FINAL, PsiModifier.PRIVATE)

        val modifiers = firFunction.computeModalityForMethod(isTopLevel = isTopLevel) +
                firFunction.computeVisibility(isTopLevel = isTopLevel)

        val isJvmStatic =
            _annotations.any { it.qualifiedName == "kotlin.jvm.JvmStatic" }

        if (isJvmStatic) return@getAndAddLazy modifiers + PsiModifier.STATIC

        modifiers
    }


    private val _modifierList: PsiModifierList by getAndAddLazy {
        FirLightClassModifierList(this, _modifiers, _annotations)
    }

    override fun getModifierList(): PsiModifierList = _modifierList
}

internal class FirLightConstructorForFirNode(
    firFunction: FirConstructor,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    methodIndex: Int,
) : FirLightMethodForFirNode(firFunction, lightMemberOrigin, containingClass, methodIndex) {

    private val _name: String? = containingClass.name

    override fun getName(): String = _name ?: ""

    override fun isConstructor(): Boolean = true

    private val _annotations: List<PsiAnnotation> by getAndAddLazy {
        firFunction.computeAnnotations(this, ConeNullability.UNKNOWN)
    }

    private val _modifiers: Set<String> by getAndAddLazy {
        setOf(firFunction.computeVisibility(isTopLevel = false))
    }

    private val _modifierList: PsiModifierList by getAndAddLazy {
        FirLightClassModifierList(this, _modifiers, _annotations)
    }

    override fun getModifierList(): PsiModifierList = _modifierList

    override fun getReturnType(): PsiType? = null
}

internal abstract class FirLightNotConstructorMethodForFirNode(
    firFunction: FirFunction<*>,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    methodIndex: Int,
    argumentsSkipMask: BitSet? = null
) : FirLightMethodForFirNode(
    firFunction = firFunction,
    lightMemberOrigin = lightMemberOrigin,
    containingClass = containingClass,
    methodIndex = methodIndex,
    argumentsSkipMask = argumentsSkipMask
) {

    init {
        require(firFunction !is FirConstructor)
    }

    override fun isConstructor(): Boolean = false

    private val _returnedType: PsiType? by getAndAddLazy {
        firFunction.returnTypeRef.coneTypeSafe?.run {
            if (isUnit) PsiType.VOID
            else asPsiType(firFunction.session, TypeMappingMode.DEFAULT, this@FirLightNotConstructorMethodForFirNode)
        }
    }

    override fun getReturnType(): PsiType? = _returnedType
}

internal abstract class FirLightMethodForFirNode(
    firFunction: FirFunction<*>,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    methodIndex: Int,
    argumentsSkipMask: BitSet? = null
) : FirLightMethod(
    lightMemberOrigin,
    containingClass,
    methodIndex
) {
    // This is greedy realization of UL class.
    // This means that all data that depends on descriptor evaluated in ctor so the descriptor will be released on the end.
    // Be aware to save descriptor in class instance or any depending references

    override val isMangled: Boolean = false

    private var _isVarArgs: Boolean = firFunction.valueParameters.any { it.isVararg }

    override fun isVarArgs(): Boolean = _isVarArgs

    override fun getTypeParameters(): Array<PsiTypeParameter> = emptyArray() //TODO

    override fun hasTypeParameters(): Boolean = false //TODO

    override fun getTypeParameterList(): PsiTypeParameterList? = null //TODO

    private val parameterListBuilder = LightParameterListBuilder(manager, language)
    override fun getParameterList(): PsiParameterList = parameterListBuilder

    override fun getBody(): PsiCodeBlock? = null
    override fun getReturnTypeElement(): PsiTypeElement? = null

    override val kotlinOrigin: KtDeclaration? = firFunction.psi as? KtDeclaration

    private val lazyInitializers = mutableListOf<Lazy<*>>()
    protected inline fun <T> getAndAddLazy(crossinline initializer: () -> T): Lazy<T> =
        lazyPub { initializer() }.also { lazyInitializers.add(it) }

    override fun buildTypeParameterList(): PsiTypeParameterList =
        KotlinLightTypeParameterListBuilder(this) //TODO()

    override fun getThrowsList(): PsiReferenceList =
        KotlinLightReferenceListBuilder(manager, language, PsiReferenceList.Role.THROWS_LIST) //TODO()

    override fun getDefaultValue(): PsiAnnotationMemberValue? = null //TODO()

    init {

        FirLightParameterForReceiver.tryGet(firFunction, this)?.let {
            parameterListBuilder.addParameter(it)
        }

        firFunction.valueParameters.mapIndexed { index, parameter ->
            val needToSkip = argumentsSkipMask?.get(index) == true
            if (!needToSkip) {
                parameterListBuilder.addParameter(
                    FirLightParameterForFirNode(
                        parameter = parameter,
                        method = this@FirLightMethodForFirNode
                    )
                )
            }
        }

//        methodDescriptor.extensionReceiverParameter?.let { receiver ->
//            //delegate.addParameter(KtUltraLightParameterForDescriptor(receiver, support, this))
//        }

//        for (valueParameter in methodDescriptor.valueParameters) {
        //delegate.addParameter(KtUltraLightParameterForDescriptor(valueParameter, support, this))
//        }


        //We should force computations on all lazy delegates to release descriptor on the end of ctor call
        with(lazyInitializers) {
            forEach { it.value }
            clear()
        }
    }
}