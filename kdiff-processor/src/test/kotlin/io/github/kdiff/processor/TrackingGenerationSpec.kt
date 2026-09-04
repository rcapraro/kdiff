package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.kdiff.runtime.TrackedField
import io.github.kdiff.runtime.UNLIMITED_DEPTH
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun order(annotations: String, properties: String): SourceFile = SourceFile.kotlin(
    "Order.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable
    import io.github.kdiff.annotations.DiffIgnore
    import io.github.kdiff.annotations.TrackDepth
    import io.github.kdiff.annotations.TrackIgnore
    import io.github.kdiff.annotations.Trackable

    @Diffable
    data class Address(val street: String, val city: String)

    @Diffable
    $annotations
    data class Order($properties)
    """.trimIndent(),
)

class TrackingGenerationSpec : FunSpec({

    context("a class declares a scope") {
        test("a class-level depth applies to every compared property") {
            val result = compile(
                order("@Trackable(depth = 1)", "val reference: String, val status: String"),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.trackedFields("demo.OrderDiffer") shouldContainExactly listOf(
                TrackedField("reference", 1),
                TrackedField("status", 1),
            )
        }

        test("a bare annotation declares an unlimited depth") {
            val result = compile(order("@Trackable", "val reference: String"))

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.trackedFields("demo.OrderDiffer") shouldContainExactly listOf(
                TrackedField("reference", UNLIMITED_DEPTH),
            )
        }

        test("a property's own depth overrides the class's") {
            val result = compile(
                order(
                    "@Trackable(depth = 1)",
                    "val reference: String, @TrackDepth(2) val billing: Address",
                ),
            )

            result.trackedFields("demo.OrderDiffer") shouldContainExactly listOf(
                TrackedField("reference", 1),
                TrackedField("billing", 2),
            )
        }

        test("a property depth applies without a class depth") {
            val result = compile(
                order("@Trackable", "@TrackDepth(1) val reference: String, val status: String"),
            )

            result.trackedFields("demo.OrderDiffer") shouldContainExactly listOf(
                TrackedField("reference", 1),
                TrackedField("status", UNLIMITED_DEPTH),
            )
        }

        test("an excluded property is left out of the scope but still compared") {
            val result = compile(
                order("@Trackable", "val reference: String, @TrackIgnore val status: String"),
            )

            result.trackedFields("demo.OrderDiffer") shouldContainExactly listOf(
                TrackedField("reference", UNLIMITED_DEPTH),
            )
            result.runDiffer("demo.Order", listOf("R1", "OPEN"), listOf("R1", "CLOSED"))
                .valueChange("status").after shouldBe "CLOSED"
        }

        test("a property ignored for comparison never reaches the scope") {
            val result = compile(
                order("@Trackable", "val reference: String, @DiffIgnore val lastTouched: String"),
            )

            result.trackedFields("demo.OrderDiffer") shouldContainExactly listOf(
                TrackedField("reference", UNLIMITED_DEPTH),
            )
        }
    }

    context("a class declaring no scope is untouched") {
        test("no tracking capability and no scope property") {
            val result = compile(order("", "val reference: String"))

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.declaresTracking("demo.OrderDiffer") shouldBe false
            result.generatedSource("OrderDiff.kt") shouldNotContainAnyOf
                listOf("Tracked<Order>", "trackScope", "trackScopeOf")
        }

        test("declaring a scope leaves comparison unchanged") {
            val properties = "val reference: String, val status: String"
            val withoutTracking = compile(order("", properties))
            val withTracking = compile(order("@Trackable(depth = 1)", properties))

            val before = listOf("R1", "OPEN")
            val after = listOf("R2", "CLOSED")

            withTracking.runDiffer("demo.Order", before, after).changes shouldBe
                withoutTracking.runDiffer("demo.Order", before, after).changes
        }
    }

    context("one declaration carries all three capabilities") {
        test("comparison, application and the scope are reached through the same object") {
            val result = compile(order("@Trackable(depth = 1)", "val reference: String"))

            val differ = result.classLoader.loadClass("demo.OrderDiffer")

            io.github.kdiff.runtime.Differ::class.java.isAssignableFrom(differ) shouldBe true
            io.github.kdiff.runtime.Patcher::class.java.isAssignableFrom(differ) shouldBe true
            io.github.kdiff.runtime.Tracked::class.java.isAssignableFrom(differ) shouldBe true
        }

        test("the generated declaration keeps its name, package and signatures") {
            val result = compile(order("@Trackable", "val reference: String"))
            val source = result.generatedSource("OrderDiff.kt")

            source shouldContain "public object OrderDiffer : Differ<Order>, Patcher<Order>, Tracked<Order>"
            source shouldContain "override fun diff(before: Order, after: Order): Diff"
            source shouldContain "override fun apply(before: Order, changes: List<Change>): PatchResult<Order>"
        }
    }

    context("a sealed type declares a scope over its own properties") {
        test("only the sealed parent's declared properties are in the scope") {
            val result = compile(
                SourceFile.kotlin(
                    "Payment.kt",
                    """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import io.github.kdiff.annotations.Trackable

                    @Diffable
                    @Trackable(depth = 1)
                    sealed interface Payment {
                        val amount: String
                    }

                    @Diffable
                    data class Card(override val amount: String, val last4: String) : Payment

                    @Diffable
                    data class Transfer(override val amount: String, val iban: String) : Payment
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.trackedFields("demo.PaymentDiffer") shouldContainExactly listOf(
                TrackedField("amount", 1),
            )
        }
    }

    context("the emitted scope depends on nothing but the annotated file") {
        test("a nested type's own scope does not reach the outer type's") {
            val result = compile(
                SourceFile.kotlin(
                    "Nested.kt",
                    """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import io.github.kdiff.annotations.Trackable

                    @Diffable
                    @Trackable(depth = 1)
                    data class Address(val street: String, val city: String)

                    @Diffable
                    @Trackable(depth = 2)
                    data class Order(val reference: String, val billing: Address)
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK
            result.trackedFields("demo.OrderDiffer") shouldContainExactly listOf(
                TrackedField("reference", 2),
                TrackedField("billing", 2),
            )
            result.trackedFields("demo.AddressDiffer") shouldContainExactly listOf(
                TrackedField("street", 1),
                TrackedField("city", 1),
            )
        }

        test("each annotated type still gets its own generated file") {
            val result = compile(order("@Trackable", "val reference: String"))

            result.generatedFileNames shouldContainExactlyInAnyOrder
                listOf("AddressDiff.kt", "OrderDiff.kt")
        }
    }

    context("a generated scope drives a tracker") {
        test("a tracker with no field of its own honours the declared depth") {
            val result = compile(
                SourceFile.kotlin(
                    "Order.kt",
                    """
                    package demo

                    import io.github.kdiff.annotations.Diffable
                    import io.github.kdiff.annotations.Trackable

                    @Diffable
                    data class Address(val street: String, val city: String)

                    @Diffable
                    @Trackable(depth = 1)
                    data class Order(val reference: String, val billing: Address)

                    object Fixture {
                        val before = Order("R1", Address("1 Rue X", "Lyon"))
                        val after = Order("R2", Address("1 Rue X", "Nice"))
                    }
                    """.trimIndent(),
                ),
            )

            result.exitCode shouldBe KotlinCompilation.ExitCode.OK

            val reported = result.trackFixture("demo.OrderDiffer")

            reported.changes.map { it.path.toString() } shouldContainExactly listOf("reference")
        }
    }
})

private infix fun String.shouldNotContainAnyOf(unwanted: List<String>) {
    unwanted.forEach { check(!contains(it)) { "expected the generated source not to contain \"$it\"" } }
}
