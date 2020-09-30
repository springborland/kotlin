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
import org.jetbrains.kotlin.fir.visitors.CompositeTransformResult
import org.jetbrains.kotlin.fir.visitors.compose
import org.jetbrains.kotlin.fir.visitors.transformSingle

@OptIn(AdapterForResolveProcessor::class)
class FirStatusResolveProcessor(
    session: FirSession,
    scopeSession: ScopeSession
) : FirTransformerBasedResolveProcessor(session, scopeSession) {
    override val transformer = FirStatusResolveTransformer(session, scopeSession, ResolvedStatusCalculatorWithJumps(session, scopeSession))
}

fun <F : FirClass<F>> F.runStatusResolveForLocalClass(
    session: FirSession,
    scopeSession: ScopeSession,
    designationMapForLocalClasses: Map<FirCallableMemberDeclaration<*>, List<FirClass<*>>>
): F {
    val transformer = FirStatusResolveTransformer(
        session,
        scopeSession,
        ResolvedStatusCalculatorWithJumps(session, scopeSession, designationMapForLocalClasses)
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

private class ResolvedStatusCalculatorWithJumps(
    private val session: FirSession,
    private val scopeSession: ScopeSession,
    val designationMapForLocalClasses: Map<FirCallableMemberDeclaration<*>, List<FirClass<*>>> = mapOf()
) : ResolvedStatusCalculator() {
    private val firProvider = session.firProvider

    @OptIn(ExperimentalStdlibApi::class)
    override fun tryCalculateResolvedStatus(declaration: FirCallableMemberDeclaration<*>): FirResolvedDeclarationStatus {
        // TODO: remove
        val declaration = declaration.symbol.overriddenSymbol?.fir ?: declaration

        val status = declaration.status
        if (status is FirResolvedDeclarationStatus) return status
        val symbol = declaration.symbol
        val designation = designationMapForLocalClasses[declaration] ?: buildList<FirDeclaration> {
            val file = firProvider.getFirCallableContainerFile(symbol) ?: TODO()

            val outerClasses = generateSequence(symbol.callableId.classId) { classId ->
                classId.outerClassId
            }.mapTo(mutableListOf()) { firProvider.getFirClassifierByFqName(it) }
            this += file
            this += outerClasses.filterNotNull().asReversed()
            this += declaration
        }

        val transformer = FirDesignatedStatusResolveTransformer(session, scopeSession, this, designation.iterator())
        designation.first().transformSingle(transformer, null)
        val newStatus = declaration.status
        require(newStatus is FirResolvedDeclarationStatus)
        return newStatus
    }
}

class FirStatusResolveTransformer(
    session: FirSession,
    scopeSession: ScopeSession,
    resolvedStatusCalculator: ResolvedStatusCalculator
) : AbstractFirStatusResolveTransformer(session, scopeSession, resolvedStatusCalculator) {
    override fun FirDeclaration.needResolve(): Boolean {
        return true
    }
}

private class FirDesignatedStatusResolveTransformer(
    session: FirSession,
    scopeSession: ScopeSession,
    resolvedStatusCalculator: ResolvedStatusCalculator,
    private val designation: Iterator<FirDeclaration>
) : AbstractFirStatusResolveTransformer(session, scopeSession, resolvedStatusCalculator) {
    private var currentElement: FirDeclaration? = null

    override fun FirDeclaration.needResolve(): Boolean {
        if (currentElement == null && designation.hasNext()) {
            currentElement = designation.next()
        }
        val result = currentElement == this
        if (result) {
            currentElement = null
        }
        return result
    }
}

abstract class AbstractFirStatusResolveTransformer(
    final override val session: FirSession,
    val scopeSession: ScopeSession,
    protected val resolvedStatusCalculator: ResolvedStatusCalculator
) : FirAbstractTreeTransformer<FirResolvedDeclarationStatus?>(phase = FirResolvePhase.STATUS) {
    private val classes = mutableListOf<FirClass<*>>()
    private val statusResolver = FirStatusResolver(session, scopeSession, resolvedStatusCalculator)

    private val containingClass: FirClass<*>? get() = classes.lastOrNull()

    protected abstract fun FirDeclaration.needResolve(): Boolean

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
        regularClass.transformStatus(this, statusResolver.resolveStatus(regularClass, containingClass, isLocal = false))
        @Suppress("UNCHECKED_CAST")
        return storeClass(regularClass) {
            regularClass.typeParameters.forEach { it.transformSingle(this, data) }
            transformDeclaration(regularClass, data)
        } as CompositeTransformResult<FirStatement>
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
        simpleFunction.transformStatus(this, statusResolver.resolveStatus(simpleFunction, containingClass, isLocal = false))
        return transformDeclaration(simpleFunction, data)
    }

    override fun transformProperty(
        property: FirProperty,
        data: FirResolvedDeclarationStatus?
    ): CompositeTransformResult<FirDeclaration> {
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
