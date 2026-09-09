package demo

import io.github.kdiff.runtime.Diff
import io.github.kdiff.runtime.Differ
import io.github.kdiff.runtime.FieldPath
import io.github.kdiff.runtime.Moved
import io.github.kdiff.runtime.PatchFailedException
import io.github.kdiff.runtime.PatchFailure
import io.github.kdiff.runtime.Segment
import io.github.kdiff.runtime.TypeChanged
import io.github.kdiff.runtime.ValueChanged
import io.github.kdiff.runtime.at
import io.github.kdiff.runtime.compareValue
import io.github.kdiff.runtime.under
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.time.Instant

/**
 * The worked recipes behind `docs/how-to.md`, one test per recipe.
 *
 * They are tests rather than prose so the how-to page cites compiled code: a recipe is the code a
 * reader copies first, which makes it the last thing that should go unchecked.
 */

private val a1 = Address("A1", "1 Rue X", "Paris")
private val a2 = Address("A2", "2 Rue Y", "Lyon")

private val order = Order(
    reference = "R-1",
    status = Status.OPEN,
    note = null,
    lastTouched = "monday",
    billing = a1,
    shipping = a2,
    addresses = listOf(a1, a2),
    tags = listOf("urgent", "fragile"),
    couponCodes = null,
    labels = setOf("a", "b"),
    amounts = mapOf("eur" to "10"),
    payment = Card("10", "1234"),
    total = Money("10", "EUR"),
    weight = Weight("500"),
    discount = BigDecimal("2.50"),
    placedAt = Instant.EPOCH,
    sku = Sku("SKU-1"),
    location = Coordinates(48.85, 2.35),
)

/** The five causes `docs/errors.md` groups `PatchFailure.Reason` by. */
private enum class Cause { CANNOT_REBUILD, UNKNOWN_PATH, WRONG_SHAPE, TARGET_ABSENT, NO_IDENTITY }

/**
 * Sorts a reason into the one thing a caller can act on.
 *
 * Exhaustive on purpose: this `when` is what holds `docs/errors.md`'s five groups to the fourteen
 * cases the vocabulary declares, so a new case fails to compile here rather than going undocumented.
 */
private fun PatchFailure.Reason.cause(): Cause = when (this) {
    is PatchFailure.Reason.NotConstructorProperty, is PatchFailure.Reason.UnpatchableProperty ->
        Cause.CANNOT_REBUILD

    is PatchFailure.Reason.UnknownProperty -> Cause.UNKNOWN_PATH

    PatchFailure.Reason.NotApplicableToValue,
    PatchFailure.Reason.NotApplicableToKeyedList,
    PatchFailure.Reason.NotApplicableToPositionalList,
    PatchFailure.Reason.NotApplicableToMap,
    -> Cause.WRONG_SHAPE

    PatchFailure.Reason.NothingBeneathNull,
    PatchFailure.Reason.NoElementForKey,
    PatchFailure.Reason.NoElementAtIndex,
    PatchFailure.Reason.NoEntryForKey,
    -> Cause.TARGET_ABSENT

    PatchFailure.Reason.ElementComparedAsValue,
    PatchFailure.Reason.EntryComparedAsValue,
    PatchFailure.Reason.SetElementNotModifiable,
    -> Cause.NO_IDENTITY
}

/**
 * Compares an amount numerically, so `"10"` and `"10.00"` agree.
 *
 * `field` compares by equality, and normalising inside the DSL would report the normalised values
 * rather than the ones the model holds — so a custom comparison is written out over the same runtime
 * helpers the DSL calls.
 */
object NumericMoneyDiffer : Differ<Money> {
    override fun diff(before: Money, after: Money): Diff = Diff(
        buildList {
            if (BigDecimal(before.amount).compareTo(BigDecimal(after.amount)) != 0) {
                add(ValueChanged(FieldPath.of("amount"), before.amount, after.amount))
            }
            compareValue("currency", before.currency, after.currency)
        },
    )
}

