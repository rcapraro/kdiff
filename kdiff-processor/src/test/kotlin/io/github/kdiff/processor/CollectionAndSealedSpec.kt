package io.github.kdiff.processor

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import io.github.kdiff.runtime.Added
import io.github.kdiff.runtime.Moved
import io.github.kdiff.runtime.Removed
import io.github.kdiff.runtime.TypeChanged
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe

private fun model(declarations: String, before: String, after: String) = SourceFile.kotlin(
    "Model.kt",
    """
    package demo

    import io.github.kdiff.annotations.Diffable
    import io.github.kdiff.annotations.DiffKey

    $declarations

    object Fixture {
        val before = $before
        val after = $after
    }
    """.trimIndent(),
)

private const val KEYED = """
@Diffable data class Address(@DiffKey val id: String, val street: String)
@Diffable data class Person(val addresses: List<Address>)
"""

class KeyedListSpec : FunSpec({

    fun keyed(before: String, after: String) =
        compile(model(KEYED, "Person(listOf($before))", "Person(listOf($after))"))

    val a1 = """Address("A1", "Rue 1")"""
    val a2 = """Address("A2", "Rue 2")"""

    test("an unchanged list produces no changes") {
        val result = keyed("$a1, $a2", "$a1, $a2")
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        result.diffFixture("demo.PersonDiffer").isEmpty shouldBe true
    }

    test("a modified element is reported at its key") {
        val diff = keyed("$a1, $a2", """$a1, Address("A2", "Rue X")""").diffFixture("demo.PersonDiffer")

        diff.changes.map { it.path.toString() } shouldContainExactly
            listOf("addresses[id=A2].street")
    }

    test("a new element is reported as added") {
        val diff = keyed(a1, """$a1, Address("A3", "Rue 3")""").diffFixture("demo.PersonDiffer")

        diff.changes.filterIsInstance<Added>().map { it.path.toString() } shouldContainExactly
            listOf("addresses[id=A3]")
    }

    test("a missing element is reported as removed") {
        val diff = keyed("$a1, $a2", a2).diffFixture("demo.PersonDiffer")

        diff.changes.filterIsInstance<Removed>().map { it.path.toString() } shouldContainExactly
            listOf("addresses[id=A1]")
    }

    test("a reordered element is reported as moved, not as removed and added") {
        val diff = keyed("$a1, $a2", "$a2, $a1").diffFixture("demo.PersonDiffer")

        diff.changes.filterIsInstance<Moved>().shouldNotBeEmpty()
        diff.changes.filterIsInstance<Removed>().shouldBeEmpty()
        diff.changes.filterIsInstance<Added>().shouldBeEmpty()
    }
})

class PositionalListSpec : FunSpec({

    fun tags(before: String, after: String) = compile(
        model(
            "@Diffable data class Person(val tags: List<String>)",
            "Person(listOf($before))",
            "Person(listOf($after))",
        ),
    )

    test("a changed element is reported at its index") {
        val result = tags("\"a\", \"b\"", "\"a\", \"c\"")
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        result.diffFixture("demo.PersonDiffer").changes.map { it.path.toString() } shouldContainExactly
            listOf("tags[1]")
    }

    test("a longer new list reports additions at the trailing indices") {
        val diff = tags("\"a\"", "\"a\", \"b\"").diffFixture("demo.PersonDiffer")

        diff.changes.filterIsInstance<Added>().map { it.path.toString() } shouldContainExactly
            listOf("tags[1]")
    }

    test("a shorter new list reports removals at the trailing indices") {
        val diff = tags("\"a\", \"b\"", "\"a\"").diffFixture("demo.PersonDiffer")

        diff.changes.filterIsInstance<Removed>().map { it.path.toString() } shouldContainExactly
            listOf("tags[1]")
    }

    test("a move is never reported for an unkeyed list") {
        val diff = tags("\"a\", \"b\"", "\"b\", \"a\"").diffFixture("demo.PersonDiffer")

        diff.changes.filterIsInstance<Moved>().shouldBeEmpty()
    }
})

