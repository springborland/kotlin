// PSI_ELEMENT: org.jetbrains.kotlin.psi.KtNamedFunction
// OPTIONS: usages, skipImports
// FIR_COMPARISON
package server

interface TraitWithImpl {
    fun <caret>foo() = 1
}

public class TraitWithDelegatedWithImpl(f: TraitWithImpl): TraitWithImpl by f

fun test(twdwi: TraitWithDelegatedWithImpl) = twdwi.foo()