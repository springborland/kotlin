// PSI_ELEMENT: org.jetbrains.kotlin.psi.KtNamedFunction
// OPTIONS: usages, skipImports
// FIR_COMPARISON
package server

interface TraitNoImpl {
    fun <caret>foo()
}

public class TraitWithDelegatedNoImpl(f: TraitNoImpl): TraitNoImpl by f

fun test(twdni: TraitWithDelegatedNoImpl) = twdni.foo()