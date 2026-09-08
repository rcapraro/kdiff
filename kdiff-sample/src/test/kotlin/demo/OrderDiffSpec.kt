package demo

import io.github.kdiff.runtime.Added
import io.github.kdiff.runtime.Moved
import io.github.kdiff.runtime.Removed
import io.github.kdiff.runtime.TypeChanged
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

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
    tags = listOf("urgent"),
    couponCodes = listOf("SAVE10"),
    labels = setOf("a", "b"),
    amounts = mapOf("eur" to "10"),
    payment = Card("10", "1234"),
    total = Money("10", "EUR"),
    weight = Weight("500"),
)

private fun diff(after: Order) = OrderDiffer.diff(order, after)

class OrderDiffSpec :
    FunSpec({

        test("an unchanged order reports nothing") {
            OrderDiffer.diff(order, order).isEmpty() shouldBe true
        }

        test("a changed scalar is reported at its property") {
            diff(order.copy(reference = "R-2")).changes.map { it.path.toString() } shouldContainExactly
                listOf("reference")
        }

        test("a changed enum is reported by value") {
            diff(order.copy(status = Status.CLOSED)).changes.map { it.path.toString() } shouldContainExactly
                listOf("status")
        }

        test("a null becoming a value is reported at the property, not as an addition") {
            val changes = diff(order.copy(note = "rush")).changes

            changes.map { it.path.toString() } shouldContainExactly listOf("note")
            changes.filterIsInstance<Added>().shouldBeEmpty()
        }

        test("an ignored property never reports, however much it differs") {
            diff(order.copy(lastTouched = "friday")).isEmpty() shouldBe true
        }

        test("a nested change is reported at a nested path") {
            diff(order.copy(billing = a1.copy(street = "9 Rue Z")))
                .changes.map { it.path.toString() } shouldContainExactly listOf("billing.street")
        }

        test("a nullable nested property becoming null reports at the property alone") {
            diff(order.copy(shipping = null))
                .changes.map { it.path.toString() } shouldContainExactly listOf("shipping")
        }

        test("a keyed list reports a modified element at its key") {
            diff(order.copy(addresses = listOf(a1, a2.copy(city = "Nice"))))
                .changes.map { it.path.toString() } shouldContainExactly listOf("addresses[id=A2].city")
        }

        test("a keyed list reports a reorder as a move rather than a removal and an addition") {
            val changes = diff(order.copy(addresses = listOf(a2, a1))).changes

            changes.filterIsInstance<Moved>().map { it.path.toString() }
                .toSet() shouldBe setOf("addresses[id=A1]", "addresses[id=A2]")
            changes.filterIsInstance<Removed>().shouldBeEmpty()
            changes.filterIsInstance<Added>().shouldBeEmpty()
        }

        test("an unkeyed list reports by index") {
            diff(order.copy(tags = listOf("calm")))
                .changes.map { it.path.toString() } shouldContainExactly listOf("tags[0]")
        }

        test("a nullable list appearing and disappearing reports one change at the property") {
            diff(order.copy(couponCodes = null))
                .changes.map { it.path.toString() } shouldContainExactly listOf("couponCodes")

            OrderDiffer.diff(order.copy(couponCodes = null), order)
                .changes.map { it.path.toString() } shouldContainExactly listOf("couponCodes")
        }

        test("a nullable list present on both sides is compared by index") {
            diff(order.copy(couponCodes = listOf("SAVE20")))
                .changes.map { it.path.toString() } shouldContainExactly listOf("couponCodes[0]")
        }

        test("a nullable list null on both sides is not a change") {
            val absent = order.copy(couponCodes = null)

            OrderDiffer.diff(absent, absent).isEmpty() shouldBe true
        }

        test("a set reports membership only") {
            val changes = diff(order.copy(labels = setOf("b", "c"))).changes

            changes.filterIsInstance<Removed>().map { it.value } shouldContainExactly listOf("a")
            changes.filterIsInstance<Added>().map { it.value } shouldContainExactly listOf("c")
        }

        test("a map reports a changed value at its entry key") {
            diff(order.copy(amounts = mapOf("eur" to "12")))
                .changes.map { it.path.toString() } shouldContainExactly listOf("amounts[key=eur]")
        }

        test("the same payment subclass delegates without a type change") {
            val changes = diff(order.copy(payment = Card("10", "5678"))).changes

            changes.map { it.path.toString() } shouldContainExactly listOf("payment.last4")
            changes.filterIsInstance<TypeChanged>().shouldBeEmpty()
        }

        test("a payment subclass swap reports a type change plus the sealed parent's own property") {
            val changes = diff(order.copy(payment = Transfer("12", "FR76"))).changes

            changes.filterIsInstance<TypeChanged>().single().let {
                it.path.toString() shouldBe "payment"
                it.beforeType shouldBe "Card"
                it.afterType shouldBe "Transfer"
            }
            changes.map { it.path.toString() } shouldContain "payment.amount"
        }

        test("the same payment singleton on both sides reports nothing about the payment") {
            val unpaid = order.copy(payment = Unpaid)

            OrderDiffer.diff(unpaid, unpaid).isEmpty() shouldBe true
        }

        test("a swap to a payment singleton reports a type change plus the sealed parent's own property") {
            val changes = diff(order.copy(payment = Unpaid)).changes

            changes.filterIsInstance<TypeChanged>().single().let {
                it.path.toString() shouldBe "payment"
                it.beforeType shouldBe "Card"
                it.afterType shouldBe "Unpaid"
            }
            changes.map { it.path.toString() } shouldContain "payment.amount"
        }

        test("a hand-written differ compares a type that cannot be annotated") {
            diff(order.copy(total = Money("12", "EUR")))
                .changes.map { it.path.toString() } shouldContainExactly listOf("total.amount")
        }

        test("the tree groups changes under a shared prefix and holds the same changes") {
            val diff = diff(order.copy(billing = a1.copy(street = "9 Rue Z", city = "Nice")))

            diff.tree().children.single().segment shouldBe io.github.kdiff.runtime.Segment.Field("billing")
            diff.tree().allChanges().size shouldBe diff.changes.size
        }

        test("a diff containing every kind of change renders one readable line each") {
            val changed = order.copy(
                reference = "R-2",
                addresses = listOf(a2.copy(city = "Nice"), Address("A3", "3 Rue W", "Nantes")),
                payment = Transfer("10", "FR76"),
            )

            val rendered = OrderDiffer.diff(order, changed).render()

            rendered shouldContain "reference"
            rendered shouldContain "REMOVED"
            rendered shouldContain "ADDED"
            rendered shouldContain "MOVED"
            rendered shouldContain "TYPE Card -> Transfer"
        }
    })
