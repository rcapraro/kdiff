package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

private data class Place(val id: String, val street: String)

private object PlaceDiffer :
    Differ<Place> by differ({
        field(Place::id)
        field(Place::street)
    }),
    Patcher<Place> {
    override fun apply(before: Place, changes: List<Change>): PatchResult<Place> {
        val grouped = groupByProperty(changes, setOf("id", "street"))
        val id = patchValue(before.id, grouped.forProperty("id"))
        val street = patchValue(before.street, grouped.forProperty("street"))
        return PatchResult(
            before.copy(id = id.value, street = street.value),
            grouped.unmatchedFailures("Place") + id.failures + street.failures,
        )
    }
}

/** A holder whose every collection property is nullable, so all four shapes are exercised at once. */
private data class Holder(
    val reference: String,
    val tags: List<String>?,
    val places: List<Place>?,
    val labels: Set<String>?,
    val amounts: Map<String, String>?,
)

/**
 * Described by hand, which is the only route this module can reach: `kdiff-runtime` does not depend on
 * the processor. It calls the same helpers the generated route calls, so what is pinned here is the
 * rule rather than one route's rendering of it — and `kdiff-sample`'s `HandWrittenParitySpec` is where
 * the two routes are held to identical output.
 */
private val HolderDiffer: Differ<Holder> = differ {
    field(Holder::reference)
    list(Holder::tags)
    keyedList(Holder::places, Place::id, PlaceDiffer)
    set(Holder::labels)
    map(Holder::amounts)
}

/** The shape the processor emits for a nullable collection: the shape's own helper, inside `patchNullable`. */
private object HolderPatcher : Patcher<Holder> {
    private val comparedProperties = setOf("reference", "tags", "places", "labels", "amounts")

    override fun apply(before: Holder, changes: List<Change>): PatchResult<Holder> {
        val grouped = groupByProperty(changes, comparedProperties)
        val reference = patchValue(before.reference, grouped.forProperty("reference"))
        val tags = patchNullable(before.tags, grouped.forProperty("tags")) { value, own ->
            patchPositionalList(value, own, null)
        }
        val places = patchNullable(before.places, grouped.forProperty("places")) { value, own ->
            patchKeyedList(value, own, PlaceDiffer, "places", "id") { it.id }
        }
        val labels = patchNullable(before.labels, grouped.forProperty("labels")) { value, own ->
            patchSet(value, own)
        }
        val amounts = patchNullable(before.amounts, grouped.forProperty("amounts")) { value, own ->
            patchMap(value, own, null)
        }
        return PatchResult(
            before.copy(
                reference = reference.value,
                tags = tags.value,
                places = places.value,
                labels = labels.value,
                amounts = amounts.value,
            ),
            buildList {
                addAll(grouped.unmatchedFailures("Holder"))
                addAll(reference.failures)
                addAll(tags.failures)
                addAll(places.failures)
                addAll(labels.failures)
                addAll(amounts.failures)
            },
        )
    }
}

private val x = Place("A1", "Rue X")
private val y = Place("A2", "Rue Y")

private val present = Holder(
    reference = "R-1",
    tags = listOf("a", "b"),
    places = listOf(x, y),
    labels = setOf("x"),
    amounts = mapOf("eur" to "10"),
)

private val absent = Holder(reference = "R-1", tags = null, places = null, labels = null, amounts = null)

private fun diff(before: Holder, after: Holder): List<Change> = HolderDiffer.diff(before, after).changes

private fun roundTrip(before: Holder, after: Holder): PatchResult<Holder> =
    HolderPatcher.apply(before, diff(before, after).toList())

