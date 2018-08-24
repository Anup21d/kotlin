/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.codegen

import com.google.common.io.Files
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.containers.ContainerUtil
import junit.framework.TestCase
import org.jetbrains.kotlin.backend.common.output.OutputFile
import org.jetbrains.kotlin.backend.common.output.OutputFileCollection
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.org.objectweb.asm.*

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.ArrayList
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Test correctness of written local variables in class file for specified method
 */

abstract class AbstractCheckLocalVariablesTableTest : CodegenTestCase() {
    protected lateinit var ktFile: File

    @Throws(Exception::class)
    override fun doMultiFileTest(wholeFile: File, files: List<CodegenTestCase.TestFile>, javaFilesDir: File?) {
        ktFile = wholeFile
        val text = FileUtil.loadFile(ktFile, true)
        compile(files, javaFilesDir)

        val classAndMethod = parseClassAndMethodSignature()
        val split = classAndMethod.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        assert(split.size == 2) { "Exactly one dot is expected: $classAndMethod" }
        val classFileRegex = StringUtil.escapeToRegexp(split[0] + ".class").replace("\\*", ".+")
        val methodName = split[1]

        val outputFiles = (classFileFactory as OutputFileCollection).asList()
        val outputFile = ContainerUtil.find(outputFiles, { file -> file.relativePath.matches(classFileRegex.toRegex()) })

        val pathsString = outputFiles.joinToString { it.relativePath }
        TestCase.assertNotNull("Couldn't find class file for pattern $classFileRegex in: $pathsString", outputFile)

        val cr = ClassReader(outputFile!!.asByteArray())
        val actualLocalVariables = readLocalVariable(cr, methodName)

        doCompare(text, actualLocalVariables)
    }

    protected open fun doCompare(text: String, actualLocalVariables: List<LocalVariable>) {
        KotlinTestUtils.assertEqualsToFile(
            ktFile,
            text.substring(0, text.indexOf("// VARIABLE : ")) + getActualVariablesAsString(
                actualLocalVariables
            )
        )
    }

    protected class LocalVariable internal constructor(
        private val name: String,
        private val type: String,
        private val index: Int
    ) {

        override fun toString(): String {
            return "// VARIABLE : NAME=$name TYPE=$type INDEX=$index"
        }
    }

    @Throws(IOException::class)
    private fun parseClassAndMethodSignature(): String {
        val lines = Files.readLines(ktFile, Charset.forName("utf-8"))
        for (line in lines) {
            val methodMatcher = methodPattern.matcher(line)
            if (methodMatcher.matches()) {
                return methodMatcher.group(1)
            }
        }

        throw AssertionError("method instructions not found")
    }

    companion object {

        private fun getActualVariablesAsString(list: List<LocalVariable>): String {
            val builder = StringBuilder()
            for (variable in list) {
                builder.append(variable.toString()).append("\n")
            }
            return builder.toString()
        }

        private val methodPattern = Pattern.compile("^// METHOD : *(.*)")

        @Throws(Exception::class)
        private fun readLocalVariable(cr: ClassReader, methodName: String): List<LocalVariable> {

            class Visitor : ClassVisitor(Opcodes.ASM5) {
                var readVariables: MutableList<LocalVariable> = ArrayList()

                override fun visitMethod(
                    access: Int, name: String, desc: String, signature: String?, exceptions: Array<String>?
                ): MethodVisitor {
                    return if (methodName == name + desc) {
                        object : MethodVisitor(Opcodes.ASM5) {
                            override fun visitLocalVariable(
                                name: String, desc: String, signature: String, start: Label, end: Label, index: Int
                            ) {
                                readVariables.add(LocalVariable(name, desc, index))
                            }
                        }
                    } else {
                        super.visitMethod(access, name, desc, signature, exceptions)
                    }
                }
            }

            val visitor = Visitor()

            cr.accept(visitor, ClassReader.SKIP_FRAMES)

            TestCase.assertFalse("method not found: $methodName", visitor.readVariables.size == 0)

            return visitor.readVariables
        }
    }
}

