package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.kdiff.runtime.Added
import io.github.kdiff.runtime.DuplicateDiffKeyException
import io.github.kdiff.runtime.Moved
import io.github.kdiff.runtime.Removed
import io.github.kdiff.runtime.ValueChanged
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A nullable `List`, `Set` or `Map` property, which before this change emitted a call the generated
 * file could not compile: the collection helpers took a non-null side.
 */
private val nullableCollections = SourceFile.kotlin(
    "Order.kt",
    """
    package demo

    import io.github.kdiff.annotations.DiffKey
    import io.github.kdiff.annotations.Diffable

    @Diffable
    data class Address(@DiffKey val id: String, val street: String)

    @Diffable
    data class Order(
        val reference: String,
        val tags: List<String>?,
        val addresses: List<Address>?,
        val labels: Set<String>?,
        val amounts: Map<String, String>?,
    )

    private val filled = Order(
        "R-1",
        listOf("a", "b"),
        listOf(Address("A1", "X"), Address("A2", "Y")),
        setOf("x"),
        mapOf("eur" to "10"),
    )

    private val empty = Order("R-1", null, null, null, null)

    object Appears {
        val before = empty
        val after = filled
    }

    object Disappears {
        val before = filled
        val after = empty
    }

    object BothNull {
        val before = empty
        val after = empty.copy(reference = "R-2")
    }

    object BothPresent {
        val before = filled
        val after = Order(
            "R-1",
            listOf("a", "c"),
            listOf(Address("A2", "Y"), Address("A1", "Z")),
            setOf("y"),
            mapOf("eur" to "12"),
        )
    }

    /** A nullable keyed list carrying a repeated key, which has no representable diff either way. */
    object Duplicated {
        val before = empty.copy(addresses = listOf(Address("A1", "X"), Address("A1", "Y")))
        val after = empty
    }
    """.trimIndent(),
)

class NullableCollectionGenerationSpec :
    FunSpec({

        val result = compile(nullableCollections)

        test("a class with four nullable collection properties compiles") {
            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        }

        // Design D3: comparing needs no branch, because the widened helpers take either. Applying does,
        // because its helpers return the rebuilt collection.
        test("the generated diff calls the same helper a non-null property would") {
            val generated = result.generatedSource("OrderDiff.kt")

            generated shouldContain "comparePositionalList(\"tags\", before.tags, after.tags, null)"
            generated shouldContain "compareSet(\"labels\", before.labels, after.labels)"
        }

        test("the generated apply wraps each collection helper in patchNullable") {
            val generated = result.generatedSource("OrderDiff.kt")

            generated shouldContain
                "val tagsPatched = patchNullable(before.tags, grouped.forProperty(\"tags\")) { value, own ->"
            generated shouldContain "patchPositionalList(value, own, null)"
            generated shouldContain "patchSet(value, own)"
        }

        context("a null on one side is a value change at the property") {
            test("every appearing collection reports one value change and no addition") {
                val diff = result.diffFixture("demo.OrderDiffer", "demo.Appears")

                diff.changes.map { it.path.toString() } shouldContainExactly
                    listOf("tags", "addresses", "labels", "amounts")
                diff.changes.filterIsInstance<Added>().shouldBeEmpty()
            }

            test("every disappearing collection reports one value change and no removal") {
                val diff = result.diffFixture("demo.OrderDiffer", "demo.Disappears")

                diff.changes.map { it.path.toString() } shouldContainExactly
                    listOf("tags", "addresses", "labels", "amounts")
                diff.changes.filterIsInstance<Removed>().shouldBeEmpty()
            }

            test("an appearing collection carries both sides") {
                val diff = result.diffFixture("demo.OrderDiffer", "demo.Appears")

                val change = diff.changes.filterIsInstance<ValueChanged>().single { it.path.toString() == "tags" }
                change.before shouldBe null
                change.after shouldBe listOf("a", "b")
            }
        }

        test("collections null on both sides report nothing, leaving other properties compared") {
            val diff = result.diffFixture("demo.OrderDiffer", "demo.BothNull")

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("reference")
        }

        context("collections present on both sides are compared as collections") {
            test("each shape reports at its own kind of path") {
                val diff = result.diffFixture("demo.OrderDiffer", "demo.BothPresent")

                diff.changes.map { it.path.toString() } shouldContainExactly listOf(
                    "tags[1]",
                    "addresses[id=A1]",
                    "addresses[id=A1].street",
                    "addresses[id=A2]",
                    "labels",
                    "labels",
                    "amounts[key=eur]",
                )
            }

            test("a nullable keyed list still reports a reorder as a move") {
                val diff = result.diffFixture("demo.OrderDiffer", "demo.BothPresent")

                diff.changes.filterIsInstance<Moved>().map { it.path.toString() } shouldContainExactly
                    listOf("addresses[id=A1]", "addresses[id=A2]")
            }
        }

        // A nullable declaration must refuse what its non-null twin refuses: the wrapper sits in front
        // of the shape's helper, and a wrapper that short-circuits never reaches its preconditions.
        context("a nullable keyed list holding a repeated key is refused, both ways") {
            test("comparing it against null is refused rather than reported as a transition") {
                shouldThrow<DuplicateDiffKeyException> {
                    result.diffFixture("demo.OrderDiffer", "demo.Duplicated")
                }
            }

            test("applying an empty change list to it is refused") {
                shouldThrow<DuplicateDiffKeyException> {
                    result.patchFixture("demo.OrderDiffer", emptyList(), "demo.Duplicated")
                }
            }

            test("the refusal names the list, the key property and the repeated value") {
                val raised = shouldThrow<DuplicateDiffKeyException> {
                    result.patchFixture("demo.OrderDiffer", emptyList(), "demo.Duplicated")
                }

                raised.property shouldBe "addresses"
                raised.keyProperty shouldBe "id"
                raised.key shouldBe "A1"
            }
        }

        context("a nullable collection round-trips in all four transitions") {
            listOf("Appears", "Disappears", "BothNull", "BothPresent").forEach { fixture ->
                test("$fixture reproduces its target with no failures") {
                    val applied = result.roundTripFixture("demo.OrderDiffer", "demo.$fixture")

                    applied.value shouldBe result.fixtureAfter("demo.$fixture")
                    applied.failures.shouldBeEmpty()
                }
            }
        }
    })
