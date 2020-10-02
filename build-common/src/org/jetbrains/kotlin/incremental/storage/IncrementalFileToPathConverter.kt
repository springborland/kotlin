/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.incremental.storage

import java.io.File

open class IncrementalFileToPathConverter(val rootProjectDir: File?) : FileToPathConverter {
    //project root dir
    private val projectDirPath = rootProjectDir?.absolutePath/*.let { normalize(it)}*/

    override fun toPath(file: File): String {
        val path = file.absolutePath
        return when {
            projectDirPath == null || !path.startsWith(projectDirPath) -> path
            else -> PROJECT_DIR_PLACEHOLDER + path.substring(projectDirPath.length)
        }
    }

    override fun toFile(path: String): File =
        when {
            path.startsWith(PROJECT_DIR_PLACEHOLDER) -> rootProjectDir!!.resolve(path.substring(PROJECT_DIR_PLACEHOLDER.length))
            else -> File(path)
        }

    private companion object {
        private const val PROJECT_DIR_PLACEHOLDER = "${'$'}PROJECT_DIR$"

    }

}