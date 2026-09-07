package demo

import io.github.kdiff.runtime.Differ
import io.github.kdiff.runtime.differ
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The same model described twice: once by annotation, once by hand.
 *
 * Property for property, in declaration order — `lastTouched` named nowhere because `@DiffIgnore`
 * excludes it from the generated comparison too.
 */
private val AddressByHand: Differ<Address> = differ {
    field(Address::id)
    field(Address::street)
    field(Address::city)
}

private val CardByHand: Differ<Card> = differ {
    field(Card::amount)
    field(Card::last4)
}

private val TransferByHand: Differ<Transfer> = differ {
    field(Transfer::amount)
    field(Transfer::iban)
}

private val PaymentByHand: Differ<Payment> = differ {
    subtype(Card::class, CardByHand)
    subtype(Transfer::class, TransferByHand)
    field(Payment::amount)
}

private val OrderByHand: Differ<Order> = differ {
    field(Order::reference)
    field(Order::status)
    field(Order::note)
    nested(Order::billing, AddressByHand)
    nested(Order::shipping, AddressByHand)
    keyedList(Order::addresses, Address::id, AddressByHand)
    list(Order::tags)
    set(Order::labels)
    map(Order::amounts)
    nested(Order::payment, PaymentByHand)
    nested(Order::total, MoneyDiffer)
    nested(Order::weight, WeightDiffer)
}

private val first = Address("A1", "1 Rue X", "Paris")
private val second = Address("A2", "2 Rue Y", "Lyon")
private val third = Address("A3", "3 Rue Z", "Nice")

private val before = Order(
    reference = "R-1",
    status = Status.OPEN,
    note = null,
    lastTouched = "monday",
    billing = first,
    shipping = second,
    addresses = listOf(first, second),
    tags = listOf("urgent"),
    labels = setOf("a", "b"),
    amounts = mapOf("eur" to "10"),
    payment = Card("10", "1234"),
    total = Money("10", "EUR"),
    weight = Weight("500"),
)

private infix fun Order.agreesWith(after: Order) {
    OrderByHand.diff(this, after).changes shouldBe OrderDiffer.diff(this, after).changes
}

class HandWrittenParitySpec :
    FunSpec({

        test("both routes report nothing for an unchanged instance") {
            before agreesWith before
        }

        test("both routes report the same changes for a transition touching every shape") {
            val after = before.copy(
                reference = "R-2",
                status = Status.CLOSED,
                note = "rush",
                lastTouched = "friday",
                billing = first.copy(city = "Ockham"),
                shipping = null,
                addresses = listOf(second, first.copy(street = "9 Rue W"), third),
                tags = listOf("calm", "extra"),
                labels = setOf("b", "c"),
                amounts = mapOf("eur" to "12", "gbp" to "9"),
                payment = Transfer("10", "FR76"),
                total = Money("12", "EUR"),
                weight = Weight("600"),
            )

            OrderDiffer.diff(before, after).changes.shouldNotBeEmpty()
            before agreesWith after
        }

        test("both routes report the same changes within one subclass") {
            before agreesWith before.copy(payment = Card("12", "5678"))
        }

        test("both routes ignore the property the annotation excludes") {
            before agreesWith before.copy(lastTouched = "friday")
        }
    })
