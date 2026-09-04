package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The trailing `else` in a generated sealed `apply` is emitted only where a `when` needs one.
 *
 * A sealed hierarchy with subclasses makes the `when` exhaustive, so an `else` there is dead code that
 * warns in every consumer. A sealed type with no subclasses has nothing to enumerate, and a branchless
 * `when` would not compile — so that is the one shape that keeps it.
 */
class SealedElseSpec : FunSpec({

    test("an enumerated sealed hierarchy generates no else branch, and still round-trips") {
        val result = compile(
            SourceFile.kotlin(
                "Payment.kt",
                """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                sealed interface Payment {
                    val amount: String
                }

                @Diffable
                data class Card(override val amount: String, val last4: String) : Payment

                @Diffable
                data class Transfer(override val amount: String, val iban: String) : Payment

                object Fixture {
                    val before: Payment = Card("10", "1234")
                    val after: Payment = Transfer("12", "FR76")
                }
                """.trimIndent(),
            ),
        )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val source = result.generatedSource("PaymentDiff.kt")
        source shouldContain "is Card ->"
        source shouldContain "is Transfer ->"
        source shouldNotContain "else -> PatchResult(before)"

        // Behaviour is unchanged: the branch that went away was unreachable.
        val patched = result.roundTripFixture("demo.PaymentDiffer")
        patched.failures.shouldBeEmpty()
        patched.value shouldBe result.fixtureAfter()
    }

    test("a sealed type with no subclasses keeps the else, because a branchless when would not compile") {
        val result = compile(
            SourceFile.kotlin(
                "Empty.kt",
                """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                sealed interface Nothing
                """.trimIndent(),
            ),
        )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedSource("NothingDiff.kt") shouldContain "else -> PatchResult(before)"
    }

    test("a sealed class with subclasses is treated the same as a sealed interface") {
        val result = compile(
            SourceFile.kotlin(
                "Shape.kt",
                """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                sealed class Shape

                @Diffable
                data class Circle(val radius: String) : Shape()

                @Diffable
                data class Square(val side: String) : Shape()
                """.trimIndent(),
            ),
        )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedSource("ShapeDiff.kt") shouldNotContain "else -> PatchResult(before)"
    }
})
