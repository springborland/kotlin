/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.asJava

import com.intellij.psi.*
import com.intellij.psi.impl.PsiImplUtil
import com.intellij.psi.impl.PsiSuperMethodImplUtil
import com.intellij.psi.impl.light.LightParameterListBuilder
import com.intellij.psi.impl.light.LightReferenceListBuilder
import com.intellij.psi.util.MethodSignature
import com.intellij.psi.util.MethodSignatureBackedByPsiMethod
import org.jetbrains.kotlin.asJava.builder.LightMemberOrigin
import org.jetbrains.kotlin.asJava.classes.*
import org.jetbrains.kotlin.asJava.elements.*
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.psi
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtTypeParameterList

abstract class FirLightMemberImpl<T : PsiMember>(
    override val lightMemberOrigin: LightMemberOrigin?,
    private val containingClass: KtLightClass,
) : KtLightElementBase(containingClass), PsiMember, KtLightMember<T> {

    override val clsDelegate: T
        get() = invalidAccess()

    private val lightIdentifier by lazyPub { KtLightIdentifier(this, kotlinOrigin as? KtNamedDeclaration) }

    override fun hasModifierProperty(name: String): Boolean = modifierList?.hasModifierProperty(name) ?: false

    //override fun getModifierList(): PsiModifierList? = _modifierList

    override fun toString(): String = "${this::class.java.simpleName}:$name"

    override fun getContainingClass() = containingClass

    override fun getNameIdentifier(): PsiIdentifier = lightIdentifier

    override val kotlinOrigin: KtDeclaration? get() = lightMemberOrigin?.originalElement

    override fun getDocComment() = null //TODO()

    override fun isDeprecated() = false //TODO()

    override fun getName(): String = "" //TODO()

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

    override fun hashCode(): Int = name.hashCode()
}

internal class FirLightMethodForFirNode(
    firFunction: FirFunction<*>,
    lightMemberOrigin: LightMemberOrigin?,
    containingClass: FirLightClassBase,
    methodIndex: Int,
) : FirLightMethod(
    lightMemberOrigin,
    containingClass,
    methodIndex
) {
    // This is greedy realization of UL class.
    // This means that all data that depends on descriptor evaluated in ctor so the descriptor will be released on the end.
    // Be aware to save descriptor in class instance or any depending references

    override val isMangled: Boolean = false

    override fun getTypeParameters(): Array<PsiTypeParameter> = emptyArray() //TODO

    override fun hasTypeParameters(): Boolean = false //TODO

    override fun getTypeParameterList(): PsiTypeParameterList? = null //TODO

    private val parameterListBuilder = LightParameterListBuilder(manager, language)
    override fun getParameterList(): PsiParameterList = parameterListBuilder

    override fun getBody(): PsiCodeBlock? = null
    override fun getReturnTypeElement(): PsiTypeElement? = null

    override val kotlinOrigin: KtDeclaration? = firFunction.psi as? KtDeclaration

    private val lazyInitializers = mutableListOf<Lazy<*>>()
    private inline fun <T> getAndAddLazy(crossinline initializer: () -> T): Lazy<T> =
        lazyPub { initializer() }.also { lazyInitializers.add(it) }

    override fun buildTypeParameterList(): PsiTypeParameterList =
        KotlinLightTypeParameterListBuilder(this) //TODO()

    override fun getThrowsList(): PsiReferenceList =
        KotlinLightReferenceListBuilder(manager, language, PsiReferenceList.Role.THROWS_LIST) //TODO()

    override fun getDefaultValue(): PsiAnnotationMemberValue? = null //TODO()

    private val _name: String = (firFunction as? FirSimpleFunction)?.name?.identifier ?: ""
    override fun getName(): String = _name

    private val _isConstructor = firFunction is FirConstructor
    override fun isConstructor(): Boolean = _isConstructor

    override fun getModifierList(): PsiModifierList = _modifierList
    private val _modifierList: PsiModifierList by getAndAddLazy {
        val modifiers = (firFunction as? FirMemberDeclaration)?.computeModifiers(isTopLevel = false) ?: emptySet()
        FirLightClassModifierList(this, modifiers)
    }

    override fun getReturnType(): PsiType? = _returnType
    private val _returnType: PsiType =
        if (firFunction !is FirConstructor)
            firFunction.returnTypeRef.coneType.asPsiType(firFunction.session, TypeMappingMode.DEFAULT, this)
        else PsiType.VOID



    init {
        firFunction.valueParameters.map {
            parameterListBuilder.addParameter(FirLightParameterForFirNode(it, this@FirLightMethodForFirNode))
        }


        //
//        firFunction.typeParameters.map {
//
//        }
//
//        firFunction.annotations.map {
//
//        }
//
//        firFunction.returnTypeRef



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