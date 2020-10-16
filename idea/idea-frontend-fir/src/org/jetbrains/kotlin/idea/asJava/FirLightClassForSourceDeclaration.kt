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
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.StandardClassIds
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.idea.frontend.api.analyze
import org.jetbrains.kotlin.idea.frontend.api.fir.analyzeWithSymbolAsContext
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirClassOrObjectSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.types.KtFirClassType
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtClassKind
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtClassOrObjectSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtFunctionSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtSymbolKind
import org.jetbrains.kotlin.idea.frontend.api.symbols.markers.KtSymbolVisibility
import org.jetbrains.kotlin.idea.util.ifFalse
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.structure.LightClassOriginKind
import org.jetbrains.kotlin.load.kotlin.TypeMappingMode
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.debugText.getDebugText
import org.jetbrains.kotlin.psi.psiUtil.isObjectLiteral
import org.jetbrains.kotlin.psi.stubs.KotlinClassOrObjectStub
import javax.swing.Icon
import kotlin.collections.ArrayList

open class FirLightClassForSourceDeclaration(
    private val classOrObject: KtClassOrObjectSymbol,
    manager: PsiManager
) :
    FirLightClassBase(manager),
    StubBasedPsiElement<KotlinClassOrObjectStub<out KtClassOrObject>> {

    private val isTopLevel: Boolean = classOrObject.symbolKind == KtSymbolKind.TOP_LEVEL

    private val _modifierList: PsiModifierList? by lazyPub {

        val modifiers = mutableSetOf(classOrObject.computeVisibility(isTopLevel))
        classOrObject.computeSimpleModality()?.run {
            modifiers.add(this)
        }
        if (!isTopLevel && !classOrObject.isInner) {
            modifiers.add(PsiModifier.STATIC)
        }

        val annotations = classOrObject.computeAnnotations(this@FirLightClassForSourceDeclaration)

        FirLightClassModifierList(this@FirLightClassForSourceDeclaration, modifiers, annotations)
    }

    override fun getModifierList(): PsiModifierList? = _modifierList
    override fun getOwnFields(): List<KtLightField> = _ownFields
    override fun getOwnMethods(): List<PsiMethod> = _ownMethods
    override fun isDeprecated(): Boolean = false //TODO()
    override fun getNameIdentifier(): KtLightIdentifier? = null //TODO()
    override fun getExtendsList(): PsiReferenceList? = _extendsList
    override fun getImplementsList(): PsiReferenceList? = _implementsList
    override fun getTypeParameterList(): PsiTypeParameterList? = null //TODO()
    override fun getTypeParameters(): Array<PsiTypeParameter> = emptyArray() //TODO()

    override fun getOwnInnerClasses(): List<PsiClass> {
        val result = ArrayList<PsiClass>()

        // workaround for ClassInnerStuffCache not supporting classes with null names, see KT-13927
        // inner classes with null names can't be searched for and can't be used from java anyway
        // we can't prohibit creating light classes with null names either since they can contain members

        analyzeWithSymbolAsContext(classOrObject) {
            classOrObject.getDeclaredMemberScope().getAllSymbols().filterIsInstance<KtClassOrObjectSymbol>().map {
                result.add(FirLightClassForSourceDeclaration(it, manager))
            }
        }

        //TODO
        //if (classOrObject.hasInterfaceDefaultImpls) {
        //    result.add(KtLightClassForInterfaceDefaultImpls(classOrObject))
        //}
        return result
    }

    override fun getTextOffset(): Int = kotlinOrigin?.textOffset ?: 0
    override fun getStartOffsetInParent(): Int = kotlinOrigin?.startOffsetInParent ?: 0
    override fun isWritable() = kotlinOrigin?.isWritable ?: false
    override val kotlinOrigin: KtClassOrObject? = classOrObject.psi as? KtClassOrObject

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
            kotlinOrigin = kotlinOrigin?.getSuperTypeList(),
            manager = manager,
            language = language,
            role = role
        )

        //TODO Add support for kotlin.collections.
        require(classOrObject is KtFirClassOrObjectSymbol)
        classOrObject.firRef.withFir {
            classOrObject.superTypes.map { type ->
                require(type is KtFirClassType)
                if (type.coneType.isTypeForInheritanceList(it.session, forExtendsList)) {
                    val psiType = mapSupertype(it.session, type.coneType, kotlinCollectionAsIs = true)
                    listBuilder.addReference(psiType)
                }
            }
        }

        return listBuilder
    }

    private val _ownMethods: List<KtLightMethod> by lazyPub {

        analyzeWithSymbolAsContext(classOrObject) {

            //TODO filterNot { it.isHiddenByDeprecation(support) }
            val callableSymbols = classOrObject.getDeclaredMemberScope().getCallableSymbols()
            val visibleDeclarations = callableSymbols.filterNot {
                isInterface && it is KtFunctionSymbol && it.visibility == KtSymbolVisibility.PRIVATE
            }

            mutableListOf<KtLightMethod>().also {
                createMethods(visibleDeclarations, isTopLevel = false, it)
            }
        }
    }

    private val _ownFields: List<KtLightField> by lazyPub {

        val result = mutableListOf<KtLightField>()

        classOrObject.companionObject?.run {
            result.add(FirLightFieldForFirObjectNode(this, this@FirLightClassForSourceDeclaration, null))
        }

        val isNamedObject = classOrObject.classKind == KtClassKind.OBJECT
        if (isNamedObject && classOrObject.symbolKind != KtSymbolKind.LOCAL) {
            result.add(FirLightFieldForFirObjectNode(classOrObject, this@FirLightClassForSourceDeclaration, null))
        }

        analyzeWithSymbolAsContext(classOrObject) {
            val callableSymbols = classOrObject.getDeclaredMemberScope().getCallableSymbols()
            createFields(callableSymbols, isTopLevel = false, result)
        }

        result

//        this.classOrObject.companionObjects.firstOrNull()?.let { companion ->
//            result.add(
//                KtUltraLightFieldForSourceDeclaration(
//                    companion,
//                    generateUniqueMemberName(companion.name.orEmpty(), usedNames),
//                    this,
//                    support,
//                    setOf(PsiModifier.STATIC, PsiModifier.FINAL, companion.simpleVisibility())
//                )
//            )
//
//            for (property in companion.declarations.filterIsInstance<KtProperty>()) {
//                if (isInterface && !property.isConstOrJvmField()) continue
//                membersBuilder.createPropertyField(property, usedNames, true)?.let(result::add)
//            }
//        }
//
//
//        for (parameter in propertyParameters()) {
//            membersBuilder.createPropertyField(parameter, usedNames, forceStatic = false)?.let(result::add)
//        }
//
//        if (!isInterface) {
//            val isCompanion = this.classOrObject is KtObjectDeclaration && this.classOrObject.isCompanion()
//            for (property in this.classOrObject.declarations.filterIsInstance<KtProperty>()) {
//                // All fields for companion object of classes are generated to the containing class
//                // For interfaces, only @JvmField-annotated properties are generated to the containing class
//                // Probably, the same should work for const vals but it doesn't at the moment (see KT-28294)
//                if (isCompanion && (containingClass?.isInterface == false || property.isJvmField())) continue
//
//                membersBuilder.createPropertyField(property, usedNames, forceStatic = this.classOrObject is KtObjectDeclaration)
//                    ?.let(result::add)
//            }
//        }
//
    }

    private val _containingFile: PsiFile? by lazyPub {

        val kotlinOrigin = kotlinOrigin ?: return@lazyPub null

        val containingClass = isTopLevel.ifFalse { create(getOutermostClassOrObject(kotlinOrigin)) } ?: this

        FirFakeFileImpl(kotlinOrigin, containingClass)
    }

    override fun getContainingFile(): PsiFile? = _containingFile

    override fun getNavigationElement(): PsiElement = kotlinOrigin ?: this

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        (kotlinOrigin?.isEquivalentTo(another) ?: false) ||
                another is FirLightClassForSourceDeclaration && Comparing.equal(another.qualifiedName, qualifiedName)

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

    override fun getName(): String = classOrObject.name.asString()

    override fun hasModifierProperty(@NonNls name: String): Boolean = modifierList?.hasModifierProperty(name) ?: false

    override fun isInterface(): Boolean =
        classOrObject is KtClass && (classOrObject.isInterface() || classOrObject.isAnnotation())

    override fun isAnnotationType(): Boolean =
        classOrObject is KtClass && classOrObject.isAnnotation()

    override fun isEnum(): Boolean =
        classOrObject is KtClass && classOrObject.isEnum()

    override fun hasTypeParameters(): Boolean =
        classOrObject is KtClass && classOrObject.typeParameters.isNotEmpty()

    override fun isValid(): Boolean = kotlinOrigin?.isValid ?: true

    override fun isInheritor(baseClass: PsiClass, checkDeep: Boolean): Boolean =
        InheritanceImplUtil.isInheritor(this, baseClass, checkDeep)

    @Throws(IncorrectOperationException::class)
    override fun setName(@NonNls name: String): PsiElement =
        throw IncorrectOperationException()

    override fun toString() =
        "${this::class.java.simpleName}:${kotlinOrigin?.getDebugText()}"

    override fun getUseScope(): SearchScope = kotlinOrigin?.useScope ?: TODO()
    override fun getElementType(): IStubElementType<out StubElement<*>, *>? = kotlinOrigin?.elementType
    override fun getStub(): KotlinClassOrObjectStub<out KtClassOrObject>? = kotlinOrigin?.stub

    override val originKind: LightClassOriginKind
        get() = LightClassOriginKind.SOURCE

    override fun getQualifiedName() = kotlinOrigin?.fqName?.asString()

    override fun getInterfaces(): Array<PsiClass> = PsiClassImplUtil.getInterfaces(this)
    override fun getSuperClass(): PsiClass? = PsiClassImplUtil.getSuperClass(this)
    override fun getSupers(): Array<PsiClass> = PsiClassImplUtil.getSupers(this)
    override fun getSuperTypes(): Array<PsiClassType> = PsiClassImplUtil.getSuperTypes(this)
    override fun getVisibleSignatures(): MutableCollection<HierarchicalMethodSignature> = PsiSuperMethodImplUtil.getVisibleSignatures(this)

    override fun getRBrace(): PsiElement? = null
    override fun getLBrace(): PsiElement? = null

    override fun getInitializers(): Array<PsiClassInitializer> = emptyArray()

    override fun getContainingClass(): PsiClass? {

        val containingBody = kotlinOrigin?.parent as? KtClassBody
        val containingClass = containingBody?.parent as? KtClassOrObject
        containingClass?.let { return create(it) }

        val containingBlock = kotlinOrigin?.parent as? KtBlockExpression
//        val containingScript = containingBlock?.parent as? KtScript
//        containingScript?.let { return KtLightClassForScript.create(it) }

        return null
    }

    override fun getParent(): PsiElement? = containingClass ?: containingFile

    override fun getScope(): PsiElement? = parent

    override fun isInheritorDeep(baseClass: PsiClass?, classToByPass: PsiClass?): Boolean =
        baseClass?.let { InheritanceImplUtil.isInheritorDeep(this, it, classToByPass) } ?: false

    override fun copy(): FirLightClassForSourceDeclaration =
        FirLightClassForSourceDeclaration(classOrObject, manager)

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
                else -> FirLightClassForSourceDeclaration(
                    analyze(classOrObject) { classOrObject.getClassOrObjectSymbol() },
                    manager = classOrObject.manager
                )
            }
        }
    }
}