class SetAndMapSpec : FunSpec({

    test("a set reports added and removed members and never a move") {
        val result = compile(
            model(
                "@Diffable data class Person(val tags: Set<String>)",
                """Person(setOf("a", "b"))""",
                """Person(setOf("b", "c"))""",
            ),
        )
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val diff = result.diffFixture("demo.PersonDiffer")

        diff.changes.filterIsInstance<Removed>().map { it.value } shouldContainExactly listOf("a")
        diff.changes.filterIsInstance<Added>().map { it.value } shouldContainExactly listOf("c")
        diff.changes.filterIsInstance<Moved>().shouldBeEmpty()
    }

    test("a map reports a changed value at its entry key") {
        val result = compile(
            model(
                "@Diffable data class Rates(val byCode: Map<String, String>)",
                """Rates(mapOf("eur" to "1.0"))""",
                """Rates(mapOf("eur" to "1.1"))""",
            ),
        )
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        result.diffFixture("demo.RatesDiffer").changes.map { it.path.toString() } shouldContainExactly
            listOf("byCode[key=eur]")
    }

    test("a map reports added and removed entries") {
        val diff = compile(
            model(
                "@Diffable data class Rates(val byCode: Map<String, String>)",
                """Rates(mapOf("gbp" to "1.0"))""",
                """Rates(mapOf("usd" to "1.0"))""",
            ),
        ).diffFixture("demo.RatesDiffer")

        diff.changes.filterIsInstance<Removed>().map { it.path.toString() } shouldContainExactly
            listOf("byCode[key=gbp]")
        diff.changes.filterIsInstance<Added>().map { it.path.toString() } shouldContainExactly
            listOf("byCode[key=usd]")
    }

    test("a map descends into an annotated value type") {
        val diff = compile(
            model(
                """
                @Diffable data class Address(val street: String)
                @Diffable data class Book(val byId: Map<String, Address>)
                """.trimIndent(),
                """Book(mapOf("a" to Address("Rue X")))""",
                """Book(mapOf("a" to Address("Rue Y")))""",
            ),
        ).diffFixture("demo.BookDiffer")

        diff.changes.map { it.path.toString() } shouldContainExactly listOf("byId[key=a].street")
    }
})

private const val SEALED = """
@Diffable sealed interface Payment { val amount: String }
@Diffable data class Card(override val amount: String, val last4: String) : Payment
@Diffable data class Transfer(override val amount: String, val iban: String) : Payment
"""

class SealedSpec : FunSpec({

    fun payments(before: String, after: String) = compile(model(SEALED, before, after))

    test("the same subclass on both sides delegates without a type change") {
        val result = payments("""Card("10", "1234")""", """Card("10", "5678")""")
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val diff = result.diffFixture("demo.PaymentDiffer")

        diff.changes.map { it.path.toString() } shouldContainExactly listOf("last4")
        diff.changes.filterIsInstance<TypeChanged>().shouldBeEmpty()
    }

    test("a subclass swap reports a type change") {
        val diff = payments("""Card("10", "1234")""", """Transfer("10", "FR76")""")
            .diffFixture("demo.PaymentDiffer")

        val typeChange = diff.changes.filterIsInstance<TypeChanged>().single()
        typeChange.beforeType shouldBe "Card"
        typeChange.afterType shouldBe "Transfer"
    }

    test("a subclass swap carries both values, not only their type names") {
        val result = payments("""Card("10", "1234")""", """Transfer("10", "FR76")""")
        val typeChange = result.diffFixture("demo.PaymentDiffer")
            .changes.filterIsInstance<TypeChanged>().single()

        typeChange.before!!::class.simpleName shouldBe "Card"
        typeChange.after!!::class.simpleName shouldBe "Transfer"
    }

    test("a subclass swap also reports the sealed parent's own declared properties") {
        val diff = payments("""Card("10", "1234")""", """Transfer("12", "FR76")""")
            .diffFixture("demo.PaymentDiffer")

        diff.valueChange("amount").before shouldBe "10"
        diff.valueChange("amount").after shouldBe "12"
    }

    test("a subclass swap does not descend into subclass-only properties") {
        val diff = payments("""Card("10", "1234")""", """Transfer("10", "FR76")""")
            .diffFixture("demo.PaymentDiffer")

        diff.changes.map { it.path.toString() } shouldNotContainAnyOf listOf("last4", "iban")
    }

    test("a marker sealed interface reports the type change alone") {
        val result = compile(
            model(
                """
                @Diffable sealed interface Shape
                @Diffable data class Circle(val r: String) : Shape
                @Diffable data class Square(val side: String) : Shape
                """.trimIndent(),
                """Circle("1")""",
                """Square("2")""",
            ),
        )
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val diff = result.diffFixture("demo.ShapeDiffer")

        diff.changes.size shouldBe 1
        diff.changes.single().shouldBeInstanceOf<TypeChanged>()
    }

    test("a sealed-typed property reports the type change at that property's path") {
        val result = compile(
            model(
                """
                $SEALED
                @Diffable data class Order(val payment: Payment)
                """.trimIndent(),
                """Order(Card("10", "1234"))""",
                """Order(Transfer("10", "FR76"))""",
            ),
        )
        result.exitCode shouldBe KotlinCompilation.ExitCode.OK

        val diff = result.diffFixture("demo.OrderDiffer")

        diff.changes.filterIsInstance<TypeChanged>().map { it.path.toString() } shouldContainExactly
            listOf("payment")
    }

    test("an annotated sealed class is accepted") {
        val result = compile(
            model(
                """
                @Diffable sealed class Node { abstract val id: String }
                @Diffable data class Leaf(override val id: String) : Node()
                @Diffable data class Branch(override val id: String, val size: String) : Node()
                """.trimIndent(),
                """Leaf("1")""",
                """Branch("2", "3")""",
            ),
        )

        result.exitCode shouldBe KotlinCompilation.ExitCode.OK
        result.generatedFileNames shouldContain "NodeDiff.kt"
    }
})
