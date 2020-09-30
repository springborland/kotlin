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
    localClassesNavigationInfo: LocalClassesNavigationInfo
): F {
    val statusComputationSession = StatusComputationSession.ForLocalClassResolution(localClassesNavigationInfo.parentForClass.keys)
    val transformer = FirStatusResolveTransformer(
        session,
        scopeSession,
        statusComputationSession
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
    statusComputationSession: StatusComputationSession
) : AbstractFirStatusResolveTransformer(session, scopeSession, statusComputationSession) {
    override fun FirDeclaration.needResolve(): Boolean {
        return true
    }
}

private class FirDesignatedStatusResolveTransformer(
    session: FirSession,
    scopeSession: ScopeSession,
    private val designation: Iterator<FirDeclaration>,
    private val targetClass: FirClass<*>,
    statusComputationSession: StatusComputationSession
) : AbstractFirStatusResolveTransformer(session, scopeSession, statusComputationSession) {
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

    override fun alreadyResolved(declaration: FirCallableMemberDeclaration<*>): Boolean {
        return !classLocated
    }
}

sealed class StatusComputationSession {
    abstract operator fun get(klass: FirClass<*>): StatusComputationStatus

    abstract fun endComputing(klass: FirClass<*>)

    enum class StatusComputationStatus {
        NotComputed, Computed
    }

    class Regular : StatusComputationSession() {
        private val classesWithComputedStatus = mutableSetOf<FirClass<*>>()

        override fun get(klass: FirClass<*>): StatusComputationStatus = if (klass in classesWithComputedStatus) {
            StatusComputationStatus.Computed
        } else {
            StatusComputationStatus.NotComputed
        }

        override fun endComputing(klass: FirClass<*>) {
            classesWithComputedStatus += klass
        }
    }

    class ForLocalClassResolution(private val localClasses: Set<FirClass<*>>) : StatusComputationSession() {
        private val delegate = Regular()

        override fun get(klass: FirClass<*>): StatusComputationStatus {
            if (klass !in localClasses) return StatusComputationStatus.Computed
            return delegate[klass]
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
    protected val designationMapForLocalClasses: Map<FirClass<*>, FirClass<*>?> = mapOf()
) : FirAbstractTreeTransformer<FirResolvedDeclarationStatus?>(phase = FirResolvePhase.STATUS) {
    private val classes = mutableListOf<FirClass<*>>()
    private val statusResolver = FirStatusResolver(session, scopeSession)

    private val containingClass: FirClass<*>? get() = classes.lastOrNull()

    private val firProvider = session.firProvider
    private val symbolProvider = session.firSymbolProvider

    protected abstract fun FirDeclaration.needResolve(): Boolean

    protected open fun alreadyResolved(declaration: FirCallableMemberDeclaration<*>): Boolean {
        val containingClass = containingClass
        val result = containingClass != null &&
                statusComputationSession[containingClass] == StatusComputationSession.StatusComputationStatus.Computed
        if (result) {
            assert(declaration.status is FirResolvedDeclarationStatus) {
                "foo"
            }
        }
        return result
    }

    override fun transformFile(file: FirFile, data: FirResolvedDeclarationStatus?): CompositeTransformResult<FirFile> {
        if (!file.needResolve()) return file.compose()
        return super.transformFile(file, data)
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
        forceResolveStatusesOfSupertypes(regularClass)
        updateResolvePhaseOfMembers(regularClass)
        regularClass.transformStatus(this, statusResolver.resolveStatus(regularClass, containingClass, isLocal = false))
        @Suppress("UNCHECKED_CAST")
        return storeClass(regularClass) {
            regularClass.typeParameters.forEach { it.transformSingle(this, data) }
            regularClass.replaceResolvePhase(transformerPhase)
            for (declaration in regularClass.declarations) {
                if (declaration !is FirClassLikeDeclaration<*>) {
                    declaration.transformSingle(this, data)
                }
            }
            for (declaration in regularClass.declarations) {
                if (declaration is FirClassLikeDeclaration<*>) {
                    declaration.transformSingle(this, data)
                }
            }
            regularClass.compose()
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
            val superClass = symbolProvider.getClassLikeSymbolByFqName(classId)?.fir as? FirRegularClass ?: continue
            forceResolveStatusesOfClass(superClass)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun forceResolveStatusesOfClass(regularClass: FirRegularClass) {
        if (statusComputationSession[regularClass] == StatusComputationSession.StatusComputationStatus.Computed) return
        if (regularClass.status is FirResolvedDeclarationStatus) {
            statusComputationSession.endComputing(regularClass)
            return
        }
        val symbol = regularClass.symbol
        val designation = designationMapForLocalClasses[regularClass]?.let(::listOf) ?: buildList<FirDeclaration> {
            val file = firProvider.getFirClassifierContainerFile(regularClass.symbol)
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
            statusComputationSession
        )
        designation.first().transformSingle(transformer, null)
        statusComputationSession.endComputing(regularClass)
    }

    override fun transformAnonymousObject(
        anonymousObject: FirAnonymousObject,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirStatement> {
        @Suppress("UNCHECKED_CAST")
        return storeClass(anonymousObject) {
            transformDeclaration(anonymousObject, data)
        } as CompositeTransformResult<FirStatement>
    }

    override fun transformPropertyAccessor(
        propertyAccessor: FirPropertyAccessor,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        if (alreadyResolved(propertyAccessor)) return propertyAccessor.compose()
        propertyAccessor.transformStatus(this, statusResolver.resolveStatus(propertyAccessor, containingClass, isLocal = false))
        @Suppress("UNCHECKED_CAST")
        return transformDeclaration(propertyAccessor, data)
    }

    override fun transformConstructor(
        constructor: FirConstructor,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        if (alreadyResolved(constructor)) return constructor.compose()
        constructor.transformStatus(this, statusResolver.resolveStatus(constructor, containingClass, isLocal = false))
        return transformDeclaration(constructor, data)
    }

    override fun transformSimpleFunction(
        simpleFunction: FirSimpleFunction,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        if (alreadyResolved(simpleFunction)) return simpleFunction.compose()
        simpleFunction.replaceResolvePhase(transformerPhase)
        simpleFunction.transformStatus(this, statusResolver.resolveStatus(simpleFunction, containingClass, isLocal = false))
        return transformDeclaration(simpleFunction, data)
    }

    override fun transformProperty(
        property: FirProperty,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        if (alreadyResolved(property)) return property.compose()
        property.replaceResolvePhase(transformerPhase)
        property.transformStatus(this, statusResolver.resolveStatus(property, containingClass, isLocal = false))
        return transformDeclaration(property, data)
    }

    override fun transformField(
        field: FirField,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
        if (alreadyResolved(field)) return field.compose()
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
