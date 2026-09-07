package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private data class Parcel(val reference: String, val weight: String)

private val parcelDiffer = differ<Parcel> {
    field(Parcel::reference)
    field(Parcel::weight)
}

class ScopeDeclarationSpec :
    FunSpec({

        // The two routes are one declaration and one implementation, so a scope either route accepts
        // the other accepts, and neither can start validating something the other does not.
        context("the standalone builder and the inline one offer the same members") {
            test("every member of the standalone builder is available inline") {
                val inline = tracker(parcelDiffer, Parcel("P-1", "500")) {
                    depth = UNLIMITED_DEPTH
                    field(Parcel::reference)
                    field(Parcel::weight, depth = 2)
                    under(Parcel::weight)
                }

                inline.update(Parcel("P-2", "600")).changes.map { it.path.toString() }
                    .shouldContainExactly(listOf("reference", "weight"))
            }

            test("a scope declared inline reports what the equivalent standalone scope reports") {
                val prepared = trackScope<Parcel> { field(Parcel::reference) }
                val next = Parcel("P-2", "600")

                val fromScope = tracker(parcelDiffer, Parcel("P-1", "500"), prepared).update(next)
                val fromInline = tracker(parcelDiffer, Parcel("P-1", "500")) { field(Parcel::reference) }.update(next)

                fromScope shouldBe fromInline
            }
        }

        context("a stated depth is validated on both routes") {
            test("a zero scope depth is rejected standalone") {
                shouldThrow<IllegalArgumentException> { trackScope<Parcel> { depth = 0 } }
                    .message.orEmpty() shouldContain "at least 1"
            }

            test("a zero scope depth is rejected inline, with the same message") {
                val standalone = shouldThrow<IllegalArgumentException> { trackScope<Parcel> { depth = 0 } }
                val inline = shouldThrow<IllegalArgumentException> {
                    tracker(parcelDiffer, Parcel("P-1", "500")) { depth = 0 }
                }

                inline.message shouldBe standalone.message
            }

            test("a zero field depth is rejected on both routes, naming the property") {
                shouldThrow<IllegalArgumentException> {
                    trackScope<Parcel> { field(Parcel::weight, depth = 0) }
                }.message.orEmpty() shouldContain "weight"

                shouldThrow<IllegalArgumentException> {
                    tracker(parcelDiffer, Parcel("P-1", "500")) { field(Parcel::weight, depth = 0) }
                }.message.orEmpty() shouldContain "weight"
            }

            test("the unlimited marker is accepted on both routes") {
                trackScope<Parcel> { depth = UNLIMITED_DEPTH }.trackedFields shouldBe null
                tracker(parcelDiffer, Parcel("P-1", "500")) { depth = UNLIMITED_DEPTH }.current.reference shouldBe "P-1"
            }
        }

        context("a differ describes something") {
            test("a differ naming no property and no subtype is rejected where it is built") {
                shouldThrow<IllegalArgumentException> { differ<Parcel> { } }
                    .message.orEmpty() shouldContain "name a property"
            }

            test("a differ naming only properties is accepted") {
                parcelDiffer.diff(Parcel("P-1", "500"), Parcel("P-2", "500")).size shouldBe 1
            }

            test("a differ naming only subtypes is accepted, and dispatches") {
                val subtypesOnly = differ<Any> {
                    subtype(Parcel::class, parcelDiffer)
                }

                subtypesOnly.diff(Parcel("P-1", "500"), Parcel("P-2", "500")).changes
                    .map { it.path.toString() } shouldContainExactly listOf("reference")
            }
        }

        // A `val` assigned inside a builder block and read after it only compiles when the block is
        // known to run exactly once, which is what the contract states.
        context("a builder block runs exactly once") {
            test("a value can be initialised inside each builder block") {
                val fromDiffer: String
                differ<Parcel> {
                    fromDiffer = "differ"
                    field(Parcel::reference)
                }

                val fromScope: String
                trackScope<Parcel> {
                    fromScope = "scope"
                    field(Parcel::reference)
                }

                val fromTracker: String
                tracker(parcelDiffer, Parcel("P-1", "500")) {
                    fromTracker = "tracker"
                }

                val fromRoute: String
                Diff.EMPTY.route<Parcel> {
                    fromRoute = "route"
                }

                listOf(fromDiffer, fromScope, fromTracker, fromRoute)
                    .shouldContainExactly(listOf("differ", "scope", "tracker", "route"))
            }
        }
    })
