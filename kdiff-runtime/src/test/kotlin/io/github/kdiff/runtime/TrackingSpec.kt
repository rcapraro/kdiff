package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class TrackingSpec : FunSpec({

    context("a tracker observes an evolving instance against a baseline") {
        test("a tracker starts at its initial instance and has reported nothing") {
            var fired = 0
            val tracker = tracker(OrderDiffer, ORDER) { onFieldChange { _, _, _ -> fired++ } }

            tracker.current shouldBe ORDER
            fired shouldBe 0
        }

        test("an update reports how the new instance differs from the baseline") {
            val tracker = tracker(OrderDiffer, ORDER)

            val diff = tracker.update(ORDER.copy(reference = "R2"))

            diff.changes shouldContainExactly listOf(ValueChanged(FieldPath.of("reference"), "R1", "R2"))
        }

        test("an update adopts the new instance as the baseline") {
            val tracker = tracker(OrderDiffer, ORDER)
            val next = ORDER.copy(reference = "R2")

            val first = tracker.update(next)

            first.paths() shouldContainExactly listOf("reference")
            tracker.current shouldBe next
            tracker.update(next).changes shouldBe emptyList()
        }

        test("an update with an equal instance reports nothing and fires nothing") {
            var fired = 0
            val tracker = tracker(OrderDiffer, ORDER) {
                onFieldChange { _, _, _ -> fired++ }
                onChange { _, _, _ -> fired++ }
            }

            tracker.update(ORDER.copy()).changes shouldBe emptyList()
            fired shouldBe 0
        }

        test("an update returns the changes even with no listener registered") {
            tracker(OrderDiffer, ORDER).update(ORDER.copy(status = "CLOSED")).paths() shouldContainExactly
                listOf("status")
        }
    }

    context("an update does not mutate its instances") {
        test("both instances are unchanged afterwards") {
            val before = ORDER
            val after = ORDER.copy(reference = "R2", addresses = listOf(ADDR_1))
            val beforeCopy = before.copy()
            val afterCopy = after.copy()

            tracker(OrderDiffer, before).update(after)

            before shouldBe beforeCopy
            after shouldBe afterCopy
        }

        test("a reported change is one the differ reported, with its path unchanged") {
            val next = ORDER.copy(billing = ADDR_1.copy(city = "Nice"))

            val reported = tracker(OrderDiffer, ORDER).update(next).changes

            reported shouldContainExactly OrderDiffer.diff(ORDER, next).changes
        }
    }

    context("a tracker's baseline can be reset without reporting") {
        test("resetting replaces the baseline silently") {
            var fired = 0
            val tracker = tracker(OrderDiffer, ORDER) {
                onFieldChange { _, _, _ -> fired++ }
                onChange { _, _, _ -> fired++ }
            }
            val other = ORDER.copy(reference = "R9", status = "CLOSED")

            tracker.reset(other)

            fired shouldBe 0
            tracker.current shouldBe other
            tracker.update(other).changes shouldBe emptyList()
        }
    }

    context("a per-field callback reports each matching change separately") {
        test("it fires once per change, in the order the differ found them") {
            val seen = mutableListOf<String>()
            val tracker = tracker(OrderDiffer, ORDER) {
                onFieldChange { path, _, _ -> seen += path.toString() }
            }

            tracker.update(ORDER.copy(reference = "R2", status = "CLOSED"))

            seen shouldContainExactly listOf("reference", "status")
        }

        test("it does not fire when the scope excludes the only change") {
            val seen = mutableListOf<String>()
            val tracker = tracker(OrderDiffer, ORDER) {
                field(Order::reference)
                onFieldChange { path, _, _ -> seen += path.toString() }
            }

            tracker.update(ORDER.copy(status = "CLOSED"))

            seen shouldBe emptyList()
        }
    }

    context("a batched callback reports one update as a whole") {
        test("it fires once with both instances and every matching change in order") {
            val seen = mutableListOf<Triple<Order, Order, List<String>>>()
            val tracker = tracker(OrderDiffer, ORDER) {
                onChange { before, after, changes ->
                    seen += Triple(before, after, changes.map { it.path.toString() })
                }
            }
            val next = ORDER.copy(reference = "R2", status = "CLOSED")

            tracker.update(next)

            seen.single() shouldBe Triple(ORDER, next, listOf("reference", "status"))
        }

        test("it does not fire for an update matching nothing") {
            var fired = 0
            val tracker = tracker(OrderDiffer, ORDER) {
                field(Order::reference)
                onChange { _, _, _ -> fired++ }
            }

            tracker.update(ORDER.copy(status = "CLOSED"))

            fired shouldBe 0
        }

        test("both callback styles fire for the same update") {
            var perField = 0
            var batched = 0
            val tracker = tracker(OrderDiffer, ORDER) {
                onFieldChange { _, _, _ -> perField++ }
                onChange { _, _, _ -> batched++ }
            }

            tracker.update(ORDER.copy(reference = "R2", status = "CLOSED"))

            perField shouldBe 2
            batched shouldBe 1
        }

        test("several callbacks of the same style all fire") {
            var first = 0
            var second = 0
            val tracker = tracker(OrderDiffer, ORDER) {
                onChange { _, _, _ -> first++ }
                onChange { _, _, _ -> second++ }
            }

            tracker.update(ORDER.copy(reference = "R2"))

            first shouldBe 1
            second shouldBe 1
        }
    }

    context("a scope can select individual properties") {
        test("only the named property is reported") {
            val tracker = tracker(OrderDiffer, ORDER) { field(Order::reference) }

            val diff = tracker.update(ORDER.copy(reference = "R2", status = "CLOSED"))

            diff.paths() shouldContainExactly listOf("reference")
        }

        test("naming a property does not imply its subtree") {
            val tracker = tracker(OrderDiffer, ORDER) { field(Order::billing) }

            tracker.update(ORDER.copy(billing = ADDR_1.copy(city = "Nice"))).changes shouldBe emptyList()
        }

        test("naming several properties reports each of them") {
            val tracker = tracker(OrderDiffer, ORDER) {
                field(Order::reference)
                field(Order::status)
            }

            val diff = tracker.update(
                ORDER.copy(reference = "R2", status = "CLOSED", note = "hello"),
            )

            diff.paths() shouldContainExactly listOf("reference", "status")
        }
    }

    context("a scope can select a property and its whole subtree") {
        test("a subtree selection reports nested changes however deep") {
            val tracker = tracker(OrderDiffer, ORDER) { under(Order::billing) }

            val diff = tracker.update(ORDER.copy(billing = ADDR_1.copy(country = Country("BE"))))

            diff.paths() shouldContainExactly listOf("billing.country.code")
        }

        test("a subtree selection reports the property itself") {
            val tracker = tracker(OrderDiffer, ORDER) { under(Order::shipping) }

            val diff = tracker.update(ORDER.copy(shipping = ADDR_2))

            diff.paths() shouldContainExactly listOf("shipping")
        }

        test("a subtree selection does not widen to other properties") {
            val tracker = tracker(OrderDiffer, ORDER) { under(Order::billing) }

            tracker.update(ORDER.copy(reference = "R2")).changes shouldBe emptyList()
        }

        test("a subtree selection reaches into a keyed list element") {
            val tracker = tracker(OrderDiffer, ORDER) { under(Order::addresses) }

            val diff = tracker.update(
                ORDER.copy(addresses = listOf(ADDR_1, ADDR_2.copy(country = Country("BE")))),
            )

            diff.paths() shouldContainExactly listOf("addresses[id=A2].country.code")
        }

        test("a subtree selection reports set membership and map entries") {
            val tracker = tracker(OrderDiffer, ORDER) {
                under(Order::labels)
                under(Order::amounts)
            }

            val diff = tracker.update(ORDER.copy(labels = setOf("y"), amounts = mapOf("eur" to "1.1")))

            diff.paths() shouldContainExactly listOf("labels", "labels", "amounts[key=eur]")
        }
    }

    context("a scope with no selector tracks the whole object") {
        test("every change the differ reports is reported") {
            val tracker = tracker(OrderDiffer, ORDER)
            val next = ORDER.copy(
                reference = "R2",
                billing = ADDR_1.copy(city = "Nice"),
                addresses = listOf(ADDR_1, ADDR_2.copy(street = "9 Rue Q")),
            )

            val diff = tracker.update(next)

            diff.changes shouldContainExactly OrderDiffer.diff(ORDER, next).changes
            diff.paths() shouldContainExactly listOf(
                "reference",
                "billing.city",
                "addresses[id=A2].street",
            )
        }
    }
})
