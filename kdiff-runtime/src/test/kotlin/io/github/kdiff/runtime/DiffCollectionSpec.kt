package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private data class Billing(val city: String, val street: String)
private data class Basket(val reference: String, val billing: Billing)

private fun path(vararg segments: Segment) = FieldPath(segments.toList())

private val onReference = ValueChanged(path(Segment.Field("reference")), "R-1", "R-2")
private val onBilling = ValueChanged(path(Segment.Field("billing")), "a", "b")
private val inBilling = ValueChanged(path(Segment.Field("billing"), Segment.Field("city")), "Paris", "Nice")

class DiffCollectionSpec :
    FunSpec({

        context("a diff is a collection of its changes") {
            val diff = Diff(onReference, onBilling, inBilling)

            test("iterating yields the changes in report order") {
                diff.toList() shouldContainExactly diff.changes
            }

            test("size agrees with the change list") {
                diff.size shouldBe 3
                Diff.EMPTY.size shouldBe 0
            }

            test("emptiness and non-emptiness agree with the change list") {
                diff.isEmpty() shouldBe false
                diff.isNotEmpty() shouldBe true
                Diff.EMPTY.isEmpty() shouldBe true
                Diff.EMPTY.isNotEmpty() shouldBe false
            }

            test("a standard library operator applies to the diff directly") {
                diff.filter { it.path.rootName() == "billing" } shouldContainExactly listOf(onBilling, inBilling)
                diff.count() shouldBe 3
            }
        }

        context("diffs combine") {
            test("the first diff's changes precede the second's") {
                val combined = Diff(onReference) + Diff(onBilling, inBilling)

                combined.changes shouldContainExactly listOf(onReference, onBilling, inBilling)
            }

            test("combining with an empty diff changes nothing") {
                val diff = Diff(onReference)

                (diff + Diff.EMPTY) shouldBe diff
                (Diff.EMPTY + diff) shouldBe diff
            }

            // Two changes at one path are both kept: nothing here can decide what they mean together.
            test("combining concatenates rather than reconciles") {
                val combined = Diff(onReference) + Diff(onReference)

                combined.size shouldBe 2
            }
        }

        context("the empty diff is a value") {
            test("it equals a diff built from an empty list") {
                Diff.EMPTY shouldBe Diff(emptyList())
                Diff() shouldBe Diff.EMPTY
            }
        }

        context("a diff narrows by property reference") {
            val diff = Diff(onReference, onBilling, inBilling)

            test("at keeps the property's own change and excludes what lies beneath it") {
                diff.at(Basket::billing).changes shouldContainExactly listOf(onBilling)
            }

            test("under keeps the property and everything beneath it, in order") {
                diff.under(Basket::billing).changes shouldContainExactly listOf(onBilling, inBilling)
            }

            test("a property nothing was reported at narrows to the empty diff") {
                Diff(onReference).under(Basket::billing) shouldBe Diff.EMPTY
            }

            test("narrowing returns a diff, so it composes with combining and narrowing again") {
                val billing = diff.under(Basket::billing) + Diff(onReference)

                billing.at(Basket::reference).changes shouldContainExactly listOf(onReference)
            }

            test("narrowing by a property nothing matches keeps the receiver's identity intact") {
                val all = diff.under(Basket::billing) + diff.at(Basket::reference)

                all.size shouldBe 3
            }
        }
    })