class RecipesSpec :
    FunSpec({

        test("auditing a change writes one line per change, with its path and both sides") {
            val diff = OrderDiffer.diff(order, order.copy(reference = "R-2", status = Status.CLOSED))

            val audit = diff.map { "${it.path}: $it" }

            audit shouldContainExactly listOf(
                "reference: ValueChanged(path=reference, before=R-1, after=R-2)",
                "status: ValueChanged(path=status, before=OPEN, after=CLOSED)",
            )
        }

        test("getOrThrow refuses a patch that did not apply in full, and names what failed") {
            val after = order.copy(reference = "R-2", weight = Weight("600"))
            val changes = OrderDiffer.diff(order, after).changes

            val raised = shouldThrow<PatchFailedException> { OrderDiffer.apply(order, changes).getOrThrow() }

            raised.failures.map { it.reason } shouldContainExactly
                listOf(PatchFailure.Reason.UnpatchableProperty("weight"))
            raised.message.orEmpty() shouldContain "1 of the changes could not be applied"
        }

        test("a partial patch keeps what applied, so the value is usable alongside the failures") {
            val after = order.copy(reference = "R-2", weight = Weight("600"))

            val result = OrderDiffer.apply(order, OrderDiffer.diff(order, after).changes)

            result.value.reference shouldBe "R-2"
            result.value.weight.grams shouldBe "500"
            result.isClean shouldBe false
        }

        test("failures sort into the causes a caller can act on") {
            val changes = OrderDiffer.diff(order, order.copy(weight = Weight("600"))).changes +
                ValueChanged(FieldPath.of("nosuch"), "before", "after") +
                ValueChanged(
                    FieldPath(listOf(Segment.Field("addresses"), Segment.Key("id", "ZZ"), Segment.Field("city"))),
                    "Paris",
                    "Nice",
                )

            val byCause = OrderDiffer.apply(order, changes).failures.groupBy { it.reason.cause() }

            byCause.keys shouldContainExactlyInAnyOrder
                listOf(Cause.CANNOT_REBUILD, Cause.UNKNOWN_PATH, Cause.TARGET_ABSENT)
        }

        test("a keyed list reports a move where an unkeyed list reports an addition and a removal") {
            val reordered = order.copy(addresses = listOf(a2, a1), tags = listOf("fragile", "urgent"))

            val changes = OrderDiffer.diff(order, reordered).changes

            changes.filterIsInstance<Moved>().map { it.path.toString() } shouldContainExactlyInAnyOrder
                listOf("addresses[id=A1]", "addresses[id=A2]")
            changes.filterNot { it is Moved }.map { it.path.toString() } shouldContainExactly
                listOf("tags[0]", "tags[1]")
        }

        test("at and under narrow a diff to one property, naming the type so the property is checked") {
            val after = order.copy(reference = "R-2", billing = a1.copy(city = "Nice"))

            val diff = OrderDiffer.diff(order, after)

            diff.at<Order>(Order::billing).changes.shouldContainExactly(emptyList())
            diff.under<Order>(Order::billing).changes.map { it.path.toString() } shouldContainExactly
                listOf("billing.city")
        }

        test("a hand-written differ compares an amount numerically rather than by equality") {
            NumericMoneyDiffer.diff(Money("10", "EUR"), Money("10.00", "EUR")).isEmpty() shouldBe true

            NumericMoneyDiffer.diff(Money("10", "EUR"), Money("12", "EUR")).changes shouldContainExactly
                listOf(ValueChanged(FieldPath.of("amount"), "10", "12"))
        }

        test("a payload-free case is a data object, and needs no annotation of its own") {
            val unpaid = order.copy(payment = Unpaid)

            // Entering the state: a type change carrying both instances, plus whatever the sealed
            // parent declares itself.
            val entering = OrderDiffer.diff(order, unpaid)
            entering.changes.filterIsInstance<TypeChanged>().single().afterType shouldBe "Unpaid"
            entering.changes.map { it.path.toString() } shouldContainExactly
                listOf("payment", "payment.amount")

            // Staying in it: nothing to report, because a singleton has no state to differ in.
            OrderDiffer.diff(unpaid, unpaid).isEmpty() shouldBe true

            // Leaving it: applied by substitution, like any type change.
            val leaving = order.copy(payment = Transfer("12", "FR76"))
            OrderDiffer.apply(unpaid, OrderDiffer.diff(unpaid, leaving).changes).value shouldBe leaving
        }

        test("a type or one property can be compared as a single value") {
            // On the type: `Coordinates` is `@DiffAsValue`, so every property of that type is one value.
            OrderDiffer.diff(order, order.copy(location = Coordinates(48.85, 2.40)))
                .changes shouldContainExactly listOf(
                ValueChanged(FieldPath.of("location"), Coordinates(48.85, 2.35), Coordinates(48.85, 2.40)),
            )

            // On the property: `Address` is `@Diffable`, and `Site.at` alone is compared as one value.
            val site = Site("depot", a1)
            val moved = a1.copy(city = "Nice")

            SiteDiffer.diff(site, site.copy(at = moved)).changes shouldContainExactly
                listOf(ValueChanged(FieldPath.of("at"), a1, moved))
        }

        test("a nullable collection appearing or disappearing is one change at the property") {
            val withoutCoupons = order.copy(couponCodes = null)
            val withCoupons = order.copy(couponCodes = listOf("SAVE10"))

            OrderDiffer.diff(withoutCoupons, withCoupons).changes shouldContainExactly
                listOf(ValueChanged(FieldPath.of("couponCodes"), null, listOf("SAVE10")))

            // Present on both sides it is compared as a list, element by element.
            OrderDiffer.diff(withCoupons, order.copy(couponCodes = listOf("SAVE20")))
                .changes.map { it.path.toString() } shouldContainExactly listOf("couponCodes[0]")

            // Absent is not empty: the two are distinguishable, and report differently.
            val empty = order.copy(couponCodes = emptyList())
            OrderDiffer.diff(withoutCoupons, empty).changes shouldContainExactly
                listOf(ValueChanged(FieldPath.of("couponCodes"), null, emptyList<String>()))
            OrderDiffer.diff(empty, order.copy(couponCodes = listOf("a")))
                .changes.map { it.path.toString() } shouldContainExactly listOf("couponCodes[0]")
        }
    })
