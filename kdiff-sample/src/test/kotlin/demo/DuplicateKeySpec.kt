package demo

import io.github.kdiff.runtime.trackScope
import io.github.kdiff.runtime.trackedDiff
import io.github.kdiff.runtime.tracker
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.time.Instant

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
    tags = listOf("urgent", "b2b"),
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

/** Two elements of `addresses` carrying the same `@DiffKey` value. */
private val duplicated = order.copy(addresses = listOf(a1, a1.copy(street = "9 Rue Q")))

class DuplicateKeySpec :
    FunSpec({

        test("the generated differ rejects a keyed list holding two elements with one key") {
            shouldThrow<IllegalArgumentException> { OrderDiffer.diff(order, duplicated) }
                .message.shouldContain("addresses is keyed by id")
        }

        test("the generated patcher rejects the same list, with no changes to apply") {
            shouldThrow<IllegalArgumentException> { OrderDiffer.apply(duplicated, emptyList()) }
                .message.shouldContain("addresses[id=A1] cannot name one of them")
        }

        test("a tracker reports the rejection rather than swallowing it") {
            shouldThrow<IllegalArgumentException> {
                tracker(OrderDiffer, order) { field(Order::addresses) }.update(duplicated)
            }
        }

        test("a scope that excludes the offending property does not suppress the rejection") {
            shouldThrow<IllegalArgumentException> {
                tracker(OrderDiffer, order) { field(Order::reference) }.update(duplicated)
            }
        }

        test("trackedDiff rejects it too, whatever the scope names") {
            shouldThrow<IllegalArgumentException> {
                OrderDiffer.trackedDiff(order, duplicated, trackScope { field(Order::reference) })
            }
        }
    })
