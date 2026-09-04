package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun Order.routed(after: Order, block: ChangeRoutes<Order>.() -> Unit) =
    OrderDiffer.diff(this, after).route(block)

class RouteSpec : FunSpec({

    context("properties") {
        test("a handler runs once with every change under its property") {
            val seen = mutableListOf<List<String>>()

            ORDER.routed(ORDER.copy(billing = ADDR_1.copy(city = "Nice", country = Country("BE")))) {
                on(Order::billing) { changes -> seen += changes.map { it.path.toString() } }
            }

            seen shouldContainExactly listOf(listOf("billing.city", "billing.country.code"))
        }

        test("a property with no change does not run its handler") {
            var ran = false

            ORDER.routed(ORDER.copy(reference = "R2")) {
                on(Order::status) { ran = true }
            }

            ran shouldBe false
        }

        test("a change under a property no handler names reaches the fallback") {
            val unhandled = mutableListOf<String>()

            ORDER.routed(ORDER.copy(reference = "R2", status = "CLOSED")) {
                on(Order::reference) { }
                otherwise { changes -> unhandled += changes.map { it.path.toString() } }
            }

            unhandled shouldContainExactly listOf("status")
        }

        test("a change at the root reaches the fallback") {
            val unhandled = mutableListOf<Change>()

            PaymentDiffer.diff(Card("10", "1234"), Transfer("10", "FR76")).route<Payment> {
                on(Payment::amount) { }
                otherwise { unhandled += it }
            }

            unhandled.filterIsInstance<TypeChanged>().single().afterType shouldBe "Transfer"
        }

        test("an unhandled change with no fallback is ignored") {
            ORDER.routed(ORDER.copy(status = "CLOSED")) {
                on(Order::reference) { error("must not run") }
            }
        }

        test("the fallback does not run when every change is handled") {
            var ran = false

            ORDER.routed(ORDER.copy(reference = "R2")) {
                on(Order::reference) { }
                otherwise { ran = true }
            }

            ran shouldBe false
        }

        test("naming one property twice is rejected") {
            val failure = shouldThrow<IllegalArgumentException> {
                ORDER.routed(ORDER.copy(reference = "R2")) {
                    on(Order::reference) { }
                    on(Order::reference) { }
                }
            }

            failure.message shouldBe "reference is named by more than one handler"
        }
    }

    context("keyed collections") {
        test("an added element arrives at its element type") {
            val added = mutableListOf<Addr>()

            ORDER.routed(ORDER.copy(addresses = ORDER.addresses + ADDR_3)) {
                onEach(Order::addresses, Addr::id) { added { added += it } }
            }

            added shouldContainExactly listOf(ADDR_3)
        }

        test("a removed element arrives at its element type") {
            val removed = mutableListOf<Addr>()

            ORDER.routed(ORDER.copy(addresses = listOf(ADDR_1))) {
                onEach(Order::addresses, Addr::id) { removed { removed += it } }
            }

            removed shouldContainExactly listOf(ADDR_2)
        }

        test("a moved element arrives with its key and both positions") {
            val moves = mutableListOf<Triple<String, Int, Int>>()

            ORDER.routed(ORDER.copy(addresses = listOf(ADDR_2, ADDR_1))) {
                onEach(Order::addresses, Addr::id) {
                    moved { key, from, to -> moves += Triple(key, from, to) }
                }
            }

            moves shouldContainExactly listOf(
                Triple("A1", 0, 1),
                Triple("A2", 1, 0),
            )
        }

        test("an element changed in place runs the handler once, however much of it changed") {
            val edited = mutableListOf<String>()
            val rewritten = ADDR_1.copy(street = "9 Rue W", city = "Nice")

            ORDER.routed(ORDER.copy(addresses = listOf(rewritten, ADDR_2))) {
                onEach(Order::addresses, Addr::id) { changed { edited += it } }
            }

            edited shouldContainExactly listOf("A1")
        }

        test("a move is not an in-place change") {
            val edited = mutableListOf<String>()

            ORDER.routed(ORDER.copy(addresses = listOf(ADDR_2, ADDR_1))) {
                onEach(Order::addresses, Addr::id) { changed { edited += it } }
            }

            edited.shouldBeEmpty()
        }

        test("a kind of change with no handler is ignored, not passed to the fallback") {
            val unhandled = mutableListOf<Change>()

            ORDER.routed(ORDER.copy(addresses = ORDER.addresses + ADDR_3)) {
                onEach(Order::addresses, Addr::id) { removed { } }
                otherwise { unhandled += it }
            }

            unhandled.shouldBeEmpty()
        }
    }

    context("what a routing cannot express") {
        test("an element changed in place under an unkeyed collection reaches the fallback") {
            val unhandled = mutableListOf<String>()
            val nested = Diff(
                listOf(
                    ValueChanged(
                        FieldPath(listOf(Segment.Field("tags"), Segment.Index(0), Segment.Field("code"))),
                        "a",
                        "b",
                    ),
                ),
            )

            nested.route<Order> {
                onEach(Order::tags) { added { } }
                otherwise { changes -> unhandled += changes.map { it.path.toString() } }
            }

            unhandled shouldContainExactly listOf("tags[0].code")
        }

        test("an element of an unexpected type reaches the fallback rather than vanishing") {
            val unhandled = mutableListOf<Change>()
            val mistyped = Diff(
                listOf(Added(FieldPath(listOf(Segment.Field("addresses"), Segment.Index(0))), "not an Addr")),
            )

            mistyped.route<Order> {
                onEach(Order::addresses, Addr::id) { added { } }
                otherwise { unhandled += it }
            }

            unhandled.shouldHaveSize(1)
        }
    }

    context("unkeyed collections") {
        test("an added set element arrives at its element type") {
            val added = mutableListOf<String>()

            ORDER.routed(ORDER.copy(labels = ORDER.labels + "urgent")) {
                onEach(Order::labels) { added { added += it } }
            }

            added shouldContainExactly listOf("urgent")
        }

        test("a removed positional element arrives at its element type") {
            val removed = mutableListOf<String>()

            ORDER.routed(ORDER.copy(tags = emptyList())) {
                onEach(Order::tags) { removed { removed += it } }
            }

            removed shouldContainExactly ORDER.tags
        }
    }

    test("a hand-written differ routes exactly like a generated one would") {
        val byHand = differ<Order> {
            field(Order::reference)
            keyedList(Order::addresses, Addr::id, AddrDiffer)
        }
        val after = ORDER.copy(reference = "R2", addresses = listOf(ADDR_2, ADDR_1, ADDR_3))

        fun collect(diff: Diff) = buildList {
            diff.route<Order> {
                on(Order::reference) { add("reference") }
                onEach(Order::addresses, Addr::id) {
                    added { add("added ${it.id}") }
                    moved { key, from, to -> add("moved $key $from->$to") }
                }
            }
        }

        collect(byHand.diff(ORDER, after)) shouldContainExactly collect(OrderDiffer.diff(ORDER, after))
    }
})
