package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * A nullable declaration of a property must answer exactly as its non-null twin does.
 *
 * The gap these pin: the nullable wrapper sits in front of the shape's own helper, and a wrapper that
 * short-circuits reaches neither the helper's preconditions nor its reporting. A keyed list holding a
 * repeated key is the library's one such precondition, and dropping a change is the one such reporting.
 */

private data class Stop(val id: String, val city: String)

private object StopDiffer :
    Differ<Stop> by differ({
        field(Stop::id)
        field(Stop::city)
    }),
    Patcher<Stop> {
    override fun apply(before: Stop, changes: List<Change>): PatchResult<Stop> {
        val grouped = groupByProperty(changes, setOf("id", "city"))
        val city = patchValue(before.city, grouped.forProperty("city"))
        return PatchResult(before.copy(city = city.value), grouped.unmatchedFailures("Stop") + city.failures)
    }
}

private val repeated = listOf(Stop("A1", "Paris"), Stop("A1", "Nice"))
private val unique = listOf(Stop("A1", "Paris"), Stop("A2", "Nice"))

private fun patchStops(source: List<Stop>?, changes: List<Change>): Patched<List<Stop>?> =
    patchNullable(source, changes) { value, own ->
        patchKeyedList(value, own, StopDiffer, "stops", "id") { it.id }
    }

private fun compareStops(before: List<Stop>?, after: List<Stop>?): List<Change> = buildList {
    compareKeyedList("stops", "id", before, after, StopDiffer) { it.id }
}

class NullableKeyedListPreconditionSpec :
    FunSpec({

        context("applying refuses a repeated key through the nullable wrapper too") {
            test("an empty change list is refused, exactly as the non-null helper refuses it") {
                shouldThrow<DuplicateDiffKeyException> { patchKeyedList(repeated, emptyList(), StopDiffer) { it.id } }

                shouldThrow<DuplicateDiffKeyException> { patchStops(repeated, emptyList()) }
            }

            test("the refusal names the list and the key the caller gave") {
                val raised = shouldThrow<DuplicateDiffKeyException> { patchStops(repeated, emptyList()) }

                raised.property shouldBe "stops"
                raised.keyProperty shouldBe "id"
                raised.key shouldBe "A1"
            }

            test("a change addressed elsewhere does not exempt the list") {
                val inside = FieldPath(listOf(Segment.Key("id", "A1"), Segment.Field("city")))
                val elsewhere = ValueChanged(inside, "a", "b")

                shouldThrow<DuplicateDiffKeyException> { patchStops(repeated, listOf(elsewhere)) }
            }

            test("a wholesale replacement does not exempt the source either") {
                val discard = ValueChanged(FieldPath.ROOT, repeated, null)

                shouldThrow<DuplicateDiffKeyException> { patchStops(repeated, listOf(discard)) }
            }

            test("a null source has nothing to examine and is not refused") {
                shouldNotThrowAny { patchStops(null, emptyList()) }
            }

            test("a unique list still applies") {
                patchStops(unique, emptyList()).value shouldBe unique
            }
        }

        context("comparing refuses a repeated key on whichever side is present") {
            test("null against a repeated-key list is refused rather than reported") {
                shouldThrow<DuplicateDiffKeyException> { compareStops(null, repeated) }
            }

            test("a repeated-key list against null is refused too") {
                shouldThrow<DuplicateDiffKeyException> { compareStops(repeated, null) }
            }

            test("the transition is still reported when both present sides are unique") {
                compareStops(null, unique) shouldContainExactly
                    listOf(ValueChanged(FieldPath.of("stops"), null, unique))
            }

            test("null on both sides examines nothing and reports nothing") {
                compareStops(null, null).shouldBeEmpty()
            }
        }
    })

class NullableWholesaleLeftoverSpec :
    FunSpec({

        test("a change beneath a wholesale replacement is reported, not dropped") {
            val wholesale = ValueChanged(FieldPath.ROOT, null, listOf("a"))
            val beneath = ValueChanged(FieldPath(listOf(Segment.Index(0))), "a", "b")

            val result = patchNullable(listOf("a") as List<String>?, listOf(wholesale, beneath)) { value, own ->
                patchPositionalList(value, own, null)
            }

            result.value shouldContainExactly listOf("a")
            result.failures shouldContainExactly
                listOf(PatchFailure(beneath, PatchFailure.Reason.NotApplicableToValue))
        }

        test("a wholesale replacement on its own reports nothing") {
            val wholesale = ValueChanged(FieldPath.ROOT, listOf("a"), null)

            val result = patchNullable(listOf("a") as List<String>?, listOf(wholesale)) { value, own ->
                patchPositionalList(value, own, null)
            }

            result.value shouldBe null
            result.failures.shouldBeEmpty()
        }

        test("the last value change at the property wins, and the earlier one is reported") {
            val first = ValueChanged(FieldPath.ROOT, null, listOf("a"))
            val second = ValueChanged(FieldPath.ROOT, null, listOf("b"))

            val result = patchNullable(null as List<String>?, listOf(first, second)) { value, own ->
                patchPositionalList(value, own, null)
            }

            result.value shouldContainExactly listOf("b")
            result.failures shouldContainExactly
                listOf(PatchFailure(first, PatchFailure.Reason.NotApplicableToValue))
        }

        // A set reports its own elements at the empty path, so the wholesale test must not steal them.
        test("a nullable set's element changes still reach its helper") {
            val added = Added(FieldPath.ROOT, "b")

            val result = patchNullable(setOf("a") as Set<String>?, listOf(added)) { value, own ->
                patchSet(value, own)
            }

            result.value shouldBe setOf("a", "b")
            result.failures.shouldBeEmpty()
        }
    })