class NullableCollectionCompareSpec :
    FunSpec({

        context("a null on one side is a value change at the property") {
            test("a null list becoming a list is one value change carrying both sides") {
                val after = absent.copy(tags = listOf("a", "b"))

                diff(absent, after) shouldContainExactly
                    listOf(ValueChanged(FieldPath.of("tags"), null, listOf("a", "b")))
            }

            test("no element of an appearing list is reported as an addition") {
                val changes = diff(absent, absent.copy(tags = listOf("a", "b")))

                changes.filterIsInstance<Added>().shouldBeEmpty()
                changes.map { it.path.toString() } shouldContainExactly listOf("tags")
            }

            test("a list becoming null is one value change carrying both sides") {
                val before = absent.copy(tags = listOf("a"))

                diff(before, absent) shouldContainExactly
                    listOf(ValueChanged(FieldPath.of("tags"), listOf("a"), null))
            }

            test("no element of a disappearing list is reported as a removal") {
                val changes = diff(absent.copy(tags = listOf("a")), absent)

                changes.filterIsInstance<Removed>().shouldBeEmpty()
            }

            test("a keyed list appearing is one value change, with nothing at an element's key") {
                val changes = diff(absent, absent.copy(places = listOf(x)))

                changes.map { it.path.toString() } shouldContainExactly listOf("places")
            }

            test("a set appearing and a map disappearing each report one value change") {
                val before = absent.copy(labels = null, amounts = mapOf("eur" to "10"))
                val after = absent.copy(labels = setOf("x"), amounts = null)

                diff(before, after) shouldContainExactly listOf(
                    ValueChanged(FieldPath.of("labels"), null, setOf("x")),
                    ValueChanged(FieldPath.of("amounts"), mapOf("eur" to "10"), null),
                )
            }
        }

        context("null on both sides is not a change") {
            test("all four nullable collections null on both sides report nothing") {
                diff(absent, absent).shouldBeEmpty()
            }

            test("a nullable collection null on both sides leaves the other properties compared") {
                diff(absent, absent.copy(reference = "R-2")) shouldContainExactly
                    listOf(ValueChanged(FieldPath.of("reference"), "R-1", "R-2"))
            }
        }

        context("two present collections are compared as collections") {
            test("a present list is compared by position") {
                diff(present, present.copy(tags = listOf("a", "c"))) shouldContainExactly
                    listOf(ValueChanged(FieldPath(listOf(Segment.Field("tags"), Segment.Index(1))), "b", "c"))
            }

            test("a present keyed list descends into its elements") {
                val after = present.copy(places = listOf(x, y.copy(street = "Rue Q")))

                diff(present, after).map { it.path.toString() } shouldContainExactly listOf("places[id=A2].street")
            }

            test("a present keyed list reports a reorder as a move") {
                val changes = diff(present, present.copy(places = listOf(y, x)))

                changes.filterIsInstance<Moved>().map { it.path.toString() } shouldContainExactly
                    listOf("places[id=A1]", "places[id=A2]")
            }

            test("a present set is compared by membership") {
                val changes = diff(present, present.copy(labels = setOf("y")))

                changes shouldContainExactly listOf(
                    Removed(FieldPath.of("labels"), "x"),
                    Added(FieldPath.of("labels"), "y"),
                )
            }

            test("a present map is compared by entry key") {
                val changes = diff(present, present.copy(amounts = mapOf("eur" to "12")))

                changes.map { it.path.toString() } shouldContainExactly listOf("amounts[key=eur]")
            }
        }
    })

class NullableCollectionPatchSpec :
    FunSpec({

        context("a nullable collection round-trips in all four transitions") {
            test("a list that appears") {
                val after = absent.copy(tags = listOf("a", "b"))
                val result = roundTrip(absent, after)

                result.value shouldBe after
                result.failures.shouldBeEmpty()
            }

            test("a list that disappears") {
                val before = absent.copy(tags = listOf("a"))
                val result = roundTrip(before, absent)

                result.value shouldBe absent
                result.failures.shouldBeEmpty()
            }

            test("collections present on both sides") {
                val after = present.copy(
                    tags = listOf("a", "c"),
                    amounts = mapOf("eur" to "10", "usd" to "11"),
                )
                val result = roundTrip(present, after)

                result.value shouldBe after
                result.failures.shouldBeEmpty()
            }

            test("collections null on both sides") {
                val result = roundTrip(absent, absent.copy(reference = "R-2"))

                result.value shouldBe absent.copy(reference = "R-2")
                result.failures.shouldBeEmpty()
            }

            test("a keyed list present on both sides, with a move") {
                val after = present.copy(places = listOf(y, x))
                val result = roundTrip(present, after)

                result.value.places shouldContainExactly listOf(y, x)
                result.failures.shouldBeEmpty()
            }

            test("every shape at once, from absent to present and back") {
                roundTrip(absent, present).value shouldBe present
                roundTrip(present, absent).value shouldBe absent
            }
        }

        context("nothing can be rebuilt beneath a null collection") {
            test("a change beneath a null list is reported and the property stays null") {
                val beneath = ValueChanged(
                    FieldPath(listOf(Segment.Field("tags"), Segment.Index(0))),
                    "a",
                    "b",
                )

                val result = HolderPatcher.apply(absent, listOf(beneath))

                result.value.tags shouldBe null
                result.failures.single().reason shouldBe PatchFailure.Reason.NothingBeneathNull
            }

            test("the rest of the instance is still patched") {
                val beneath = ValueChanged(FieldPath(listOf(Segment.Field("tags"), Segment.Index(0))), "a", "b")
                val elsewhere = ValueChanged(FieldPath.of("reference"), "R-1", "R-2")

                val result = HolderPatcher.apply(absent, listOf(beneath, elsewhere))

                result.value.reference shouldBe "R-2"
                result.value.tags shouldBe null
                result.failures.single().reason shouldBe PatchFailure.Reason.NothingBeneathNull
            }
        }

        context("an unaddressed nullable collection is carried through") {
            test("a present set no change addresses is the source's own instance") {
                val result = roundTrip(present, present.copy(reference = "R-2"))

                result.value.labels shouldBeSameInstanceAs present.labels
                result.value.amounts shouldBeSameInstanceAs present.amounts
                result.value.tags shouldBeSameInstanceAs present.tags
            }
        }
    })
