/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.asJava.classes

import org.jetbrains.kotlin.asJava.KotlinAsJavaSupport
import org.jetbrains.kotlin.doTestWithFIRFlagsByPath
import org.jetbrains.kotlin.idea.perf.UltraLightChecker
import org.jetbrains.kotlin.idea.perf.UltraLightChecker.checkByJavaFile
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

abstract class AbstractFirClassLoadingTest : AbstractUltraLightClassLoadingTest() {

    override fun isFirPlugin(): Boolean = true

    override fun doTest(testDataPath: String) = doTestWithFIRFlagsByPath(testDataPath) {
        doTestImpl(testDataPath)
    }

    private fun doTestImpl(testDataPath: String) {

        val testDataFile = File(testDataPath)
        val sourceText = testDataFile.readText()
        val file = myFixture.addFileToProject(testDataPath, sourceText) as KtFile

        val classFabric = KotlinAsJavaSupport.getInstance(project)
        val lightClasses = UltraLightChecker.allClasses(file).mapNotNull { classFabric.getLightClass(it) }

        checkByJavaFile(testDataPath, lightClasses)
    }
}