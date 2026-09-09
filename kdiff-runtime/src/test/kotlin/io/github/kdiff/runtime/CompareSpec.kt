package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private data class Address(val id: String, val street: String)

private object AddressDiffer : Differ<Address> by differ({
    field(Address::id)
    field(Address::street)
})

private fun compare(block: MutableList<Change>.() -> Unit): List<Change> = buildList(block)

class CompareSpec :
    FunSpec({

        context("values") {
            test("equal values produce no change") {
                compare { compareValue("name", "Ada", "Ada") } shouldBe emptyList()
            }

            test("differing values produce one change carrying both sides") {
                compare { compareValue("name", "Ada", "Grace") } shouldContainExactly
                    listOf(ValueChanged(FieldPath.of("name"), "Ada", "Grace"))
            }

            test("null becoming a value is a value change, not an addition") {
                val changes = compare { compareValue("nickname", null, "Ada") }

                changes shouldContainExactly listOf(ValueChanged(FieldPath.of("nickname"), null, "Ada"))
            }

            test("a value becoming null is a value change, not a removal") {
                val changes = compare { compareValue("nickname", "Ada", null) }

                changes shouldContainExactly listOf(ValueChanged(FieldPath.of("nickname"), "Ada", null))
            }

            test("null on both sides is not a change") {
                compare { compareValue("nickname", null, null) } shouldBe emptyList()
            }
        }

        context("nested") {
            test("a nested change is reported at a nested path") {
                val changes = compare {
                    compareNested("address", Address("A1", "Rue X"), Address("A1", "Rue Y"), AddressDiffer)
                }

                changes.map { it.path.toString() } shouldContainExactly listOf("address.street")
            }

            test("a nullable nested property becoming null is a value change with nothing beneath it") {
                val changes = compare {
                    compareNestedNullable("address", Address("A1", "Rue X"), null, AddressDiffer)
                }

                changes.map { it.path.toString() } shouldContainExactly listOf("address")
                changes.single().shouldBeValueChange()
            }

            test("a nullable nested property null on both sides is not a change") {
                compare { compareNestedNullable("address", null, null, AddressDiffer) } shouldBe emptyList()
            }

            test("a nullable nested property non-null on both sides delegates") {
                val changes = compare {
                    compareNestedNullable("address", Address("A1", "Rue X"), Address("A1", "Rue Y"), AddressDiffer)
                }

                changes.map { it.path.toString() } shouldContainExactly listOf("address.street")
            }
        }

        context("keyed lists") {
            val a1 = Address("A1", "Rue 1")
            val a2 = Address("A2", "Rue 2")
            val a3 = Address("A3", "Rue 3")

            fun keyed(before: List<Address>, after: List<Address>) = compare {
                compareKeyedList("addresses", "id", before, after, AddressDiffer) { it.id }
            }

            test("an unchanged list produces no changes") {
                keyed(listOf(a1, a2), listOf(a1, a2)) shouldBe emptyList()
            }

            test("a modified element is reported at its key") {
                val changes = keyed(listOf(a1, a2), listOf(a1, a2.copy(street = "Rue X")))

                changes.map { it.path.toString() } shouldContainExactly listOf("addresses[id=A2].street")
            }

            test("a new element is reported as added") {
                val changes = keyed(listOf(a1), listOf(a1, a3))

                changes shouldContainExactly listOf(
                    Added(FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "A3"))), a3),
                )
            }

            test("a missing element is reported as removed") {
                val changes = keyed(listOf(a1, a2), listOf(a2))

                changes.filterIsInstance<Removed>().map { it.path.toString() } shouldContainExactly
                    listOf("addresses[id=A1]")
            }

            test("a reordered element is reported as moved, not as removed and added") {
                val changes = keyed(listOf(a1, a2), listOf(a2, a1))

                changes.filterIsInstance<Moved>().map { it.path.toString() } shouldContainExactlyInAnyOrder
                    listOf("addresses[id=A1]", "addresses[id=A2]")
                changes.filterIsInstance<Removed>() shouldBe emptyList()
                changes.filterIsInstance<Added>() shouldBe emptyList()
            }

            test("a move carries the old and new index") {
                val moved = keyed(listOf(a1, a2), listOf(a2, a1)).filterIsInstance<Moved>()
                    .single { it.path.toString() == "addresses[id=A2]" }

                moved.from shouldBe 1
                moved.to shouldBe 0
            }

            val shadowed = Address("A1", "Rue 9")

            test("a repeated key in the old list is rejected") {
                shouldThrow<IllegalArgumentException> { keyed(listOf(a1, shadowed), listOf(a1)) }
                    .message shouldBe
                    "addresses is keyed by id, but two elements share the key A1. " +
                    "A keyed element must be uniquely identified; addresses[id=A1] cannot name one of them."
            }

            test("a repeated key in the new list is rejected") {
                shouldThrow<IllegalArgumentException> { keyed(listOf(a1), listOf(a1, shadowed)) }
                    .message.shouldContain("share the key A1")
            }

            test("a repeated key is rejected even when the two lists are otherwise equal") {
                shouldThrow<IllegalArgumentException> {
                    keyed(listOf(a1, shadowed), listOf(a1, shadowed))
                }
            }

            test("a repeated key is rejected even when the duplicated elements are identical") {
                shouldThrow<IllegalArgumentException> { keyed(listOf(a1, a1), listOf(a1)) }
            }

            test("a rejected comparison contributes nothing to the caller's change list") {
                val changes = mutableListOf<Change>()

                shouldThrow<IllegalArgumentException> {
                    changes.compareKeyedList("addresses", "id", listOf(a1, shadowed), listOf(a1), AddressDiffer) {
                        it.id
                    }
                }

                changes.shouldBeEmpty()
            }

            test("the same key appearing once in each list is not a duplicate") {
                keyed(listOf(a1), listOf(a1.copy(street = "Rue X"))).map { it.path.toString() } shouldContainExactly
                    listOf("addresses[id=A1].street")
            }
        }

        context("positional lists") {
            fun positional(before: List<String>, after: List<String>) = compare {
                comparePositionalList("tags", before, after, differ = null)
            }

            test("a changed element is reported at its index") {
                val changes = positional(listOf("a", "b"), listOf("a", "c"))

                changes.map { it.path.toString() } shouldContainExactly listOf("tags[1]")
            }

            test("repeated elements are compared by position rather than rejected") {
                positional(listOf("a", "a"), listOf("a", "a")).shouldBeEmpty()
                positional(listOf("a", "a"), listOf("a", "b")).map { it.path.toString() } shouldContainExactly
                    listOf("tags[1]")
            }

            test("a longer new list reports additions at the trailing indices") {
                val changes = positional(listOf("a", "b"), listOf("a", "b", "c"))

                changes shouldContainExactly listOf(
                    Added(FieldPath(listOf(Segment.Field("tags"), Segment.Index(2))), "c"),
                )
            }

            test("a shorter new list reports removals at the trailing indices") {
                val changes = positional(listOf("a", "b", "c"), listOf("a", "b"))

                changes shouldContainExactly listOf(
                    Removed(FieldPath(listOf(Segment.Field("tags"), Segment.Index(2))), "c"),
                )
            }

            test("a move is never reported for a positional list") {
                val changes = positional(listOf("a", "b"), listOf("b", "a"))

                changes.filterIsInstance<Moved>() shouldBe emptyList()
            }
        }

        context("sets") {
            fun set(before: Set<String>, after: Set<String>) = compare { compareSet("tags", before, after) }

            test("added and removed members are reported and unchanged ones are not") {
                val changes = set(setOf("a", "b"), setOf("b", "c"))

                changes shouldContainExactlyInAnyOrder listOf(
                    Removed(FieldPath.of("tags"), "a"),
                    Added(FieldPath.of("tags"), "c"),
                )
            }

            test("the same members in a different order is not a change") {
                set(setOf("a", "b"), setOf("b", "a")) shouldBe emptyList()
            }

            test("a set never reports a move") {
                set(setOf("a", "b"), setOf("b", "c")).filterIsInstance<Moved>() shouldBe emptyList()
            }
        }

        context("maps") {
            fun map(before: Map<String, String>, after: Map<String, String>) = compare {
                compareMap("rates", before, after, differ = null)
            }

            test("a changed value is reported at its entry key") {
                val changes = map(mapOf("eur" to "1.0"), mapOf("eur" to "1.1"))

                changes.map { it.path.toString() } shouldContainExactly listOf("rates[key=eur]")
            }

            test("an entry's key segment carries the declared map-entry constant") {
                val changes = compare { compareMap("amounts", mapOf("eur" to "1.0"), mapOf("eur" to "1.1"), null) }

                val segment = changes.single().path.segments.last() as Segment.Key
                segment.property shouldBe Segment.Key.MAP_ENTRY
                changes.single().path.toString() shouldBe "amounts[key=eur]"
            }

            test("added and removed entries are reported") {
                val changes = map(mapOf("gbp" to "1.0"), mapOf("usd" to "1.0"))

                changes.filterIsInstance<Removed>().map { it.path.toString() } shouldContainExactly
                    listOf("rates[key=gbp]")
                changes.filterIsInstance<Added>().map { it.path.toString() } shouldContainExactly
                    listOf("rates[key=usd]")
            }

            test("a map delegates into its value type when one is supplied") {
                val changes = compare {
                    compareMap(
                        "byId",
                        mapOf("a" to Address("A1", "Rue X")),
                        mapOf("a" to Address("A1", "Rue Y")),
                        AddressDiffer,
                    )
                }

                changes.map { it.path.toString() } shouldContainExactly listOf("byId[key=a].street")
            }
        }
    })

private fun Change.shouldBeValueChange() {
    check(this is ValueChanged) { "expected a value change but was $this" }
}
