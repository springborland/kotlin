/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.asJava

import com.intellij.navigation.NavigationItem
import com.intellij.psi.*
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.util.IncorrectOperationException
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.asJava.elements.*
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.idea.frontend.api.symbols.*
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtAnnotatedSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtNamedSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtPossibleExtensionSymbol
import org.jetbrains.kotlin.idea.frontend.api.types.KtType
import org.jetbrains.kotlin.psi.KtParameter

internal abstract class FirLightParameter(containingDeclaration: FirLightMethod) : PsiVariable, NavigationItem,
    KtLightElement<KtParameter, PsiParameter>, KtLightParameter, KtLightElementBase(containingDeclaration) {

    override val clsDelegate: PsiParameter
        get() = invalidAccess()

    override val givenAnnotations: List<KtLightAbstractAnnotation>
        get() = invalidAccess()

    override fun getTypeElement(): PsiTypeElement? = null
    override fun getInitializer(): PsiExpression? = null
    override fun hasInitializer(): Boolean = false
    override fun computeConstantValue(): Any? = null
    override fun getNameIdentifier(): PsiIdentifier? = null

    abstract override fun getName(): String

    @Throws(IncorrectOperationException::class)
    override fun normalizeDeclaration() {
    }

    override fun setName(p0: String): PsiElement = TODO() //cannotModify()

    //KotlinIconProviderService.getInstance().getLightVariableIcon(this, flags)

    override val method: KtLightMethod = containingDeclaration

    override fun getDeclarationScope(): KtLightMethod = method

    override fun accept(visitor: PsiElementVisitor) {
        if (visitor is JavaElementVisitor) {
            visitor.visitParameter(this)
        }
    }

    override fun toString(): String = "Fir Light Parameter $name"

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        kotlinOrigin == another || another is FirLightParameterForFirNode && another.kotlinOrigin == kotlinOrigin

    override fun getNavigationElement(): PsiElement = kotlinOrigin ?: method.navigationElement

    override fun getUseScope(): SearchScope = kotlinOrigin?.useScope ?: LocalSearchScope(this)

    override fun isValid() = parent.isValid

    abstract override fun getType(): PsiType

    override fun getContainingFile(): PsiFile = method.containingFile

    override fun getParent(): PsiElement = method.parameterList

    override fun equals(other: Any?): Boolean =
        other is FirLightParameter && other.kotlinOrigin == this.kotlinOrigin

    override fun hashCode(): Int = kotlinOrigin.hashCode()

    abstract override fun isVarArgs(): Boolean
}


internal class FirLightParameterForFirNode(
    parameter: KtParameterSymbol,
    method: FirLightMethod
) : FirLightParameter(method) {
    private val _name: String = parameter.name.asString()
    override fun getName(): String = _name

    private val _isVarArgs: Boolean = parameter.isVararg
    override fun isVarArgs() = _isVarArgs
    override fun hasModifierProperty(name: String): Boolean =
        modifierList.hasModifierProperty(name)

    override val kotlinOrigin: KtParameter? = parameter.psi as? KtParameter

    private val _annotations: List<PsiAnnotation> by lazyPub {
        parameter.computeAnnotations(this, parameter.type.nullabilityType)
    }

    override fun getModifierList(): PsiModifierList = _modifierList
    private val _modifierList: PsiModifierList by lazyPub {
        FirLightClassModifierList(this, emptySet(), _annotations)
    }

    private val _type by lazyPub {
        parameter.asPsiType(this, FirResolvePhase.TYPES)
    }

    override fun getType(): PsiType = _type
}

internal class FirLightParameterForReceiver private constructor(
    firFunction: KtAnnotatedSymbol,
    type: KtType,
    methodName: String,
    method: FirLightMethod
) : FirLightParameter(method) {

    companion object {
        fun tryGet(
            callableSymbol: KtFunctionLikeSymbol,
            method: FirLightMethod
        ): FirLightParameterForReceiver? {

            if (callableSymbol !is KtNamedSymbol) return null
            if (callableSymbol !is KtAnnotatedSymbol) return null
            if (callableSymbol !is KtPossibleExtensionSymbol) return null

            if (!callableSymbol.isExtension) return null
            val receiverType = callableSymbol.receiverType ?: return null

            return FirLightParameterForReceiver(
                firFunction = callableSymbol,
                type = receiverType,
                methodName = callableSymbol.name.asString(),
                method = method
            )
        }
    }

    private val _name: String by lazyPub {
        AsmUtil.getLabeledThisName(methodName, AsmUtil.LABELED_THIS_PARAMETER, AsmUtil.RECEIVER_PARAMETER_NAME)
    }

    override fun getName(): String = _name

    override fun isVarArgs() = false
    override fun hasModifierProperty(name: String): Boolean = false //TODO()

    override val kotlinOrigin: KtParameter? = null

    private val _annotations: List<PsiAnnotation> by lazyPub {
        firFunction.computeAnnotations(this, type.nullabilityType, AnnotationUseSiteTarget.RECEIVER)
    }

    override fun getModifierList(): PsiModifierList = _modifierList
    private val _modifierList: PsiModifierList by lazyPub {
        FirLightClassModifierList(this, emptySet(), _annotations)
    }

    private val _type: PsiType by lazyPub {
        type.asPsiType(firFunction, method, FirResolvePhase.TYPES)
    }

    override fun getType(): PsiType = _type
}
