package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class EscapeHatchSpec :
    FunSpec({

        test("a property delegates to a hand-written differ at the property's path") {
            val result = compile(diffWithSource)
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.InvoiceDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("total.amount")
        }

        test("a hand-written differ nests inside two levels of generated delegation") {
            val result = compile(
                SourceFile.kotlin(
                    "Model.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable
                import io.github.kdiff.annotations.DiffWith
                import io.github.kdiff.runtime.Differ
                import io.github.kdiff.runtime.differ

                class Money(val amount: String)

                object MoneyDiffer : Differ<Money> by differ({ field(Money::amount) })

                @Diffable
                data class Line(@DiffWith(MoneyDiffer::class) val price: Money)

                @Diffable
                data class Invoice(val line: Line)

                @Diffable
                data class Account(val invoice: Invoice)

                object Fixture {
                    val before = Account(Invoice(Line(Money("10"))))
                    val after = Account(Invoice(Line(Money("12"))))
                }
                    """.trimIndent(),
                ),
            )
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val diff = result.diffFixture("demo.AccountDiffer")

            diff.changes.map { it.path.toString() } shouldContainExactly
                listOf("invoice.line.price.amount")
        }
    })
