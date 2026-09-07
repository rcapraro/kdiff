package io.github.kdiff.processor

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.kdiff.runtime.Added
import io.github.kdiff.runtime.Change
import io.github.kdiff.runtime.FieldPath
import io.github.kdiff.runtime.PatchFailure
import io.github.kdiff.runtime.PatchResult
import io.github.kdiff.runtime.Patcher
import io.github.kdiff.runtime.ValueChanged
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The shape of a generated `apply`.
 *
 * Two costs a generated `apply` used to pay on every call — rebuilding the set of compared property
 * names, and folding the per-property failures with `+` — are paid once per type and once per call
 * respectively. Both are properties of the emitted text, so both are asserted on it; what the emitted
 * code *does* is asserted by running it.
 */
class ApplyShapeSpec :
    FunSpec({

        fun model(properties: String, extra: String = "") = SourceFile.kotlin(
            "Order.kt",
            """
        package demo

        import io.github.kdiff.annotations.Diffable

        @Diffable
        data class Order($properties)

        $extra
            """.trimIndent(),
        )

        context("the compared property names are a constant of the type") {
            test("they are declared once, privately, and not rebuilt inside apply") {
                val result = compile(model("val reference: String, val status: String"))
                val source = result.generatedSource("OrderDiff.kt")

                result.exitCode shouldBe KotlinCompilation.ExitCode.OK
                source shouldContain
                    "private val comparedProperties: Set<String> = setOf(\"reference\", \"status\")"
                source shouldContain "val grouped = groupByProperty(changes, comparedProperties)"
                source.substringAfter("override fun apply") shouldNotContain "setOf("
            }

            test("the property is not part of what a consumer can name") {
                val source = compile(model("val reference: String")).generatedSource("OrderDiff.kt")

                source shouldContain "private val comparedProperties"
            }

            test("a sealed type declares none, because it never groups") {
                val result = compile(
                    SourceFile.kotlin(
                        "Payment.kt",
                        """
                    package demo

                    import io.github.kdiff.annotations.Diffable

                    @Diffable
                    sealed interface Payment { val amount: String }

                    @Diffable
                    data class Card(override val amount: String, val last4: String) : Payment
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.OK
                result.generatedSource("PaymentDiff.kt") shouldNotContain "comparedProperties"
                result.generatedSource("CardDiff.kt") shouldContain "comparedProperties"
            }
        }

        context("the failures are gathered once") {
            test("apply builds one list rather than folding with plus") {
                val source = compile(model("val reference: String, val status: String"))
                    .generatedSource("OrderDiff.kt")

                source shouldContain "buildList<PatchFailure> {"
                source shouldContain "addAll(grouped.unmatchedFailures(\"Order\"))"
                source shouldContain "addAll(referencePatched.failures)"
                source shouldContain "addAll(statusPatched.failures)"
                source shouldNotContain ".failures + "
            }

            test("failures are reported unmatched first, then one property at a time") {
                val result = compile(model("val reference: String, val status: String"))

                val unmatched = ValueChanged(FieldPath.of("nonesuch"), "a", "b")
                val onReference = Added(FieldPath.of("reference"), "x")
                val onStatus = Added(FieldPath.of("status"), "y")

                val patched = result.patchFixtureless(
                    "demo.OrderDiffer",
                    "demo.Order",
                    listOf("R1", "OPEN"),
                    listOf(onStatus, unmatched, onReference),
                )

                // `groupByProperty` strips the property segment, so a grouped failure's own path is empty;
                // the value each change carries is what identifies which property it was addressed to.
                patched.failures.map { it.reason to (it.change as? Added)?.value } shouldContainExactly listOf(
                    PatchFailure.Reason.UnknownProperty("Order") to null,
                    PatchFailure.Reason.NotApplicableToValue to "x",
                    PatchFailure.Reason.NotApplicableToValue to "y",
                )
            }

            test("a clean apply reports no failures and rebuilds the target") {
                val result = compile(model("val reference: String, val status: String"))

                val patched = result.patchFixtureless(
                    "demo.OrderDiffer",
                    "demo.Order",
                    listOf("R1", "OPEN"),
                    listOf(ValueChanged(FieldPath.of("reference"), "R1", "R2")),
                )

                patched.failures shouldBe emptyList()
                patched.value.toString() shouldContain "reference=R2"
            }
        }

        context("incremental processing is unaffected") {
            test("two annotated types still generate one file each, and nothing more") {
                val result = compile(
                    model("val reference: String"),
                    SourceFile.kotlin(
                        "Address.kt",
                        """
                    package demo

                    import io.github.kdiff.annotations.Diffable

                    @Diffable
                    data class Address(val city: String)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.OK
                result.generatedFileNames.sorted() shouldContainExactly listOf("AddressDiff.kt", "OrderDiff.kt")
            }
        }

        context("a keyed list still names itself when a key repeats") {
            test("the generated call passes the list and key names through") {
                val result = compile(
                    SourceFile.kotlin(
                        "Basket.kt",
                        """
                    package demo

                    import io.github.kdiff.annotations.DiffKey
                    import io.github.kdiff.annotations.Diffable

                    @Diffable
                    data class Item(@DiffKey val id: String, val label: String)

                    @Diffable
                    data class Basket(val items: List<Item>)
                        """.trimIndent(),
                    ),
                )

                result.exitCode shouldBe KotlinCompilation.ExitCode.OK
                result.generatedSource("BasketDiff.kt") shouldContain
                    "patchKeyedList(before.items, grouped.forProperty(\"items\"), ItemDiffer, \"items\", \"id\")"
            }
        }
    })

/**
 * Applies [changes] to an instance built from string constructor arguments.
 *
 * The fixture helpers all diff first; these cases need a patcher reached with changes the comparison
 * would never produce, which is what a failure is.
 */
@Suppress("UNCHECKED_CAST")
private fun JvmCompilationResult.patchFixtureless(
    differClassName: String,
    targetClassName: String,
    args: List<String>,
    changes: List<Change>,
): PatchResult<Any?> {
    val target = classLoader.loadClass(targetClassName)
    val constructor = target.getConstructor(*Array(args.size) { String::class.java })
    val before = constructor.newInstance(*args.toTypedArray())

    val patcher = classLoader.loadClass(differClassName).getField("INSTANCE").get(null)
    return (patcher as Patcher<Any?>).apply(before, changes)
}
