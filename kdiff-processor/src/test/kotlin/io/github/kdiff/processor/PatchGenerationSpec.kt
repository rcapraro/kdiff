package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun model(declarations: String, before: String, after: String) = SourceFile.kotlin(
    "Model.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable
    import io.github.kdiff.annotations.DiffIgnore
    import io.github.kdiff.annotations.DiffKey
    import io.github.kdiff.annotations.DiffWith
    import io.github.kdiff.runtime.Change
    import io.github.kdiff.runtime.Differ
    import io.github.kdiff.runtime.PatchResult
    import io.github.kdiff.runtime.Patcher
    import io.github.kdiff.runtime.differ
    import io.github.kdiff.runtime.groupByProperty
    import io.github.kdiff.runtime.patchValue
    import io.github.kdiff.runtime.unmatchedFailures

    $declarations

    object Fixture {
        val before = $before
        val after = $after
    }
    """.trimIndent(),
)

/** Each row of design D7's table, round-tripped through generated code. */
class PatchGenerationSpec : FunSpec({

    fun roundTrips(declarations: String, before: String, after: String, differ: String = "demo.PersonDiffer") {
        val result = compile(model(declarations, before, after))
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val patched = result.roundTripFixture(differ)

        patched.failures.shouldBeEmpty()
        patched.value shouldBe result.fixtureAfter()
    }

    test("a value property round-trips") {
        roundTrips(
            "@Diffable data class Person(val id: String, val name: String)",
            """Person("1", "Ada")""",
            """Person("1", "Grace")""",
        )
    }

    test("an enum property round-trips") {
        roundTrips(
            """
            enum class Status { OPEN, CLOSED }
            @Diffable data class Person(val status: Status)
            """.trimIndent(),
            "Person(Status.OPEN)",
            "Person(Status.CLOSED)",
        )
    }

    test("a nullable property round-trips in both directions") {
        roundTrips(
            "@Diffable data class Person(val nickname: String?)",
            "Person(null)",
            """Person("Ada")""",
        )
        roundTrips(
            "@Diffable data class Person(val nickname: String?)",
            """Person("Ada")""",
            "Person(null)",
        )
    }

    test("a nested annotated property round-trips") {
        roundTrips(
            """
            @Diffable data class Address(val street: String)
            @Diffable data class Person(val address: Address)
            """.trimIndent(),
            """Person(Address("Rue X"))""",
            """Person(Address("Rue Y"))""",
        )
    }

    test("a nullable nested property round-trips to null") {
        roundTrips(
            """
            @Diffable data class Address(val street: String)
            @Diffable data class Person(val address: Address?)
            """.trimIndent(),
            """Person(Address("Rue X"))""",
            "Person(null)",
        )
    }

    test("a keyed list round-trips a modification, an addition, a removal and a move") {
        roundTrips(
            """
            @Diffable data class Address(@DiffKey val id: String, val street: String)
            @Diffable data class Person(val addresses: List<Address>)
            """.trimIndent(),
            """Person(listOf(Address("A1", "Rue 1"), Address("A2", "Rue 2")))""",
            """Person(listOf(Address("A2", "Rue X"), Address("A3", "Rue 3")))""",
        )
    }

    test("a positional list round-trips a length change") {
        roundTrips(
            "@Diffable data class Person(val tags: List<String>)",
            """Person(listOf("a", "b"))""",
            """Person(listOf("a", "c", "d"))""",
        )
    }

    test("a set round-trips") {
        roundTrips(
            "@Diffable data class Person(val tags: Set<String>)",
            """Person(setOf("a", "b"))""",
            """Person(setOf("b", "c"))""",
        )
    }

    test("a map round-trips, including a non-string key") {
        roundTrips(
            "@Diffable data class Person(val byId: Map<Int, String>)",
            """Person(mapOf(1 to "one"))""",
            """Person(mapOf(1 to "uno", 2 to "two"))""",
        )
    }

    test("a sealed subclass change round-trips") {
        roundTrips(
            """
            @Diffable sealed interface Payment { val amount: String }
            @Diffable data class Card(override val amount: String, val last4: String) : Payment
            @Diffable data class Transfer(override val amount: String, val iban: String) : Payment
            @Diffable data class Person(val payment: Payment)
            """.trimIndent(),
            """Person(Card("10", "1234"))""",
            """Person(Transfer("12", "FR76"))""",
        )
    }

    test("an ignored property is left at the source value with nothing reported") {
        val result = compile(
            model(
                "@Diffable data class Person(val id: String, @DiffIgnore val lastSeen: String)",
                """Person("1", "monday")""",
                """Person("1", "friday")""",
            ),
        )
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val patched = result.roundTripFixture("demo.PersonDiffer")

        patched.failures.shouldBeEmpty()
        patched.value.toString() shouldContain "monday"
    }

    test("a @DiffWith target that also patches round-trips") {
        roundTrips(
            """
            class Money(val amount: String)
            object MoneyDiffer : Differ<Money> by differ({ field(Money::amount) }), Patcher<Money> {
                override fun apply(before: Money, changes: List<Change>): PatchResult<Money> {
                    val grouped = groupByProperty(changes, setOf("amount"))
                    val amount = patchValue(before.amount, grouped.forProperty("amount"))
                    return PatchResult(Money(amount.value), grouped.unmatchedFailures("Money") + amount.failures)
                }
            }
            @Diffable data class Person(@DiffWith(MoneyDiffer::class) val total: Money) {
                override fun equals(other: Any?): Boolean = other is Person && other.total.amount == total.amount
                override fun hashCode(): Int = total.amount.hashCode()
            }
            """.trimIndent(),
            """Person(Money("10"))""",
            """Person(Money("12"))""",
        )
    }
})

class PatchFailureGenerationSpec : FunSpec({

    test("a change naming no compared property is reported and the rest still applies") {
        val result = compile(
            model(
                "@Diffable data class Person(val name: String)",
                """Person("Ada")""",
                """Person("Grace")""",
            ),
        )
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val stray = io.github.kdiff.runtime.ValueChanged(
            io.github.kdiff.runtime.FieldPath.of("nonsense"),
            "a",
            "b",
        )
        val patched = result.applyWithExtra("demo.PersonDiffer", stray)

        patched.failures.single().reason shouldContain "no compared property"
        patched.value.toString() shouldContain "Grace"
    }

    test("a compare-only @DiffWith property is reported as unpatchable") {
        val result = compile(
            model(
                """
                class Weight(val grams: String)
                object WeightDiffer : Differ<Weight> by differ({ field(Weight::grams) })
                @Diffable data class Person(val name: String, @DiffWith(WeightDiffer::class) val weight: Weight)
                """.trimIndent(),
                """Person("Ada", Weight("500"))""",
                """Person("Grace", Weight("750"))""",
            ),
        )
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val patched = result.roundTripFixture("demo.PersonDiffer")

        patched.failures.single().reason shouldContain "weight"
        patched.failures.single().reason shouldContain "cannot patch"
        patched.value.toString() shouldContain "Grace"
    }

    test("a property declared in the class body is reported as not reconstructible") {
        val result = compile(
            model(
                """
                @Diffable data class Person(val first: String, val last: String) {
                    val full: String = "${'$'}first ${'$'}last"
                }
                """.trimIndent(),
                """Person("Ada", "L")""",
                """Person("Grace", "H")""",
            ),
        )
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val patched = result.roundTripFixture("demo.PersonDiffer")

        patched.failures.single().reason shouldContain "full"
        patched.failures.single().reason shouldContain "only constructor properties"
    }
})
