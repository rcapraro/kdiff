package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * How a prepared scope, properties named at the call site, and a stated depth combine.
 *
 * Every case here narrows or widens what is reported, so getting one wrong leaks changes a caller
 * asked not to hear about — or, worse, silently reports everything.
 */
class TrackScopeCompositionSpec :
    FunSpec({

        val next = ORDER.copy(
            reference = "R2",
            status = "CLOSED",
            billing = ADDR_1.copy(city = "Nice", country = Country("BE")),
        )

        context("a prepared scope with a depth stated at the call site") {
            test("the depth applies to the prepared scope's properties rather than replacing them") {
                val prepared = trackScope<Order> { field(Order::billing) }

                val diff = tracker(OrderDiffer, ORDER, prepared) { depth = 2 }.update(next)

                diff.paths() shouldContainExactly listOf("billing.city")
            }

            test("stating a depth never widens a prepared scope to properties it did not name") {
                val prepared = trackScope<Order> { field(Order::reference) }

                val diff = tracker(OrderDiffer, ORDER, prepared) { depth = 2 }.update(next)

                diff.paths() shouldContainExactly listOf("reference")
            }

            test("the type's declared scope does not leak in when a prepared scope names a property") {
                val prepared = trackScope<Order> { field(Order::reference) }
                val declaring = TrackedOrderDiffer(trackScopeOf(TrackedField("status", 1)))

                val diff = tracker(declaring, ORDER, prepared) { depth = 2 }.update(next)

                diff.paths() shouldContainExactly listOf("reference")
            }
        }

        context("a property named at the call site beats a prepared scope") {
            test("the named property wins and the prepared scope is set aside") {
                val prepared = trackScope<Order> { under(Order::billing) }

                val diff = tracker(OrderDiffer, ORDER, prepared) { field(Order::reference) }.update(next)

                diff.paths() shouldContainExactly listOf("reference")
            }

            test("a prepared scope is used when the call site names nothing") {
                val prepared = trackScope<Order> { under(Order::billing) }

                val diff = tracker(OrderDiffer, ORDER, prepared).update(next)

                diff.paths() shouldContainExactly listOf("billing.city", "billing.country.code")
            }
        }

        context("the same property named twice") {
            test("the widest depth wins, whichever order the selectors are written in") {
                val narrowFirst = tracker(OrderDiffer, ORDER) {
                    field(Order::billing)
                    under(Order::billing)
                }.update(next)

                val wideFirst = tracker(OrderDiffer, ORDER) {
                    under(Order::billing)
                    field(Order::billing)
                }.update(next)

                narrowFirst.paths() shouldContainExactly listOf("billing.city", "billing.country.code")
                wideFirst.paths() shouldContainExactly narrowFirst.paths()
            }

            test("two bounded depths resolve to the deeper of the two") {
                val diff = tracker(OrderDiffer, ORDER) {
                    field(Order::billing, depth = 2)
                    field(Order::billing, depth = 3)
                }.update(next)

                diff.paths() shouldContainExactly listOf("billing.city", "billing.country.code")
            }
        }

        context("a scope naming what it excludes") {
            test("everything else is tracked") {
                val scope = trackScope<Order> { except(Order::status) }

                tracker(OrderDiffer, ORDER, scope).update(next).paths() shouldContainExactly
                    listOf("reference", "billing.city", "billing.country.code")
            }

            test("nothing beneath an excluded property is tracked either") {
                val scope = trackScope<Order> { except(Order::billing) }

                tracker(OrderDiffer, ORDER, scope).update(next).paths() shouldContainExactly
                    listOf("reference", "status")
            }

            test("a depth stated alongside cannot widen an exclusion back") {
                val scope = trackScope<Order> { except(Order::billing) }

                tracker(OrderDiffer, ORDER, scope) { depth = UNLIMITED_DEPTH }.update(next).paths() shouldContainExactly
                    listOf("reference", "status")
            }

            test("an exclusion at the call site narrows a prepared scope rather than replacing it") {
                val prepared = trackScope<Order> { except(Order::status) }

                val diff = tracker(OrderDiffer, ORDER, prepared) { except(Order::billing) }.update(next)

                diff.paths() shouldContainExactly listOf("reference")
            }

            test("an exclusion narrows the scope the differ's type declares") {
                val declaring = TrackedOrderDiffer(
                    trackScopeOf(TrackedField("reference", 1), TrackedField("status", 1)),
                )

                val diff = tracker(declaring, ORDER) { except(Order::status) }.update(next)

                diff.paths() shouldContainExactly listOf("reference")
            }

            test("excluding a property that is never compared excludes nothing") {
                val scope = trackScope<Order> { except(Order::labels) }

                tracker(OrderDiffer, ORDER, scope).update(next).paths() shouldContainExactly
                    listOf("reference", "status", "billing.city", "billing.country.code")
            }

            test("naming a tracked property and an exclusion in one scope is rejected") {
                val failure = shouldThrow<IllegalArgumentException> {
                    trackScope<Order> {
                        field(Order::reference)
                        except(Order::status)
                    }
                }

                failure.message shouldBe
                    "a scope names the properties it tracks or the properties it excludes, never both"
            }
        }

        context("a scope naming no property") {
            test("built by hand it tracks everything") {
                trackScope<Order> { }.trackedFields shouldBe null

                tracker(OrderDiffer, ORDER, trackScope { }).update(next).paths() shouldContainExactly
                    listOf("reference", "status", "billing.city", "billing.country.code")
            }

            test("built from an explicit empty list it tracks nothing but the object itself") {
                trackScopeOf<Order>().trackedFields shouldBe emptyList()

                tracker(OrderDiffer, ORDER, trackScopeOf()).update(next).changes shouldBe emptyList()
            }

            test("an empty explicit scope still reports a change at the tracked object itself") {
                val diff = tracker(PaymentDiffer, Card("10", "1234"), trackScopeOf())
                    .update(Transfer("10", "FR76"))

                diff.changes.filterIsInstance<TypeChanged>().single().afterType shouldBe "Transfer"
            }

            test("the two kinds are distinguishable by what they report they name") {
                val wantsAll = trackScope<Order> { }
                val hasNoneToName = trackScopeOf<Order>()

                wantsAll.trackedFields shouldBe null
                hasNoneToName.trackedFields shouldBe emptyList()
                wantsAll.trackedFields shouldNotBe hasNoneToName.trackedFields
            }
        }
    })
