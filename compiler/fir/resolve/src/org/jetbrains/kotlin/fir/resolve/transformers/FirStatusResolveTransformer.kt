/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.transformers

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.render
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.firProvider
import org.jetbrains.kotlin.fir.resolve.firSymbolProvider
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.LocalClassesNavigationInfo
import org.jetbrains.kotlin.fir.scopes.FirCompositeScope
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.processClassifiersByName
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.CompositeTransformResult
import org.jetbrains.kotlin.fir.visitors.compose
import org.jetbrains.kotlin.fir.visitors.transformSingle

@OptIn(AdapterForResolveProcessor::class)
class FirStatusResolveProcessor(
    session: FirSession,
    scopeSession: ScopeSession
) : FirTransformerBasedResolveProcessor(session, scopeSession) {
    override val transformer = run {
        val statusComputationSession = StatusComputationSession.Regular()
        FirStatusResolveTransformer(
            session,
            scopeSession,
            statusComputationSession
        )
    }
}

fun <F : FirClass<F>> F.runStatusResolveForLocalClass(
    session: FirSession,
    scopeSession: ScopeSession,
    scopesForLocalClass: List<FirScope>,
    localClassesNavigationInfo: LocalClassesNavigationInfo
): F {
    val statusComputationSession = StatusComputationSession.ForLocalClassResolution(localClassesNavigationInfo.parentForClass.keys)
    val transformer = FirStatusResolveTransformer(
        session,
        scopeSession,
        statusComputationSession,
        localClassesNavigationInfo.parentForClass,
        FirCompositeScope(scopesForLocalClass)
    )

    return this.transform<F, Nothing?>(transformer, null).single
}

abstract class ResolvedStatusCalculator {
    abstract fun tryCalculateResolvedStatus(declaration: FirCallableMemberDeclaration<*>): FirResolvedDeclarationStatus

    object Default : ResolvedStatusCalculator() {
        override fun tryCalculateResolvedStatus(declaration: FirCallableMemberDeclaration<*>): FirResolvedDeclarationStatus {
            val status = declaration.status
            require(status is FirResolvedDeclarationStatus) {
                "Status of ${declaration.render()} is unresolved"
            }
            return status
        }
    }
}

class FirStatusResolveTransformer(
    session: FirSession,
    scopeSession: ScopeSession,
    statusComputationSession: StatusComputationSession,
    designationMapForLocalClasses: Map<FirClass<*>, FirClass<*>?> = mapOf(),
    scopeForLocalClass: FirScope? = null,
) : AbstractFirStatusResolveTransformer(session, scopeSession, statusComputationSession, designationMapForLocalClasses, scopeForLocalClass) {
    override fun FirDeclaration.needResolve(): Boolean {
        return true
    }

    override fun FirDeclaration.needResolveMembers(): Boolean {
        return true
    }

    override fun FirDeclaration.needResolveNestedClassifiers(): Boolean {
        return true
    }
}

private class FirDesignatedStatusResolveTransformer(
    session: FirSession,
    scopeSession: ScopeSession,
    private val designation: Iterator<FirDeclaration>,
    private val targetClass: FirClass<*>,
    statusComputationSession: StatusComputationSession,
    designationMapForLocalClasses: Map<FirClass<*>, FirClass<*>?>,
    scopeForLocalClass: FirScope?,
) : AbstractFirStatusResolveTransformer(session, scopeSession, statusComputationSession, designationMapForLocalClasses, scopeForLocalClass) {
    private var currentElement: FirDeclaration? = null
    private var classLocated = false

    override fun FirDeclaration.needResolve(): Boolean {
        if (classLocated) return false
        if (currentElement == null && designation.hasNext()) {
            currentElement = designation.next()
        }
        val result = currentElement == this
        if (result) {
            if (currentElement == targetClass) {
                classLocated = true
            }
            currentElement = null
        }
        return result
    }

    override fun FirDeclaration.needResolveMembers(): Boolean {
        return classLocated
    }

    override fun FirDeclaration.needResolveNestedClassifiers(): Boolean {
        return !classLocated
    }
}

sealed class StatusComputationSession {
    abstract operator fun get(klass: FirClass<*>): StatusComputationStatus

    abstract fun startComputing(klass: FirClass<*>)
    abstract fun endComputing(klass: FirClass<*>)

    enum class StatusComputationStatus {
        NotComputed, Computing, Computed
    }

    class Regular : StatusComputationSession() {
        private val statusMap = mutableMapOf<FirClass<*>, StatusComputationStatus>()
            .withDefault { StatusComputationStatus.NotComputed }

        override fun get(klass: FirClass<*>): StatusComputationStatus = statusMap.getValue(klass)

        override fun startComputing(klass: FirClass<*>) {
            statusMap[klass] = StatusComputationStatus.Computing
        }

        override fun endComputing(klass: FirClass<*>) {
            statusMap[klass] = StatusComputationStatus.Computed
        }
    }

