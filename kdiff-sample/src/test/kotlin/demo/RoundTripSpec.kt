package demo

import io.github.kdiff.runtime.PatchFailure
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Instant

private val a1 = Address("A1", "1 Rue X", "Paris")
private val a2 = Address("A2", "2 Rue Y", "Lyon")
private val a3 = Address("A3", "3 Rue W", "Nantes")

private val order = Order(
    reference = "R-1",
    status = Status.OPEN,
    note = null,
    lastTouched = "monday",
    billing = a1,
    shipping = a2,
    addresses = listOf(a1, a2),
    tags = listOf("urgent", "b2b"),
    couponCodes = listOf("SAVE10"),
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

/** The change's headline property: a diff applied to its own source reproduces the target. */
private fun roundTrip(before: Order, after: Order) {
    val result = OrderDiffer.apply(before, OrderDiffer.diff(before, after).changes)

    result.failures.shouldBeEmpty()
    OrderDiffer.diff(result.value, after).changes.shouldBeEmpty()
}

private fun roundTripFrom(after: Order) = roundTrip(order, after)

class RoundTripSpec :
    FunSpec({

        test("values and enums") {
            roundTripFrom(order.copy(reference = "R-2", status = Status.CLOSED))
        }

        test("a nullable property in both directions") {
            roundTripFrom(order.copy(note = "rush"))
            roundTrip(order.copy(note = "rush"), order.copy(note = null))
        }

        test("a nested annotated type") {
            roundTripFrom(order.copy(billing = a1.copy(street = "9 Rue Z", city = "Nice")))
        }

        test("a nullable nested type in both directions") {
            roundTripFrom(order.copy(shipping = null))
            roundTrip(order.copy(shipping = null), order)
        }

        test("a keyed list with a modification, an addition, a removal and a move at once") {
            roundTripFrom(order.copy(addresses = listOf(a2.copy(city = "Nice"), a3)))
        }

        test("a keyed list reordered only") {
            roundTripFrom(order.copy(addresses = listOf(a2, a1)))
        }

        test("an unkeyed list that changed length in both directions") {
            roundTripFrom(order.copy(tags = listOf("urgent", "b2b", "new")))
            roundTripFrom(order.copy(tags = listOf("urgent")))
            roundTripFrom(order.copy(tags = listOf("calm", "b2b")))
        }

        test("a nullable list in all four transitions") {
            roundTripFrom(order.copy(couponCodes = null))
            roundTrip(order.copy(couponCodes = null), order)
            roundTripFrom(order.copy(couponCodes = listOf("SAVE20", "FREESHIP")))
            roundTrip(order.copy(couponCodes = null), order.copy(couponCodes = null))
        }

        test("a set") {
            roundTripFrom(order.copy(labels = setOf("b", "c")))
        }

        test("a map with a changed, an added and a removed entry") {
            roundTripFrom(order.copy(amounts = mapOf("eur" to "12", "usd" to "3")))
        }

        test("a sealed subclass change") {
            roundTripFrom(order.copy(payment = Transfer("12", "FR76")))
        }

        test("a sealed value keeping its subclass") {
            roundTripFrom(order.copy(payment = Card("10", "5678")))
        }

        test("a swap to and from a sealed singleton") {
            roundTripFrom(order.copy(payment = Unpaid))
            roundTrip(order.copy(payment = Unpaid), order)
            roundTrip(order.copy(payment = Unpaid), order.copy(payment = Transfer("12", "FR76")))
        }

        test("a sealed singleton unchanged on both sides") {
            roundTrip(order.copy(payment = Unpaid), order.copy(payment = Unpaid))
        }

        test("a subclass change yields the target's subclass") {
            val after = order.copy(payment = Transfer("12", "FR76"))

            val result = OrderDiffer.apply(order, OrderDiffer.diff(order, after).changes)

            result.value.payment.shouldBeInstanceOf<Transfer>()
        }

        test("a hand-written differ that can also patch") {
            roundTripFrom(order.copy(total = Money("12", "EUR")))
        }

        test("standard-library value types") {
            roundTripFrom(order.copy(discount = BigDecimal("3.00"), placedAt = Instant.ofEpochSecond(90)))
        }

        test("a big decimal changing only in scale") {
            roundTripFrom(order.copy(discount = BigDecimal("2.500")))
        }

        test("an inline value class") {
            roundTripFrom(order.copy(sku = Sku("SKU-2")))
        }

        test("a type declared @DiffAsValue is set wholesale") {
            val after = order.copy(location = Coordinates(48.85, 2.40))

            val result = OrderDiffer.apply(order, OrderDiffer.diff(order, after).changes)

            result.failures.shouldBeEmpty()
            result.value.location shouldBe after.location
        }

        test("everything at once") {
            roundTripFrom(
                order.copy(
                    reference = "R-9",
                    status = Status.CLOSED,
                    note = "rush",
                    billing = a1.copy(city = "Nice"),
                    shipping = null,
                    addresses = listOf(a2.copy(street = "changed"), a3),
                    tags = listOf("only"),
                    labels = setOf("c"),
                    amounts = mapOf("usd" to "7"),
                    payment = Transfer("99", "FR76"),
                    total = Money("99", "USD"),
                    discount = BigDecimal("9.99"),
                    placedAt = Instant.ofEpochSecond(99),
                    sku = Sku("SKU-9"),
                    location = Coordinates(1.0, 2.0),
                ),
            )
        }

        test("an ignored property keeps the source value and reports nothing") {
            val after = order.copy(lastTouched = "friday")

            val result = OrderDiffer.apply(order, OrderDiffer.diff(order, after).changes)

            result.failures.shouldBeEmpty()
            result.value.lastTouched shouldBe "monday"
        }
    })

class PatchContractSpec :
    FunSpec({

        test("applying does not modify its source") {
            val changes = OrderDiffer.diff(order, order.copy(reference = "R-2")).changes

            OrderDiffer.apply(order, changes)

            order.reference shouldBe "R-1"
        }

        test("applying is repeatable") {
            val changes = OrderDiffer.diff(order, order.copy(reference = "R-2")).changes

            OrderDiffer.apply(order, changes).value shouldBe OrderDiffer.apply(order, changes).value
        }

        test("an empty change list returns an equal instance and reports nothing") {
            val result = OrderDiffer.apply(order, emptyList())

            result.value shouldBe order
            result.isClean shouldBe true
        }

        test("a compare-only differ makes its property unpatchable, and the rest still patches") {
            val after = order.copy(reference = "R-2", weight = Weight("750"))

            val result = OrderDiffer.apply(order, OrderDiffer.diff(order, after).changes)

            result.value.reference shouldBe "R-2"
            result.value.weight.grams shouldBe "500"
            result.failures.single().reason shouldBe
                PatchFailure.Reason.UnpatchableProperty("weight")
        }

        test("a change whose path names no compared property is reported") {
            val stray = io.github.kdiff.runtime.ValueChanged(
                io.github.kdiff.runtime.FieldPath.of("nonsense"),
                "a",
                "b",
            )

            val result = OrderDiffer.apply(order, listOf(stray))

            result.failures.single().change shouldBe stray
            result.failures.single().reason shouldBe
                PatchFailure.Reason.UnknownProperty("Order")
            result.value shouldBe order
        }

        test("applicable changes still apply when one fails") {
            val stray = io.github.kdiff.runtime.ValueChanged(
                io.github.kdiff.runtime.FieldPath.of("nonsense"),
                "a",
                "b",
            )
            val real = OrderDiffer.diff(order, order.copy(reference = "R-2")).changes

            val result = OrderDiffer.apply(order, real + stray)

            result.value.reference shouldBe "R-2"
            result.failures.map { it.change } shouldContainExactly listOf(stray)
        }
    })
