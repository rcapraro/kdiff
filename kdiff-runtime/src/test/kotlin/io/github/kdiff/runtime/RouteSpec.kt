package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

private fun Order.routed(after: Order, block: ChangeRoutes<Order>.() -> Unit) =
    OrderDiffer.diff(this, after).route(block)

/** A nested value holding a keyed collection, which no other fixture has, so a frame can reach one. */
private data class Line(val id: String, val qty: Int)

private data class Bundle(val label: String, val lines: List<Line>)

private data class Shipment(val ref: String, val bundle: Bundle, val spare: Bundle?)

private val LineDiffer: Differ<Line> = differ { field(Line::qty) }

private val BundleDiffer: Differ<Bundle> = differ {
    field(Bundle::label)
    keyedList(Bundle::lines, Line::id, LineDiffer)
}

private val ShipmentDiffer: Differ<Shipment> = differ {
    field(Shipment::ref)
    nested(Shipment::bundle, BundleDiffer)
    nested(Shipment::spare, BundleDiffer)
}

private val LINE_1 = Line("L1", 1)
private val LINE_2 = Line("L2", 2)
private val SHIPMENT = Shipment("S1", Bundle("box", listOf(LINE_1, LINE_2)), null)

private fun Shipment.routed(after: Shipment, block: ChangeRoutes<Shipment>.() -> Unit) =
    ShipmentDiffer.diff(this, after).route(block)

