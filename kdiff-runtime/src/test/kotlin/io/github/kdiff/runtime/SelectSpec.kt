package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.core.spec.style.FunSpec

class SelectSpec : FunSpec({

    context("depth counts property steps only") {
        test("depth one reports the object's own properties and excludes a nested change") {
            val tracker = tracker(OrderDiffer, ORDER) { depth = 1 }

            val diff = tracker.update(
                ORDER.copy(reference = "R2", billing = ADDR_1.copy(city = "Nice")),
            )

            diff.paths() shouldContainExactly listOf("reference")
        }

        test("depth two reaches one level into a nested type but no further") {
            val tracker = tracker(OrderDiffer, ORDER) { depth = 2 }

            val diff = tracker.update(
                ORDER.copy(
                    reference = "R2",
                    billing = ADDR_1.copy(city = "Nice", country = Country("BE")),
                ),
            )

            diff.paths() shouldContainExactly listOf("reference", "billing.city")
        }

        test("an unlimited depth excludes nothing, three property steps down") {
            val tracker = tracker(OrderDiffer, ORDER) { depth = UNLIMITED_DEPTH }

            val diff = tracker.update(ORDER.copy(billing = ADDR_1.copy(country = Country("BE"))))

            diff.paths() shouldContainExactly listOf("billing.country.code")
        }

        test("an element added to a keyed list is reported at depth one") {
            val tracker = tracker(OrderDiffer, ORDER) { depth = 1 }

            val diff = tracker.update(ORDER.copy(addresses = ORDER.addresses + ADDR_3))

            diff.paths() shouldContainExactly listOf("addresses[id=A3]")
        }

        test("a map entry is reported at the same depth as a plain property") {
            val tracker = tracker(OrderDiffer, ORDER) { depth = 1 }

            val diff = tracker.update(ORDER.copy(amounts = mapOf("eur" to "1.1")))

            diff.paths() shouldContainExactly listOf("amounts[key=eur]")
        }

        test("a positional list element is reported at depth one") {
            val tracker = tracker(OrderDiffer, ORDER) { depth = 1 }

            val diff = tracker.update(ORDER.copy(tags = listOf("a", "c")))

            diff.paths() shouldContainExactly listOf("tags[1]")
        }

        test("a change inside a keyed list element is reported at depth two") {
            val tracker = tracker(OrderDiffer, ORDER) { depth = 2 }

            val diff = tracker.update(
                ORDER.copy(addresses = listOf(ADDR_1, ADDR_2.copy(street = "9 Rue Q"))),
            )

            diff.paths() shouldContainExactly listOf("addresses[id=A2].street")
        }

        test("a change inside a keyed list element is excluded at depth one") {
            val tracker = tracker(OrderDiffer, ORDER) { depth = 1 }

            val diff = tracker.update(
                ORDER.copy(addresses = listOf(ADDR_1, ADDR_2.copy(street = "9 Rue Q"))),
            )

            diff.changes shouldBe emptyList()
        }
    }

    context("a change excluded by depth is dropped, not relocated") {
        test("a deep change produces nothing at its ancestor") {
            val tracker = tracker(OrderDiffer, ORDER) { depth = 1 }

            val diff = tracker.update(ORDER.copy(billing = ADDR_1.copy(city = "Nice")))

            diff.changes shouldBe emptyList()
            diff.paths() shouldBe emptyList()
        }

        test("a reported change keeps the path the differ gave it") {
            val tracker = tracker(OrderDiffer, ORDER) { under(Order::billing) }
            val next = ORDER.copy(billing = ADDR_1.copy(country = Country("BE")))

            val reported = tracker.update(next).changes.single()

            reported shouldBe OrderDiffer.diff(ORDER, next).changes.single()
        }
    }

    context("an unselected property is excluded") {
        test("a change under a property the scope does not name is not reported") {
            val tracker = tracker(OrderDiffer, ORDER) { field(Order::reference) }

            val diff = tracker.update(ORDER.copy(status = "CLOSED"))

            diff.changes shouldBe emptyList()
        }
    }

    context("a change at the tracked object itself") {
        test("a subclass swap is reported however narrow the scope") {
            val tracker = tracker(PaymentDiffer, Card("10", "1234")) {
                field(Payment::amount)
                depth = 1
            }

            val diff = tracker.update(Transfer("10", "FR76"))

            diff.changes.filterIsInstance<TypeChanged>().single().let {
                it.beforeType shouldBe "Card"
                it.afterType shouldBe "Transfer"
            }
        }
    }

    context("each kind of change maps to its two sides") {
        fun sidesOf(before: Order, after: Order): List<Triple<String, Any?, Any?>> {
            val seen = mutableListOf<Triple<String, Any?, Any?>>()
            tracker(OrderDiffer, before) {
                onFieldChange { path, from, to -> seen += Triple(path.toString(), from, to) }
            }.update(after)
            return seen
        }

        test("a value change reports the old and the new value") {
            sidesOf(ORDER, ORDER.copy(reference = "R2")) shouldContainExactly
                listOf(Triple("reference", "R1", "R2"))
        }

        test("an addition reports a null before and the new value after") {
            sidesOf(ORDER, ORDER.copy(addresses = ORDER.addresses + ADDR_3)) shouldContainExactly
                listOf(Triple("addresses[id=A3]", null, ADDR_3))
        }

        test("a removal reports the old value before and a null after") {
            sidesOf(ORDER, ORDER.copy(addresses = listOf(ADDR_1))) shouldContainExactly
                listOf(Triple("addresses[id=A2]", ADDR_2, null))
        }

        test("a move reports its old and new index") {
            val moves = sidesOf(ORDER, ORDER.copy(addresses = listOf(ADDR_2, ADDR_1)))

            moves shouldContainExactly listOf(
                Triple("addresses[id=A1]", 0, 1),
                Triple("addresses[id=A2]", 1, 0),
            )
        }

        test("a sealed type change reports both instances") {
            val seen = mutableListOf<Triple<String, Any?, Any?>>()
            val card = Card("10", "1234")
            val transfer = Transfer("12", "FR76")

            tracker(PaymentDiffer, card) {
                onFieldChange { path, from, to -> seen += Triple(path.toString(), from, to) }
            }.update(transfer)

            seen shouldContainExactly listOf(
                Triple("", card, transfer),
                Triple("amount", "10", "12"),
            )
        }
    }

    context("scope resolution") {
        val declaredReferenceOnly = TrackedOrderDiffer(trackScopeOf(TrackedField("reference", 1)))
        val declaredEveryFieldAtOne = TrackedOrderDiffer(
            trackScopeOf(
                TrackedField("reference", 1),
                TrackedField("status", 1),
                TrackedField("billing", 1),
            ),
        )

        test("a declared scope is used when the caller names no property") {
            val tracker = tracker(declaredReferenceOnly, ORDER)

            val diff = tracker.update(ORDER.copy(reference = "R2", status = "CLOSED"))

            diff.paths() shouldContainExactly listOf("reference")
        }

        test("a property named at the call site replaces the declared scope") {
            val tracker = tracker(declaredReferenceOnly, ORDER) { field(Order::status) }

            val diff = tracker.update(ORDER.copy(reference = "R2", status = "CLOSED"))

            diff.paths() shouldContainExactly listOf("status")
        }

        test("a depth stated at the call site overrides the declared depth") {
            val tracker = tracker(declaredEveryFieldAtOne, ORDER) { depth = 2 }

            val diff = tracker.update(ORDER.copy(billing = ADDR_1.copy(city = "Nice")))

            diff.paths() shouldContainExactly listOf("billing.city")
        }

        test("a differ declaring no scope tracks everything") {
            val tracker = tracker(OrderDiffer, ORDER)

            val diff = tracker.update(
                ORDER.copy(reference = "R2", billing = ADDR_1.copy(country = Country("BE"))),
            )

            diff.paths() shouldContainExactly listOf("reference", "billing.country.code")
        }
    }

    context("an invalid depth is rejected where it is declared") {
        test("a zero depth on the scope is rejected, naming the accepted values") {
            val failure = shouldThrow<IllegalArgumentException> { trackScope<Order> { depth = 0 } }

            failure.message.orEmpty() shouldContain "at least 1"
        }

        test("a negative depth other than the unlimited marker is rejected") {
            shouldThrow<IllegalArgumentException> { trackScope<Order> { depth = -2 } }
        }

        test("a zero depth on a named property is rejected, naming the property") {
            val failure = shouldThrow<IllegalArgumentException> {
                trackScope<Order> { field(Order::reference, depth = 0) }
            }

            failure.message.orEmpty() shouldContain "reference"
        }

        test("the unlimited marker is accepted") {
            trackScope<Order> { depth = UNLIMITED_DEPTH }.trackedFields shouldBe null
        }
    }
})
