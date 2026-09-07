package demo

import io.github.kdiff.runtime.TrackedField
import io.github.kdiff.runtime.UNLIMITED_DEPTH
import io.github.kdiff.runtime.trackScope
import io.github.kdiff.runtime.tracker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

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
    labels = setOf("a", "b"),
    amounts = mapOf("eur" to "10"),
    payment = Card("10", "1234"),
    total = Money("10", "EUR"),
    weight = Weight("500"),
)

private fun paths(block: io.github.kdiff.runtime.TrackerBuilder<Order>.() -> Unit, next: Order) =
    tracker(OrderDiffer, order, block).update(next).changes.map { it.path.toString() }

class OrderTrackingSpec :
    FunSpec({

        context("the scope Order declares with @Trackable") {
            test("it is exposed on the same object that compares and patches Order") {
                OrderDiffer.trackScope.trackedFields shouldContainExactly listOf(
                    TrackedField("reference", 1),
                    TrackedField("status", 1),
                    TrackedField("billing", 2),
                    TrackedField("shipping", 1),
                    TrackedField("addresses", 1),
                    TrackedField("tags", 1),
                    TrackedField("labels", 1),
                    TrackedField("amounts", 1),
                    TrackedField("payment", 1),
                    TrackedField("total", 1),
                    TrackedField("weight", 1),
                )
            }

            test("@TrackIgnore keeps note out of the scope while it is still compared") {
                OrderDiffer.trackScope.trackedFields.orEmpty()
                    .map { it.name }
                    .shouldNotContain("note")

                paths({}, order.copy(note = "hello")).shouldBeEmpty()
                OrderDiffer.diff(order, order.copy(note = "hello")).changes
                    .map { it.path.toString() } shouldContainExactly listOf("note")
            }

            test("@DiffIgnore keeps lastTouched out of comparison and so out of tracking") {
                paths({}, order.copy(lastTouched = "tuesday")).shouldBeEmpty()
            }

            test("a tracker with no field of its own honours the declared depth of 1") {
                paths({}, order.copy(reference = "R-2", billing = a1.copy(city = "Nice"))) shouldContainExactly
                    listOf("reference", "billing.city")
            }

            test("@TrackDepth(2) on billing reaches its properties where depth 1 would not") {
                paths({}, order.copy(billing = a1.copy(street = "9 Rue Q"))) shouldContainExactly
                    listOf("billing.street")
                paths({}, order.copy(shipping = a2.copy(city = "Nice"))).shouldBeEmpty()
            }

            test("an element added to the keyed addresses list is reported at depth 1") {
                paths({}, order.copy(addresses = order.addresses + a3)) shouldContainExactly
                    listOf("addresses[id=A3]")
            }

            test("a change inside an addresses element is excluded at depth 1") {
                paths({}, order.copy(addresses = listOf(a1, a2.copy(city = "Nice")))).shouldBeEmpty()
            }
        }

        context("a field named at the call site replaces the declared scope") {
            test("only that field is reported") {
                paths({ field(Order::status) }, order.copy(reference = "R-2", status = Status.CLOSED))
                    .shouldContainExactly(listOf("status"))
            }

            test("under() reaches a whole subtree the declared depth would have cut off") {
                paths(
                    { under(Order::addresses) },
                    order.copy(addresses = listOf(a1, a2.copy(city = "Nice"))),
                ) shouldContainExactly listOf("addresses[id=A2].city")
            }

            test("a depth at the call site overrides the declared depths") {
                paths({ depth = UNLIMITED_DEPTH }, order.copy(addresses = listOf(a1, a2.copy(city = "Nice"))))
                    .shouldContainExactly(listOf("addresses[id=A2].city"))
            }

            test("both callback styles fire for one update") {
                var perField = 0
                var batched = 0
                val watcher = tracker(OrderDiffer, order) {
                    onFieldChange { _, _, _ -> perField++ }
                    onChange { _, _, _ -> batched++ }
                }

                watcher.update(order.copy(reference = "R-2", status = Status.CLOSED))

                perField shouldBe 2
                batched shouldBe 1
            }
        }

        context("a scope written by hand, for types that carry no tracking annotation") {
            test("Money is compared by a hand-written differ and tracked by a hand-written scope") {
                val watcher = tracker(MoneyDiffer, Money("10", "EUR"), trackScope { field(Money::amount) })

                watcher.update(Money("12", "USD")).changes.map { it.path.toString() } shouldContainExactly
                    listOf("amount")
            }

            test("a hand-written scope over Order matches what @Trackable declares") {
                val byHand = trackScope<Order> {
                    field(Order::reference)
                    field(Order::billing, depth = 2)
                }
                val next = order.copy(reference = "R-2", billing = a1.copy(city = "Nice"))

                tracker(OrderDiffer, order, byHand).update(next).changes shouldContainExactly
                    tracker(OrderDiffer, order) {
                        field(Order::reference)
                        field(Order::billing, depth = 2)
                    }.update(next).changes
            }

            test("the compare-only Weight differ is unpatchable yet still trackable") {
                val next = order.copy(weight = Weight("600"))

                val reported = tracker(OrderDiffer, order) { under(Order::weight) }.update(next)

                reported.changes.map { it.path.toString() } shouldContainExactly listOf("weight.grams")

                val patched = OrderDiffer.apply(order, reported.changes)
                patched.isClean shouldBe false
                patched.failures.single().reason shouldBe
                    "weight is compared by a differ that cannot patch"
            }
        }

        context("a tracker's report applies to its baseline") {
            test("an unrestricted scope reproduces the instance the tracker was shown") {
                val next = order.copy(
                    reference = "R-2",
                    billing = a1.copy(city = "Nice"),
                    addresses = listOf(a1, a2.copy(street = "9 Rue Q"), a3),
                    total = Money("12", "EUR"),
                )
                val watcher = tracker(OrderDiffer, order) { depth = UNLIMITED_DEPTH }

                val patched = OrderDiffer.apply(order, watcher.update(next).changes)

                patched.failures.shouldBeEmpty()
                OrderDiffer.diff(patched.value, next).changes.shouldBeEmpty()
            }

            test("a narrowed scope propagates only the tracked field") {
                val next = order.copy(reference = "R-2", status = Status.CLOSED)
                val watcher = tracker(OrderDiffer, order) { field(Order::reference) }

                val patched = OrderDiffer.apply(order, watcher.update(next).changes)

                patched.failures.shouldBeEmpty()
                patched.value.reference shouldBe next.reference
                patched.value.status shouldBe order.status
            }

            test("an empty report applies cleanly") {
                val watcher = tracker(OrderDiffer, order) { field(Order::reference) }

                val reported = watcher.update(order.copy(status = Status.CLOSED))
                val patched = OrderDiffer.apply(order, reported.changes)

                reported.changes.shouldBeEmpty()
                patched.failures.shouldBeEmpty()
                patched.value shouldBe order
            }
        }
    })