class RouteSpec :
    FunSpec({

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

        context("nested frames") {
            test("a frame dispatches on the framed property's own properties") {
                val seen = mutableListOf<String>()

                ORDER.routed(ORDER.copy(billing = ADDR_1.copy(city = "Nice"))) {
                    under(Order::billing) {
                        on(Addr::city) { changes -> seen += changes.map { it.path.toString() } }
                    }
                }

                seen shouldContainExactly listOf("city")
            }

            test("a frame dispatches exactly as routing the nested value's own diff would") {
                val after = ORDER.copy(billing = ADDR_1.copy(city = "Nice", country = Country("BE")))

                fun ChangeRoutes<Addr>.record(into: MutableList<String>) {
                    on(Addr::city) { changes -> into += "city " + changes.joinToString { it.path.toString() } }
                    on(Addr::country) { changes -> into += "country " + changes.joinToString { it.path.toString() } }
                }

                val framed = mutableListOf<String>()
                ORDER.routed(after) { under(Order::billing) { record(framed) } }

                val direct = mutableListOf<String>()
                AddrDiffer.diff(ORDER.billing, after.billing).route<Addr> { record(direct) }

                framed shouldContainExactly direct
                framed shouldContainExactly listOf("city city", "country country.code")
            }

            test("frames nest to a further level") {
                val seen = mutableListOf<String>()

                ORDER.routed(ORDER.copy(billing = ADDR_1.copy(country = Country("BE")))) {
                    under(Order::billing) {
                        under(Addr::country) {
                            on(Country::code) { changes -> seen += changes.map { it.path.toString() } }
                        }
                    }
                }

                seen shouldContainExactly listOf("code")
            }

            test("a handler for a property with no change inside a frame does not run") {
                val ran = mutableListOf<String>()

                ORDER.routed(ORDER.copy(billing = ADDR_1.copy(city = "Nice"))) {
                    under(Order::billing) {
                        on(Addr::city) { ran += "city" }
                        on(Addr::street) { ran += "street" }
                    }
                }

                ran shouldContainExactly listOf("city")
            }

            test("a keyed collection inside a frame supplies its element key") {
                val edited = mutableListOf<String>()
                val bundle = SHIPMENT.bundle

                SHIPMENT.routed(SHIPMENT.copy(bundle = bundle.copy(lines = listOf(LINE_1.copy(qty = 9), LINE_2)))) {
                    under(Shipment::bundle) {
                        onEach(Bundle::lines, Line::id) { changed { edited += it } }
                    }
                }

                edited shouldContainExactly listOf("L1")
            }

            test("an element added to a collection inside a frame arrives at its element type") {
                val added = mutableListOf<Line>()
                val bundle = SHIPMENT.bundle
                val extra = Line("L3", 3)

                SHIPMENT.routed(SHIPMENT.copy(bundle = bundle.copy(lines = bundle.lines + extra))) {
                    under(Shipment::bundle) {
                        onEach(Bundle::lines, Line::id) { added { added += it } }
                    }
                }

                added shouldContainExactly listOf(extra)
            }

            test("a frame's changes do not reach the enclosing fallback") {
                var ran = false

                ORDER.routed(ORDER.copy(billing = ADDR_1.copy(city = "Nice"))) {
                    under(Order::billing) { on(Addr::city) { } }
                    otherwise { ran = true }
                }

                ran shouldBe false
            }

            test("a change no handler inside a frame names reaches the enclosing fallback at its original path") {
                val unhandled = mutableListOf<String>()

                ORDER.routed(ORDER.copy(billing = ADDR_1.copy(city = "Nice", street = "9 Rue W"))) {
                    under(Order::billing) { on(Addr::city) { } }
                    otherwise { changes -> unhandled += changes.map { it.path.toString() } }
                }

                unhandled shouldContainExactly listOf("billing.street")
            }

            test("a frame's own fallback consumes what its handlers did not name") {
                val inner = mutableListOf<String>()
                var outerRan = false

                ORDER.routed(ORDER.copy(billing = ADDR_1.copy(city = "Nice", street = "9 Rue W"))) {
                    under(Order::billing) {
                        on(Addr::city) { }
                        otherwise { changes -> inner += changes.map { it.path.toString() } }
                    }
                    otherwise { outerRan = true }
                }

                inner shouldContainExactly listOf("street")
                outerRan shouldBe false
            }

            test("an unhandled change inside a frame with no fallback anywhere is ignored") {
                ORDER.routed(ORDER.copy(billing = ADDR_1.copy(street = "9 Rue W"))) {
                    under(Order::billing) { on(Addr::city) { error("must not run") } }
                }
            }

            test("a change at the framed property itself is unhandled") {
                val unhandled = mutableListOf<String>()

                ORDER.routed(ORDER.copy(shipping = ADDR_1)) {
                    under(Order::shipping) { on(Addr::city) { error("must not run") } }
                    otherwise { changes -> unhandled += changes.map { it.path.toString() } }
                }

                unhandled shouldContainExactly listOf("shipping")
            }

            test("a frame over a collection property propagates every change outward") {
                val unhandled = mutableListOf<String>()

                ORDER.routed(ORDER.copy(tags = emptyList())) {
                    under(Order::tags) { }
                    otherwise { changes -> unhandled += changes.map { it.path.toString() } }
                }

                unhandled shouldContainExactly listOf("tags[0]", "tags[1]")
            }

            test("several changes beneath one frame come back to the enclosing fallback in order") {
                val unhandled = mutableListOf<String>()
                val rewritten = ADDR_1.copy(street = "9 Rue W", city = "Nice", country = Country("BE"))

                ORDER.routed(ORDER.copy(billing = rewritten)) {
                    under(Order::billing) { on(Addr::city) { } }
                    otherwise { changes -> unhandled += changes.map { it.path.toString() } }
                }

                unhandled shouldContainExactly listOf("billing.street", "billing.country.code")
            }

            test("a frame's body declares its routes and runs once, when the routing is declared") {
                var bodies = 0

                ORDER.routed(ORDER.copy(reference = "R2")) {
                    on(Order::reference) { }
                    under(Order::billing) {
                        bodies++
                        on(Addr::city) { error("must not run") }
                    }
                }

                bodies shouldBe 1
            }

            test("a frame over a sealed property dispatches the properties the sealed type declares") {
                val seen = mutableListOf<String>()

                ORDER.routed(ORDER.copy(payment = Card("20", "1234"))) {
                    under(Order::payment) {
                        on(Payment::amount) { changes -> seen += changes.map { it.path.toString() } }
                    }
                }

                seen shouldContainExactly listOf("amount")
            }

            test("a subclass swap sits at the framed property, so a frame does not dispatch it") {
                val unhandled = mutableListOf<String>()

                ORDER.routed(ORDER.copy(payment = Transfer("10", "FR76"))) {
                    under(Order::payment) { on(Payment::amount) { error("must not run") } }
                    otherwise { changes -> unhandled += changes.map { it.path.toString() } }
                }

                unhandled shouldContainExactly listOf("payment")
            }

            test("naming a property in both a frame and another handler is rejected") {
                val failure = shouldThrow<IllegalArgumentException> {
                    ORDER.routed(ORDER.copy(billing = ADDR_1.copy(city = "Nice"))) {
                        under(Order::billing) { }
                        on(Order::billing) { }
                    }
                }

                failure.message shouldBe "billing is named by more than one handler"
            }

            test("a nested routing behaves the same for a hand-written differ") {
                val byHand = differ<Order> { nested(Order::billing, AddrDiffer) }
                val after = ORDER.copy(billing = ADDR_1.copy(city = "Nice", country = Country("BE")))

                fun collect(diff: Diff) = buildList {
                    diff.route<Order> {
                        under(Order::billing) {
                            on(Addr::city) { add("city") }
                            under(Addr::country) { on(Country::code) { changes -> add("code ${changes.size}") } }
                        }
                    }
                }

                collect(byHand.diff(ORDER, after)) shouldContainExactly collect(OrderDiffer.diff(ORDER, after))
            }
        }
    })
