/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.kotlin.analyzer.KotlinModificationTrackerService
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.classes.shouldNotBeVisibleAsLightClass
import org.jetbrains.kotlin.idea.asJava.FirLightClassForSourceDeclaration
import org.jetbrains.kotlin.idea.util.ifFalse
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtScript

class IDEKotlinAsJavaFirSupport(project: Project) : IDEKotlinAsJavaSupport(project) {


    override fun createLightClassForFacade(manager: PsiManager, facadeClassFqName: FqName, searchScope: GlobalSearchScope): KtLightClass? = null

    override fun createLightClassForScript(script: KtScript): KtLightClass? = null

    override fun createLightClassForSourceDeclaration(classOrObject: KtClassOrObject): KtLightClass? =
        classOrObject.shouldNotBeVisibleAsLightClass().ifFalse {
            CachedValuesManager.getCachedValue(classOrObject) {
                CachedValueProvider.Result
                    .create(
                        FirLightClassForSourceDeclaration(classOrObject),
                        KotlinModificationTrackerService.getInstance(classOrObject.project).outOfBlockModificationTracker
                    )
            }
        }
}