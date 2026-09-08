package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.kdiff.runtime.FieldPath
import io.github.kdiff.runtime.PatchFailure
import io.github.kdiff.runtime.TypeChanged
import io.github.kdiff.runtime.ValueChanged
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * A sealed hierarchy holding a payload-free case, which is how Kotlin models one — and which before
 * this change had no way through: `@Diffable` rejected the object, and the sealed parent required every
 * subclass to carry it.
 */
private val withSingleton = SourceFile.kotlin(
    "Payment.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable
    import io.github.kdiff.runtime.Differ
    import io.github.kdiff.runtime.differ

    @Diffable
    sealed interface Payment {
        val amount: String
    }

    @Diffable
    data class Card(override val amount: String, val last4: String) : Payment

    @Diffable
    data class Transfer(override val amount: String, val iban: String) : Payment

    data object Unpaid : Payment {
        override val amount: String get() = "0"
    }

    @Diffable
    sealed interface State

    data object Idle : State

    data object Busy : State

    /** Declares the two data classes and not the singleton, so the undeclared-subtype rule applies. */
    object HandWritten : Differ<Payment> by differ({
        field(Payment::amount)
        subtype(Card::class, differ { field(Card::amount); field(Card::last4) })
        subtype(Transfer::class, differ { field(Transfer::amount); field(Transfer::iban) })
    })

    object Same {
        val before: Payment = Unpaid
        val after: Payment = Unpaid
    }

    object ToUnpaid {
        val before: Payment = Card("10", "1234")
        val after: Payment = Unpaid
    }

    object FromUnpaid {
        val before: Payment = Unpaid
        val after: Payment = Transfer("12", "FR76")
    }

    object BetweenObjects {
        val before: State = Idle
        val after: State = Busy
    }
    """.trimIndent(),
)

class SealedSingletonSpec :
    FunSpec({

        val result = compile(withSingleton)

        test("an object subclass is accepted without an annotation of its own") {
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            // No differ for the three objects: the sealed parents dispatch on them directly.
            result.generatedFileNames shouldContainExactlyInAnyOrder
                listOf("PaymentDiff.kt", "CardDiff.kt", "TransferDiff.kt", "StateDiff.kt")
        }

        test("the singleton's branch reports nothing rather than naming a differ") {
            val generated = result.generatedSource("PaymentDiff.kt")

            generated shouldContain "before is Unpaid && after is Unpaid -> Unit"
            generated shouldNotContain "UnpaidDiffer"
        }

        test("the singleton's apply branch returns it through the runtime helper") {
            result.generatedSource("PaymentDiff.kt") shouldContain
                "is Unpaid -> patchSingleton<Payment>(before, changes, \"Unpaid\")"
        }

        context("comparing") {
            test("the same object on both sides reports nothing") {
                result.diffFixture("demo.PaymentDiffer", "demo.Same").changes.shouldBeEmpty()
            }

            test("a swap to an object reports the type change and the parent's own properties") {
                val diff = result.diffFixture("demo.PaymentDiffer", "demo.ToUnpaid")

                diff.changes.filterIsInstance<TypeChanged>().single().let {
                    it.beforeType shouldBe "Card"
                    it.afterType shouldBe "Unpaid"
                }
                diff.valueChange("amount").let {
                    it.before shouldBe "10"
                    it.after shouldBe "0"
                }
            }

            test("a swap from an object reports the type change and the parent's own properties") {
                val diff = result.diffFixture("demo.PaymentDiffer", "demo.FromUnpaid")

                diff.changes.filterIsInstance<TypeChanged>().single().afterType shouldBe "Transfer"
                diff.valueChange("amount").after shouldBe "12"
            }

            test("a type change to an object carries both instances, not only their names") {
                val swap = result.diffFixture("demo.PaymentDiffer", "demo.ToUnpaid")
                    .changes.filterIsInstance<TypeChanged>().single()

                swap.after!!::class.java.name shouldBe "demo.Unpaid"
            }

            test("a swap between two objects reports the type change alone when the parent declares nothing") {
                val diff = result.diffFixture("demo.StateDiffer", "demo.BetweenObjects")

                diff.changes.filterIsInstance<TypeChanged>().single().let {
                    it.beforeType shouldBe "Idle"
                    it.afterType shouldBe "Busy"
                }
                diff.changes.size shouldBe 1
            }
        }

        context("applying") {
            test("a swap to an object round-trips") {
                val applied = result.roundTripFixture("demo.PaymentDiffer", "demo.ToUnpaid")

                applied.value shouldBe result.fixtureAfter("demo.ToUnpaid")
                applied.failures.shouldBeEmpty()
            }

            test("a swap from an object round-trips") {
                val applied = result.roundTripFixture("demo.PaymentDiffer", "demo.FromUnpaid")

                applied.value shouldBe result.fixtureAfter("demo.FromUnpaid")
                applied.failures.shouldBeEmpty()
            }

            test("the same object on both sides applies an empty diff and returns the singleton") {
                val applied = result.roundTripFixture("demo.PaymentDiffer", "demo.Same")

                applied.value shouldBe result.fixtureAfter("demo.Same")
                applied.failures.shouldBeEmpty()
            }

            test("a foreign change beneath a singleton is reported as an unknown property") {
                val foreign = ValueChanged(FieldPath.of("amount"), "0", "5")

                val applied = result.patchFixture("demo.PaymentDiffer", listOf(foreign), "demo.Same")

                applied.value shouldBe result.fixtureAfter("demo.Same")
                applied.failures.single().reason shouldBe PatchFailure.Reason.UnknownProperty("Unpaid")
            }
        }

        context("a hand-written sealed differ treats the singleton the same way") {
            listOf("Same", "ToUnpaid", "FromUnpaid").forEach { fixture ->
                test("$fixture reports the same changes through both routes") {
                    val generated = result.diffFixture("demo.PaymentDiffer", "demo.$fixture")
                    val handWritten = result.diffFixture("demo.HandWritten", "demo.$fixture")

                    handWritten shouldBe generated
                }
            }
        }

        test("an unannotated data class subclass is still rejected, and the object is not named") {
            val rejected = compile(
                SourceFile.kotlin(
                    "Payment.kt",
                    """
                package demo

                import io.github.kdiff.annotations.Diffable

                @Diffable
                sealed interface Payment

                data object Unpaid : Payment

                data class Transfer(val iban: String) : Payment
                    """.trimIndent(),
                ),
            )

            rejected.exitCode shouldBe KotlinCompilation.ExitCode.COMPILATION_ERROR
            rejected.messages shouldContain
                "@Diffable on a sealed type requires every subclass to be @Diffable; Payment has Transfer"
            rejected.messages shouldNotContain "Unpaid"
        }
    })
