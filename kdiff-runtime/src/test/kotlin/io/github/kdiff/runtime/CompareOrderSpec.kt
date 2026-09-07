package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.core.spec.style.FunSpec

/**
 * The order a comparison reports in, and the element a rejected key names.
 *
 * Both were properties of how the comparison happened to be written — a set subtraction, a second walk
 * to find a repeated key — and both are now properties of how it is written instead. Pinning them keeps
 * the rewrite from moving anything a caller reads.
 */
class CompareOrderSpec : FunSpec({

    context("a set reports removals then additions, in the order each side iterates") {
        test("removals come before additions") {
            val changes = buildList {
                compareSet("labels", setOf("a", "b", "c"), setOf("b", "d", "e"))
            }

            changes.map { change ->
                when (change) {
                    is Removed -> "removed ${change.value}"
                    is Added -> "added ${change.value}"
                    else -> "unexpected $change"
                }
            } shouldContainExactly listOf("removed a", "removed c", "added d", "added e")
        }

        test("every reported change sits at the property itself") {
            val changes = buildList { compareSet("labels", setOf("a"), setOf("b")) }

            changes.map { it.path.toString() } shouldContainExactly listOf("labels", "labels")
        }

        test("two equal sets report nothing") {
            buildList { compareSet("labels", setOf("a", "b"), setOf("b", "a")) } shouldBe emptyList()
        }
    }

    context("a repeated key names the element that repeated it") {
        val a1 = Addr("A1", "1 Rue X", "Lyon", Country("FR"))
        val a2 = Addr("A2", "2 Rue Y", "Lyon", Country("FR"))

        test("the key named is the first whose value was already seen") {
            val duplicated = listOf(a1, a2, a2.copy(street = "other"), a1.copy(city = "Nice"))

            val failure = shouldThrow<IllegalArgumentException> {
                buildList { compareKeyedList("addresses", "id", duplicated, listOf(a1), AddrDiffer) { it.id } }
            }

            failure.message.orEmpty() shouldContain "A2"
        }

        test("a duplicate in the old list is reported even when the new list also has one") {
            val oldSide = listOf(a1, a1.copy(city = "Nice"))
            val newSide = listOf(a2, a2.copy(city = "Nice"))

            val failure = shouldThrow<IllegalArgumentException> {
                buildList { compareKeyedList("addresses", "id", oldSide, newSide, AddrDiffer) { it.id } }
            }

            failure.message.orEmpty() shouldContain "A1"
        }

        test("the message names the list and the key property when both are known") {
            val failure = shouldThrow<IllegalArgumentException> {
                buildList {
                    compareKeyedList("addresses", "id", listOf(a1, a1), emptyList(), AddrDiffer) { it.id }
                }
            }

            failure.message.orEmpty() shouldContain "addresses is keyed by id"
            failure.message.orEmpty() shouldContain "addresses[id=A1] cannot name one of them"
        }
    }

    context("a keyed list reports in the old list's order, then additions in the new list's") {
        val a1 = Addr("A1", "1", "Lyon", Country("FR"))
        val a2 = Addr("A2", "2", "Lyon", Country("FR"))
        val a3 = Addr("A3", "3", "Nice", Country("FR"))

        test("a removal, a move and an addition keep their relative order") {
            val changes = buildList {
                compareKeyedList("addresses", "id", listOf(a1, a2), listOf(a2, a3), AddrDiffer) { it.id }
            }

            changes.map { it.path.toString() } shouldContainExactly listOf(
                "addresses[id=A1]",
                "addresses[id=A2]",
                "addresses[id=A3]",
            )
            changes.map { it::class } shouldContainExactly listOf(Removed::class, Moved::class, Added::class)
        }

        test("a move reports the positions the element held on each side") {
            val moved = buildList {
                compareKeyedList("addresses", "id", listOf(a1, a2), listOf(a2, a1), AddrDiffer) { it.id }
            }.filterIsInstance<Moved>()

            moved.map { Triple(it.path.toString(), it.from, it.to) } shouldContainExactly listOf(
                Triple("addresses[id=A1]", 0, 1),
                Triple("addresses[id=A2]", 1, 0),
            )
        }
    }
})
