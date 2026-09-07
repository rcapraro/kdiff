package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

class DifferGenerationSpec :
    FunSpec({

        test("an annotated data class gains a differ that runs and reports what changed") {
            val result = compile(
                SourceFile.kotlin(
                    "Person.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                data class Person(val id: String, val name: String)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.generatedFileNames shouldContainExactlyInAnyOrder listOf("PersonDiff.kt")

            val diff = result.runDiffer("demo.Person", listOf("1", "Ada"), listOf("1", "Grace"))
            diff.changes.map { it.path.toString() } shouldContainExactly listOf("name")
        }

        test("an unannotated class gains nothing") {
            val result = compile(
                SourceFile.kotlin(
                    "Order.kt",
                    """
                package demo

                data class Order(val id: String)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.generatedFileNames shouldBe emptyList()
        }

        test("two annotated classes each get their own differ") {
            val result = compile(
                SourceFile.kotlin(
                    "Model.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                data class Person(val id: String, val name: String)

                @Diffable
                data class Address(val street: String, val city: String)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.generatedFileNames shouldContainExactlyInAnyOrder listOf("PersonDiff.kt", "AddressDiff.kt")

            result.runDiffer("demo.Person", listOf("1", "Ada"), listOf("1", "Ada")).isEmpty shouldBe true
            result.runDiffer("demo.Address", listOf("Rue X", "Paris"), listOf("Rue Y", "Lyon"))
                .changes.map { it.path.toString() } shouldContainExactly listOf("street", "city")
        }

        test("the differ is generated into the annotated class's own package") {
            val result = compile(
                SourceFile.kotlin(
                    "Money.kt",
                    """
                package com.example.billing

                import io.github.kdiff.annotations.Diffable

                @Diffable
                data class Money(val amount: String, val currency: String)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.runDiffer(
                "com.example.billing.Money",
                listOf("10", "EUR"),
                listOf("12", "EUR"),
            )
            diff.changes.map { it.path.toString() } shouldContainExactly listOf("amount")
        }
    })
