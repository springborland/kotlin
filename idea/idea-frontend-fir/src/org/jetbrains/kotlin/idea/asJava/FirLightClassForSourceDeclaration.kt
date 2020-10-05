/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.asJava

import com.intellij.openapi.util.Comparing
import com.intellij.psi.*
import com.intellij.psi.impl.InheritanceImplUtil
import com.intellij.psi.impl.PsiClassImplUtil
import com.intellij.psi.impl.PsiSuperMethodImplUtil
import com.intellij.psi.search.SearchScope
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.IncorrectOperationException
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.analyzer.KotlinModificationTrackerService
import org.jetbrains.kotlin.asJava.classes.*
import org.jetbrains.kotlin.asJava.elements.KtLightField
import org.jetbrains.kotlin.asJava.elements.KtLightIdentifier
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.StandardClassIds
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.idea.fir.low.level.api.api.LowLevelFirApiFacade
import org.jetbrains.kotlin.idea.util.ifTrue
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.structure.LightClassOriginKind
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.debugText.getDebugText
import org.jetbrains.kotlin.psi.psiUtil.isObjectLiteral
import org.jetbrains.kotlin.psi.stubs.KotlinClassOrObjectStub
import javax.swing.Icon

open class FirLightClassForSourceDeclaration(private val classOrObject: KtClassOrObject) :
    FirLightClassBase(classOrObject.manager),
    StubBasedPsiElement<KotlinClassOrObjectStub<out KtClassOrObject>> {

    private val _modifierList: PsiModifierList? by lazyPub {
        val modifiers = classOrObject.withFir<FirMemberDeclaration, Set<String>?> {
            (this as? FirMemberDeclaration)?.computeModifiers(classOrObject.isTopLevel())
        } ?: emptySet()

        FirLightClassModifierList(this, modifiers)
    }

    override fun getModifierList(): PsiModifierList? = _modifierList
    override fun getOwnFields(): List<KtLightField> = emptyList() //TODO()
    override fun getOwnMethods(): List<PsiMethod> = _ownMethods.value
    override fun isDeprecated(): Boolean = false //TODO()
    override fun getNameIdentifier(): KtLightIdentifier? = null //TODO()
    override fun getExtendsList(): PsiReferenceList? = _extendsList
    override fun getImplementsList(): PsiReferenceList? = _implementsList
    override fun getTypeParameterList(): PsiTypeParameterList? = null //TODO()
    override fun getTypeParameters(): Array<PsiTypeParameter> = emptyArray() //TODO()
    override fun getOwnInnerClasses(): List<PsiClass> = emptyList() //TODO()

    override fun getTextOffset() = kotlinOrigin.textOffset
    override fun getStartOffsetInParent() = kotlinOrigin.startOffsetInParent
    override fun isWritable() = kotlinOrigin.isWritable
    override val kotlinOrigin: KtClassOrObject = classOrObject

    private val _extendsList by lazyPub { createInheritanceList(forExtendsList = true) }
    private val _implementsList by lazyPub { createInheritanceList(forExtendsList = false) }

    private fun mapSupertype(session: FirSession, supertype: ConeKotlinType, kotlinCollectionAsIs: Boolean = false) =
        supertype.asPsiType(
            session,
            if (kotlinCollectionAsIs) TypeMappingMode.SUPER_TYPE_KOTLIN_COLLECTIONS_AS_IS else TypeMappingMode.SUPER_TYPE,
            this
        ) as? PsiClassType

    private fun ConeClassLikeType.isTypeForInheritanceList(session: FirSession, forExtendsList: Boolean): Boolean {

        // Do not add redundant "extends java.lang.Object" anywhere
        if (classId == StandardClassIds.Any) return false

        // We don't have Enum among enums supertype in sources neither we do for decompiled class-files and light-classes
        if (isEnum && classId == StandardClassIds.Enum) return false

        // Interfaces have only extends lists
        if (isInterface) return forExtendsList

        val isInterface = (lookupTag.toSymbol(session)?.fir as? FirClass)?.classKind == ClassKind.INTERFACE

        return forExtendsList == !isInterface
    }

    private fun createInheritanceList(forExtendsList: Boolean): PsiReferenceList? {

        val role = if (forExtendsList) PsiReferenceList.Role.EXTENDS_LIST else PsiReferenceList.Role.IMPLEMENTS_LIST

        if (isAnnotationType) return KotlinLightReferenceListBuilder(manager, language, role)

        val listBuilder = KotlinSuperTypeListBuilder(
            kotlinOrigin = kotlinOrigin.getSuperTypeList(),
            manager = manager,
            language = language,
            role = role
        )

        //TODO Add support for kotlin.collections.
        classOrObject.withFir<FirRegularClass, Unit> {
            this.superConeTypes.forEach {
                if (it.isTypeForInheritanceList(session, forExtendsList)) {
                    val psiType = mapSupertype(session, it, kotlinCollectionAsIs = true)
                    listBuilder.addReference(psiType)
                }
            }
        }

        return listBuilder
    }

    private fun ownMethods(): List<KtLightMethod> {
        var methodIndex = METHOD_INDEX_BASE
        return classOrObject.withFir<FirRegularClass, List<KtLightMethod>> {
            declarations.mapNotNull { declaration ->

                if (declaration !is FirFunction<*>) return@mapNotNull null

                if (declaration is FirMemberDeclaration &&
                    declaration.visibility == Visibilities.Private && isInterface) return@mapNotNull null


                FirLightMethodForFirNode(
                    firFunction = declaration,
                    lightMemberOrigin = null,
                    containingClass = this@FirLightClassForSourceDeclaration,
                    methodIndex++
                )
            }
        }
    }

    private val _ownMethods: CachedValue<List<KtLightMethod>> = CachedValuesManager.getManager(project).createCachedValue(
        {
            CachedValueProvider.Result.create(
                ownMethods(),
                classOrObject.getExternalDependencies()
            )
        }, false
    )

    private val _containingFile: PsiFile by lazyPub {
        val containingClass =
            (!classOrObject.isTopLevel()).ifTrue { create(getOutermostClassOrObject(classOrObject)) } ?: this
        FirFakeFileImpl(classOrObject, containingClass)
    }

    override fun getContainingFile(): PsiFile? = _containingFile

    override fun getNavigationElement(): PsiElement = classOrObject

    override fun isEquivalentTo(another: PsiElement?): Boolean {
        return kotlinOrigin.isEquivalentTo(another) ||
                another is FirLightClassForSourceDeclaration && Comparing.equal(another.qualifiedName, qualifiedName)
    }

    override fun getElementIcon(flags: Int): Icon? =
        throw UnsupportedOperationException("This should be done by JetIconProvider")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class.java != other::class.java) return false

        val aClass = other as FirLightClassForSourceDeclaration

        if (classOrObject != aClass.classOrObject) return false

        return true
    }

    override fun hashCode(): Int = classOrObject.hashCode()

    override fun getName(): String = classOrObject.nameAsName?.asString() ?: ""

    inline fun <reified T : FirDeclaration, K> KtClassOrObject.withFir(body: T.() -> K): K {
        val resolveState = LowLevelFirApiFacade.getResolveStateFor(this)
        return LowLevelFirApiFacade.withFirDeclaration(this, resolveState, FirResolvePhase.IMPLICIT_TYPES_BODY_RESOLVE) {
            require(it is T)
            it.body()
        }
    }

    override fun hasModifierProperty(@NonNls name: String): Boolean = modifierList?.hasModifierProperty(name) ?: false

    override fun isInterface(): Boolean =
        classOrObject is KtClass && (classOrObject.isInterface() || classOrObject.isAnnotation())

    override fun isAnnotationType(): Boolean =
        classOrObject is KtClass && classOrObject.isAnnotation()

    override fun isEnum(): Boolean =
        classOrObject is KtClass && classOrObject.isEnum()

    override fun hasTypeParameters(): Boolean =
        classOrObject is KtClass && classOrObject.typeParameters.isNotEmpty()

    override fun isValid(): Boolean = classOrObject.isValid

    override fun isInheritor(baseClass: PsiClass, checkDeep: Boolean): Boolean =
        InheritanceImplUtil.isInheritor(this, baseClass, checkDeep)

    @Throws(IncorrectOperationException::class)
    override fun setName(@NonNls name: String): PsiElement =
        throw IncorrectOperationException()

    override fun toString() =
        "${this::class.java.simpleName}:${classOrObject.getDebugText()}"

    override fun getUseScope(): SearchScope = kotlinOrigin.useScope
    override fun getElementType(): IStubElementType<out StubElement<*>, *>? = classOrObject.elementType
    override fun getStub(): KotlinClassOrObjectStub<out KtClassOrObject>? = classOrObject.stub

    override val originKind: LightClassOriginKind
        get() = LightClassOriginKind.SOURCE

    override fun getQualifiedName() = classOrObject.fqName?.asString()

    override fun getInterfaces(): Array<PsiClass> = PsiClassImplUtil.getInterfaces(this)
    override fun getSuperClass(): PsiClass? = PsiClassImplUtil.getSuperClass(this)
    override fun getSupers(): Array<PsiClass> = PsiClassImplUtil.getSupers(this)
    override fun getSuperTypes(): Array<PsiClassType> = PsiClassImplUtil.getSuperTypes(this)
    override fun getVisibleSignatures(): MutableCollection<HierarchicalMethodSignature> = PsiSuperMethodImplUtil.getVisibleSignatures(this)

    override fun getRBrace(): PsiElement? = null
    override fun getLBrace(): PsiElement? = null

    override fun getInitializers(): Array<PsiClassInitializer> = emptyArray()

    override fun getContainingClass(): PsiClass? {

        val containingBody = classOrObject.parent as? KtClassBody
        val containingClass = containingBody?.parent as? KtClassOrObject
        containingClass?.let { return create(it) }

        val containingBlock = classOrObject.parent as? KtBlockExpression
//        val containingScript = containingBlock?.parent as? KtScript
//        containingScript?.let { return KtLightClassForScript.create(it) }

        return null
    }

    override fun getParent(): PsiElement? = containingClass ?: containingFile

    override fun getScope(): PsiElement? = parent

    override fun isInheritorDeep(baseClass: PsiClass?, classToByPass: PsiClass?): Boolean =
        baseClass?.let { InheritanceImplUtil.isInheritorDeep(this, it, classToByPass) } ?: false

    override fun copy(): FirLightClassForSourceDeclaration =
        FirLightClassForSourceDeclaration(classOrObject.copy() as KtClassOrObject)

    companion object {
        fun create(classOrObject: KtClassOrObject): FirLightClassForSourceDeclaration? =
            CachedValuesManager.getCachedValue(classOrObject) {
                CachedValueProvider.Result
                    .create(
                        createNoCache(classOrObject),
                        KotlinModificationTrackerService.getInstance(classOrObject.project).outOfBlockModificationTracker
                    )
            }

        fun createNoCache(classOrObject: KtClassOrObject): FirLightClassForSourceDeclaration? {
            val containingFile = classOrObject.containingFile
            if (containingFile is KtCodeFragment) {
                // Avoid building light classes for code fragments
                return null
            }

            if (classOrObject.shouldNotBeVisibleAsLightClass()) {
                return null
            }

            return when {
                classOrObject.isObjectLiteral() -> return null //TODO
                classOrObject.safeIsLocal() -> return null //TODO
                classOrObject.hasModifier(KtTokens.INLINE_KEYWORD) -> return null //TODO
                else -> FirLightClassForSourceDeclaration(classOrObject)
            }
        }
    }
}