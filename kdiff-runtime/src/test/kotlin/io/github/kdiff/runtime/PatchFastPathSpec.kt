package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * Applying carries an unaddressed property through rather than rebuilding it, and does not thereby
 * skip a precondition the property's shape requires.
 *
 * Identity is what these assert on, because equality would pass either way: the point is that the work
 * is not done, not that the outcome differs.
 */
class PatchFastPathSpec :
    FunSpec({

        context("a property no change addresses is carried through") {
            test("a value property is returned as the source value") {
                val source = "unchanged"

                patchValue(source, emptyList()).value shouldBeSameInstanceAs source
            }

            test("a set is returned as the source instance, not a copy") {
                val source = setOf("a", "b")

                val patched = patchSet(source, emptyList())

                patched.value shouldBeSameInstanceAs source
                patched.failures shouldBe emptyList()
            }

            test("a map is returned as the source instance, not a copy") {
                val source = mapOf("eur" to "1.0")

                val patched = patchMap(source, emptyList(), null)

                patched.value shouldBeSameInstanceAs source
                patched.failures shouldBe emptyList()
            }

            test("a positional list is returned as the source instance, not a copy") {
                val source = listOf("a", "b")

                val patched = patchPositionalList(source, emptyList(), null)

                patched.value shouldBeSameInstanceAs source
                patched.failures shouldBe emptyList()
            }

            test("a keyed list with unique keys is returned as the source instance") {
                val source = listOf(ADDR_1, ADDR_2)

                val patched = patchKeyedList(source, emptyList(), AddrPatcher, "addresses", "id") { it.id }

                patched.value shouldBeSameInstanceAs source
                patched.failures shouldBe emptyList()
            }
        }

        context("carrying through never skips the keyed list's precondition") {
            val repeated = listOf(ADDR_1, ADDR_1.copy(city = "Nice"))

            test("a repeated key is rejected even when no change addresses the list") {
                val failure = shouldThrow<IllegalArgumentException> {
                    patchKeyedList(repeated, emptyList(), AddrPatcher, "addresses", "id") { it.id }
                }

                failure.message.orEmpty() shouldContain "addresses is keyed by id"
                failure.message.orEmpty() shouldContain "A1"
            }

            test("a repeated key is rejected the same way when changes do address the list") {
                val change = Removed(FieldPath(listOf(Segment.Key("id", "A1"))), ADDR_1)

                shouldThrow<IllegalArgumentException> {
                    patchKeyedList(repeated, listOf(change), AddrPatcher, "addresses", "id") { it.id }
                }
            }

            // A null element makes `Map.put` return null whether or not the key was already there, so a
            // duplicate detected from that return value alone slips through and one element is lost.
            test("a repeated key is rejected even when the elements themselves are null") {
                val nulls = listOf<Addr?>(null, null)

                val failure = shouldThrow<IllegalArgumentException> {
                    patchKeyedList(nulls, emptyList(), NullableAddrPatcher, "addresses", "id") { it?.id ?: "none" }
                }

                failure.message.orEmpty() shouldContain "none"
            }

            test("a comparison rejects a repeated key among null elements too") {
                val nulls = listOf<Addr?>(null, null)

                shouldThrow<IllegalArgumentException> {
                    buildList {
                        compareKeyedList("addresses", "id", nulls, emptyList(), NullableAddrDiffer) { it?.id ?: "none" }
                    }
                }
            }

            test("a hand-written patcher naming neither list nor key still names the value") {
                val failure = shouldThrow<IllegalArgumentException> {
                    patchKeyedList(repeated, emptyList(), AddrPatcher) { it.id }
                }

                failure.message.orEmpty() shouldContain "two elements of a keyed list share the key A1"
            }
        }

        context("a keyed list that is rebuilt still honours order") {
            test("a removal, an addition and a move combine to the expected order") {
                val source = listOf(ADDR_1, ADDR_2, ADDR_3)
                val changes = listOf(
                    Removed(FieldPath(listOf(Segment.Key("id", "A2"))), ADDR_2),
                    Moved(FieldPath(listOf(Segment.Key("id", "A3"))), 2, 0),
                    Added(FieldPath(listOf(Segment.Key("id", "A4"))), ADDR_4),
                )

                val patched = patchKeyedList(source, changes, AddrPatcher, "addresses", "id") { it.id }

                patched.value.map { it.id } shouldBe listOf("A3", "A1", "A4")
                patched.failures shouldBe emptyList()
            }

            test("an addition of a key already present replaces it where it stands") {
                val source = listOf(ADDR_1, ADDR_2)
                val replacement = ADDR_1.copy(city = "Nice")
                val changes = listOf(Added(FieldPath(listOf(Segment.Key("id", "A1"))), replacement))

                val patched = patchKeyedList(source, changes, AddrPatcher, "addresses", "id") { it.id }

                patched.value shouldBe listOf(replacement, ADDR_2)
            }
        }
    })

internal val ADDR_4 = Addr("A4", "4 Rue W", "Nice", Country("FR"))

/** A nullable element type, which is what makes a `put`-based duplicate check unsound. */
internal object NullableAddrPatcher : Patcher<Addr?> {
    override fun apply(before: Addr?, changes: List<Change>): PatchResult<Addr?> = PatchResult(before)
}

internal object NullableAddrDiffer : Differ<Addr?> {
    override fun diff(before: Addr?, after: Addr?): Diff = Diff(emptyList())
}

/** The reconstruction half of `AddrDiffer`, written out because the builder cannot construct. */
internal object AddrPatcher : Patcher<Addr> {
    override fun apply(before: Addr, changes: List<Change>): PatchResult<Addr> {
        val grouped = groupByProperty(changes, setOf("id", "street", "city", "country"))
        val id = patchValue(before.id, grouped.forProperty("id"))
        val street = patchValue(before.street, grouped.forProperty("street"))
        val city = patchValue(before.city, grouped.forProperty("city"))
        val country = patchValue(before.country, grouped.forProperty("country"))
        return PatchResult(
            Addr(id.value, street.value, city.value, country.value),
            buildList {
                addAll(grouped.unmatchedFailures("Addr"))
                addAll(id.failures)
                addAll(street.failures)
                addAll(city.failures)
                addAll(country.failures)
            },
        )
    }
}
