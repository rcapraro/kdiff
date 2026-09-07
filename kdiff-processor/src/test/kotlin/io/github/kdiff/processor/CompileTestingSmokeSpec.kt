package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CompileTestingSmokeSpec :
    FunSpec({

        test("a source file with no annotations compiles with the processor on the classpath") {
            val result = KotlinCompilation().apply {
                sources = listOf(
                    SourceFile.kotlin(
                        "Order.kt",
                        """
                    package demo

                    data class Order(val id: String)
                        """.trimIndent(),
                    ),
                )
                configureKsp {
                    symbolProcessorProviders += DiffProcessorProvider()
                }
                inheritClassPath = true
                messageOutputStream = System.out
            }.compile()

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        }
    })
