package io.github.kdiff.processor

import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

/**
 * A value classification taken off a declaration in the author's module records that declaration's
 * file, so editing it regenerates whatever read it.
 *
 * Each case declares the value type in a file of its own, because the claim is precisely that the
 * *other* file joins the originating set — a type declared beside the annotated class would be
 * recorded through the annotated class's own file and prove nothing.
 */
private fun order(propertyType: String): SourceFile = SourceFile.kotlin(
    "Order.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable

    @Diffable
    data class Order(val subject: $propertyType)
    """.trimIndent(),
)

private val declaredAsValue = SourceFile.kotlin(
    "Sku.kt",
    """
    package demo

    import io.github.kdiff.annotations.DiffAsValue

    @DiffAsValue
    data class Sku(val code: String, val warehouse: String)
    """.trimIndent(),
)

private val inlineValueClass = SourceFile.kotlin(
    "Reference.kt",
    """
    package demo

    @JvmInline
    value class Reference(val value: String)
    """.trimIndent(),
)

private val enumClass = SourceFile.kotlin(
    "Status.kt",
    """
    package demo

    enum class Status { OPEN, CLOSED }
    """.trimIndent(),
)

class ValueSourceSpec :
    FunSpec({

        test("a type declared to compare as one value records its declaring file") {
            val recorded = recordResolutions(order("Sku"), declaredAsValue)

            recorded.of("Order", "subject").valueSourceFileNames shouldContainExactly listOf("Sku.kt")
        }

        test("an inline value class records its declaring file") {
            val recorded = recordResolutions(order("Reference"), inlineValueClass)

            recorded.of("Order", "subject").valueSourceFileNames shouldContainExactly listOf("Reference.kt")
        }

        test("an enum class records its declaring file") {
            val recorded = recordResolutions(order("Status"), enumClass)

            recorded.of("Order", "subject").valueSourceFileNames shouldContainExactly listOf("Status.kt")
        }

        test("a standard-library value type records no file, having no declaration the author can edit") {
            val recorded = recordResolutions(order("java.math.BigDecimal"))

            recorded.of("Order", "subject").valueSourceFileNames.shouldBeEmpty()
        }
    })
