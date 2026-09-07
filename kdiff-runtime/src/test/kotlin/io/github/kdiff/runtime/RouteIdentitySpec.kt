package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * What a routing hands to its fallback is the change the diff holds, not a copy of it.
 *
 * A caller that routes a diff and audits the leftovers needs to match those leftovers back to the diff
 * — and `ChangeRoutes.under` already depends on it, matching a frame's returned changes to the ones it
 * was given by reference. Dispatch now tracks leftovers by identity for the same reason.
 */
class RouteIdentitySpec :
    FunSpec({

        test("a change under an unnamed property reaches the fallback as the instance the diff holds") {
            val diff = OrderDiffer.diff(ORDER, ORDER.copy(status = "CLOSED"))
            val captured = mutableListOf<Change>()

            diff.route<Order> {
                on(Order::reference) { }
                otherwise { captured += it }
            }

            captured.single() shouldBeSameInstanceAs diff.changes.single()
        }

        test("a change a frame did not name reaches the outer fallback at its enclosing path") {
            val next = ORDER.copy(billing = ADDR_1.copy(country = Country("BE")))
            val diff = OrderDiffer.diff(ORDER, next)
            val captured = mutableListOf<Change>()

            diff.route<Order> {
                under(Order::billing) { on(Addr::city) { } }
                otherwise { captured += it }
            }

            captured.single() shouldBeSameInstanceAs diff.changes.single()
            captured.single().path.toString() shouldBe "billing.country.code"
        }

        test("several leftovers reach the fallback in the order the diff reports them") {
            val next = ORDER.copy(status = "CLOSED", note = "x", tags = listOf("a", "c"))
            val diff = OrderDiffer.diff(ORDER, next)
            val captured = mutableListOf<Change>()

            diff.route<Order> {
                on(Order::reference) { }
                otherwise { captured += it }
            }

            captured shouldContainExactly diff.changes
            captured.indices.forEach { captured[it] shouldBeSameInstanceAs diff.changes[it] }
        }

        test("a handled property's changes never reach the fallback") {
            val diff = OrderDiffer.diff(ORDER, ORDER.copy(status = "CLOSED", reference = "R2"))
            val handled = mutableListOf<Change>()
            val captured = mutableListOf<Change>()

            diff.route<Order> {
                on(Order::reference) { handled += it }
                on(Order::status) { handled += it }
                otherwise { captured += it }
            }

            handled.map { it.path.toString() } shouldContainExactly listOf("reference", "status")
            captured shouldBe emptyList()
        }

        // A diff can hold two changes equal in kind, path and values only when they sit under the same
        // property, so one routing decision covers both. This pins that they are not double-reported.
        test("two changes equal in every respect are both accounted for exactly once") {
            val repeated = ValueChanged(FieldPath.of("status"), "OPEN", "CLOSED")
            val diff = Diff(listOf(repeated, ValueChanged(FieldPath.of("status"), "OPEN", "CLOSED")))
            val handled = mutableListOf<Change>()
            val captured = mutableListOf<Change>()

            diff.route<Order> {
                on(Order::status) { handled += it }
                otherwise { captured += it }
            }

            handled.size shouldBe 2
            captured shouldBe emptyList()
        }
    })