    class ForLocalClassResolution(private val localClasses: Set<FirClass<*>>) : StatusComputationSession() {
        private val delegate = Regular()

        override fun get(klass: FirClass<*>): StatusComputationStatus {
            if (klass !in localClasses) return StatusComputationStatus.Computed
            return delegate[klass]
        }

        override fun startComputing(klass: FirClass<*>) {
            delegate.startComputing(klass)
        }

        override fun endComputing(klass: FirClass<*>) {
            delegate.endComputing(klass)
        }
    }
}

abstract class AbstractFirStatusResolveTransformer(
    final override val session: FirSession,
    val scopeSession: ScopeSession,
    protected val statusComputationSession: StatusComputationSession,
    protected val designationMapForLocalClasses: Map<FirClass<*>, FirClass<*>?>,
    private val scopeForLocalClass: FirScope?
) : FirAbstractTreeTransformer<FirResolvedDeclarationStatus?>(phase = FirResolvePhase.STATUS) {
    private val classes = mutableListOf<FirClass<*>>()
    private val statusResolver = FirStatusResolver(session, scopeSession)

    private val containingClass: FirClass<*>? get() = classes.lastOrNull()

    private val firProvider = session.firProvider
    private val symbolProvider = session.firSymbolProvider

    protected abstract fun FirDeclaration.needResolve(): Boolean
    protected abstract fun FirDeclaration.needResolveMembers(): Boolean
    protected abstract fun FirDeclaration.needResolveNestedClassifiers(): Boolean

    override fun transformFile(file: FirFile, data: FirResolvedDeclarationStatus?): CompositeTransformResult<FirFile> {
        if (!file.needResolve()) return file.compose()
        file.replaceResolvePhase(transformerPhase)
        if (file.needResolveMembers()) {
            for (declaration in file.declarations) {
                if (declaration !is FirClassLikeDeclaration<*>) {
                    declaration.transformSingle(this, data)
                }
            }
        }
        if (file.needResolveNestedClassifiers()) {
            for (declaration in file.declarations) {
                if (declaration is FirClassLikeDeclaration<*>) {
                    declaration.transformSingle(this, data)
                }
            }
        }
        return file.compose()
    }

    override fun transformDeclarationStatus(
        declarationStatus: FirDeclarationStatus,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclarationStatus> {
        return (data ?: declarationStatus).compose()
    }

    private inline fun storeClass(
        klass: FirClass<*>,
        computeResult: () -> CompositeTransformResult<FirDeclaration>
    ): CompositeTransformResult<FirDeclaration> {
        classes += klass
        val result = computeResult()
        classes.removeAt(classes.lastIndex)
        return result
    }

    override fun transformDeclaration(
        declaration: FirDeclaration,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        declaration.replaceResolvePhase(transformerPhase)
        return when (declaration) {
            is FirCallableDeclaration<*> -> {
                when (declaration) {
                    is FirProperty -> {
                        declaration.getter?.let { transformPropertyAccessor(it, data) }
                        declaration.setter?.let { transformPropertyAccessor(it, data) }
                    }
                    is FirFunction<*> -> {
                        for (valueParameter in declaration.valueParameters) {
                            transformValueParameter(valueParameter, data)
                        }
                    }
                }
                declaration.compose()
            }
            is FirPropertyAccessor -> {
                declaration.compose()
            }
            else -> {
                transformElement(declaration, data)
            }
        }
    }

    override fun transformTypeAlias(
        typeAlias: FirTypeAlias,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        typeAlias.typeParameters.forEach { transformDeclaration(it, data) }
        typeAlias.transformStatus(this, statusResolver.resolveStatus(typeAlias, containingClass, isLocal = false))
        return transformDeclaration(typeAlias, data)
    }

    override fun transformRegularClass(
        regularClass: FirRegularClass,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirStatement> {
        if (!regularClass.needResolve()) return regularClass.compose()
        if (statusComputationSession[regularClass] == StatusComputationSession.StatusComputationStatus.Computing) return regularClass.compose()
        statusComputationSession.startComputing(regularClass)
        forceResolveStatusesOfSupertypes(regularClass)
        updateResolvePhaseOfMembers(regularClass)
        regularClass.transformStatus(this, statusResolver.resolveStatus(regularClass, containingClass, isLocal = false))
        return transformClass(regularClass, data)
    }

    override fun transformAnonymousObject(
        anonymousObject: FirAnonymousObject,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirStatement> {
        @Suppress("UNCHECKED_CAST")
        return transformClass(anonymousObject, data)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <F : FirClass<F>> transformClass(
        klass: FirClass<F>,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirStatement> {
        return storeClass(klass) {
            klass.typeParameters.forEach { it.transformSingle(this, data) }
            klass.replaceResolvePhase(transformerPhase)
            if (klass.needResolveMembers()) {
                val members = klass.declarations.filter { it !is FirClassLikeDeclaration<*> }
                members.forEach { it.replaceResolvePhase(transformerPhase) }
                members.forEach { it.transformSingle(this, data) }
            }
            if (klass.needResolveNestedClassifiers()) {
                for (declaration in klass.declarations) {
                    if (declaration is FirClassLikeDeclaration<*>) {
                        declaration.transformSingle(this, data)
                    }
                }
            }
            klass.compose()
        } as CompositeTransformResult<FirStatement>
    }

    private fun updateResolvePhaseOfMembers(regularClass: FirRegularClass) {
        for (declaration in regularClass.declarations) {
            if (declaration is FirProperty || declaration is FirSimpleFunction) {
                declaration.replaceResolvePhase(transformerPhase)
            }
        }
    }

    private fun forceResolveStatusesOfSupertypes(regularClass: FirRegularClass) {
        for (superTypeRef in regularClass.superTypeRefs) {
            val classId = superTypeRef.coneType.classId ?: continue

            val superClass = when {
                classId.isLocal -> {
                    var parent = designationMapForLocalClasses[regularClass] as? FirRegularClass
                    if (parent == null && scopeForLocalClass != null) {
                        scopeForLocalClass.processClassifiersByName(classId.shortClassName) {
                            if (it is FirRegularClass && it.classId == classId) {
                                parent = it
                            }
                        }
                    }
                    parent
                }
                else -> symbolProvider.getClassLikeSymbolByFqName(classId)?.fir as? FirRegularClass
            } ?: continue

            forceResolveStatusesOfClass(superClass)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun forceResolveStatusesOfClass(regularClass: FirRegularClass) {
        if (statusComputationSession[regularClass] == StatusComputationSession.StatusComputationStatus.Computed) return
        val file = firProvider.getFirClassifierContainerFileIfAny(regularClass.symbol)
        if (regularClass.status is FirResolvedDeclarationStatus) {
            statusComputationSession.endComputing(regularClass)
            /*
             * If regular class has no corresponding file then it is platform class,
             *   so we need to resolve supertypes of this class because they could
             *   come from kotlin sources
             */
            if (file == null) {
                forceResolveStatusesOfSupertypes(regularClass)
            }
            return
        }
        require(file != null)
        val symbol = regularClass.symbol
        val designation = designationMapForLocalClasses[regularClass]?.let(::listOf) ?: buildList<FirDeclaration> {
            val outerClasses = generateSequence(symbol.classId) { classId ->
                classId.outerClassId
            }.mapTo(mutableListOf()) { firProvider.getFirClassifierByFqName(it) }
            this += file
            this += outerClasses.filterNotNull().asReversed()
        }
        if (designation.isEmpty()) return

        val transformer = FirDesignatedStatusResolveTransformer(
            session,
            scopeSession,
            designation.iterator(),
            regularClass,
            statusComputationSession,
            designationMapForLocalClasses,
            scopeForLocalClass
        )
        designation.first().transformSingle(transformer, null)
        statusComputationSession.endComputing(regularClass)
    }

    override fun transformPropertyAccessor(
        propertyAccessor: FirPropertyAccessor,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        propertyAccessor.transformStatus(this, statusResolver.resolveStatus(propertyAccessor, containingClass, isLocal = false))
        @Suppress("UNCHECKED_CAST")
        return transformDeclaration(propertyAccessor, data)
    }

    override fun transformConstructor(
        constructor: FirConstructor,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        constructor.transformStatus(this, statusResolver.resolveStatus(constructor, containingClass, isLocal = false))
        return transformDeclaration(constructor, data)
    }

    override fun transformSimpleFunction(
        simpleFunction: FirSimpleFunction,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        simpleFunction.replaceResolvePhase(transformerPhase)
        simpleFunction.transformStatus(this, statusResolver.resolveStatus(simpleFunction, containingClass, isLocal = false))
        return transformDeclaration(simpleFunction, data)
    }

    override fun transformProperty(
        property: FirProperty,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        property.replaceResolvePhase(transformerPhase)
        property.transformStatus(this, statusResolver.resolveStatus(property, containingClass, isLocal = false))
        return transformDeclaration(property, data)
    }

    override fun transformField(
        field: FirField,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        field.transformStatus(this, statusResolver.resolveStatus(field, containingClass, isLocal = false))
        return transformDeclaration(field, data)
    }

    override fun transformEnumEntry(
        enumEntry: FirEnumEntry,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        enumEntry.transformStatus(this, statusResolver.resolveStatus(enumEntry, containingClass, isLocal = false))
        return transformDeclaration(enumEntry, data)
    }

    override fun transformValueParameter(
        valueParameter: FirValueParameter,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirStatement> {
        @Suppress("UNCHECKED_CAST")
        return transformDeclaration(valueParameter, data) as CompositeTransformResult<FirStatement>
    }

    override fun transformTypeParameter(
        typeParameter: FirTypeParameter,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        return transformDeclaration(typeParameter, data)
    }

    override fun transformBlock(block: FirBlock, data: FirResolvedDeclarationStatus?): CompositeTransformResult<FirStatement> {
        return block.compose()
    }
}
