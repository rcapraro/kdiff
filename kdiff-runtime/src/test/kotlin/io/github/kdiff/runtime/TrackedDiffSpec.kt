package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/** A scope applied to one comparison, for a caller holding both instances rather than a baseline. */
class TrackedDiffSpec :
    FunSpec({

        val next = ORDER.copy(
            reference = "R2",
            status = "CLOSED",
            billing = ADDR_1.copy(city = "Nice"),
        )

        test("a filtered comparison reports what a tracker with the same scope reports") {
            val scope = trackScope<Order> { under(Order::billing) }

            OrderDiffer.trackedDiff(ORDER, next, scope) shouldBe
                tracker(OrderDiffer, ORDER, scope).update(next)
        }

        test("the scope the differ's type declares applies when none is given") {
            val declaring = TrackedOrderDiffer(trackScopeOf(TrackedField("reference", 1)))

            declaring.trackedDiff(ORDER, next).paths() shouldContainExactly listOf("reference")
        }

        test("every compared property is reported when nothing is declared and none is given") {
            OrderDiffer.trackedDiff(ORDER, next).paths() shouldContainExactly
                listOf("reference", "status", "billing.city")
        }

        test("an exclusion is honoured") {
            val scope = trackScope<Order> { except(Order::billing) }

            OrderDiffer.trackedDiff(ORDER, next, scope).paths() shouldContainExactly
                listOf("reference", "status")
        }

        test("a frame reports nothing the enclosing scope filtered out") {
            fun framed(scope: TrackScope<Order>) = buildList {
                OrderDiffer.trackedDiff(ORDER, next, scope).route<Order> {
                    under(Order::billing) { on(Addr::city) { add("city") } }
                }
            }

            framed(trackScope { except(Order::billing) }).shouldBeEmpty()
            framed(trackScope { under(Order::billing) }) shouldContainExactly listOf("city")
        }

        test("nothing is retained: the same pair compares the same way twice") {
            OrderDiffer.trackedDiff(ORDER, next) shouldBe OrderDiffer.trackedDiff(ORDER, next)
        }
    })
