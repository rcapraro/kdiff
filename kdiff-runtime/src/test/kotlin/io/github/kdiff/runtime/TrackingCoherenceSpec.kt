package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/** A third-party type, annotated nowhere: compared by `differ { }` and tracked by `trackScope { }`. */
private class Price(val amount: String, val currency: String)

private object PriceDiffer :
    Differ<Price> by differ({
        field(Price::amount)
        field(Price::currency)
    }),
    Patcher<Price> {
    override fun apply(before: Price, changes: List<Change>): PatchResult<Price> {
        val grouped = groupByProperty(changes, setOf("amount", "currency"))
        val amount = patchValue(before.amount, grouped.forProperty("amount"))
        val currency = patchValue(before.currency, grouped.forProperty("currency"))
        return PatchResult(
            Price(amount.value, currency.value),
            grouped.unmatchedFailures("Price") + amount.failures + currency.failures,
        )
    }
}

/** Deliberately compare-only, so a property can be unpatchable yet still trackable. */
private class Weight(val grams: String)

private object WeightDiffer : Differ<Weight> by differ({ field(Weight::grams) })

private data class Invoice(val reference: String, val total: Price, val weight: Weight)

private object InvoiceDiffer : Differ<Invoice>, Patcher<Invoice> {
    override fun diff(before: Invoice, after: Invoice): Diff = Diff(
        buildList {
            compareValue("reference", before.reference, after.reference)
            compareNested("total", before.total, after.total, PriceDiffer)
            compareNested("weight", before.weight, after.weight, WeightDiffer)
        },
    )

    override fun apply(before: Invoice, changes: List<Change>): PatchResult<Invoice> {
        val grouped = groupByProperty(changes, setOf("reference", "total", "weight"))
        val reference = patchValue(before.reference, grouped.forProperty("reference"))
        val total = patchNested(before.total, grouped.forProperty("total"), PriceDiffer)
        val weight = unpatchable(before.weight, grouped.forProperty("weight"), "weight")
        return PatchResult(
            before.copy(reference = reference.value, total = total.value, weight = weight.value),
            grouped.unmatchedFailures("Invoice") + reference.failures + total.failures + weight.failures,
        )
    }
}

/** A minimal type that can both compare and reconstruct, for the apply round trips. */
private data class Contact(val name: String, val email: String)

private object ContactDiffer : Differ<Contact>, Patcher<Contact> {
    override fun diff(before: Contact, after: Contact): Diff = Diff(
        buildList {
            compareValue("name", before.name, after.name)
            compareValue("email", before.email, after.email)
        },
    )

    override fun apply(before: Contact, changes: List<Change>): PatchResult<Contact> {
        val grouped = groupByProperty(changes, setOf("name", "email"))
        val name = patchValue(before.name, grouped.forProperty("name"))
        val email = patchValue(before.email, grouped.forProperty("email"))
        return PatchResult(
            before.copy(name = name.value, email = email.value),
            grouped.unmatchedFailures("Contact") + name.failures + email.failures,
        )
    }
}

class TrackingCoherenceSpec :
    FunSpec({

        context("a scope written by hand for a type that cannot be annotated") {
            test("it tracks the properties it names") {
                val tracker = tracker(PriceDiffer, Price("10", "EUR"), trackScope { field(Price::amount) })

                val diff = tracker.update(Price("12", "USD"))

                diff.paths() shouldContainExactly listOf("amount")
            }

            test("a type carrying no kdiff annotation is both compared and tracked") {
                val seen = mutableListOf<String>()
                val tracker = tracker(PriceDiffer, Price("10", "EUR")) {
                    under(Price::currency)
                    onFieldChange { path, _, _ -> seen += path.toString() }
                }

                tracker.update(Price("12", "GBP"))

                seen shouldContainExactly listOf("currency")
            }

            test("a hand-written scope is indistinguishable from a declared one") {
                val fields = arrayOf(TrackedField("reference", 1), TrackedField("billing", 2))
                val declared = TrackedOrderDiffer(trackScopeOf(*fields))
                val byHand = trackScope<Order> {
                    field(Order::reference)
                    field(Order::billing, depth = 2)
                }
                val next = ORDER.copy(
                    reference = "R2",
                    status = "CLOSED",
                    billing = ADDR_1.copy(city = "Nice", country = Country("BE")),
                )

                val fromDeclared = tracker(declared, ORDER).update(next)
                val fromHand = tracker(OrderDiffer, ORDER, byHand).update(next)

                fromHand.changes shouldContainExactly fromDeclared.changes
                fromHand.paths() shouldContainExactly listOf("reference", "billing.city")
            }
        }

        context("a property compared by a hand-written differ") {
            val invoice = Invoice("I1", Price("10", "EUR"), Weight("500"))

            test("a change inside a delegated property is tracked by its path") {
                val tracker = tracker(InvoiceDiffer, invoice) { under(Invoice::total) }

                val diff = tracker.update(invoice.copy(total = Price("12", "EUR")))

                diff.paths() shouldContainExactly listOf("total.amount")
            }

            test("a delegated property obeys depth like any nested property") {
                val tracker = tracker(InvoiceDiffer, invoice) { depth = 1 }

                val diff = tracker.update(invoice.copy(total = Price("12", "EUR")))

                diff.changes shouldBe emptyList()
            }

            test("a compare-only delegated differ is unpatchable yet still trackable") {
                val next = invoice.copy(weight = Weight("600"))
                val tracker = tracker(InvoiceDiffer, invoice) { under(Invoice::weight) }

                val diff = tracker.update(next)

                diff.paths() shouldContainExactly listOf("weight.grams")

                val patched = InvoiceDiffer.apply(invoice, diff.changes)
                patched.isClean shouldBe false
                patched.failures.single().reason shouldBe "weight is compared by a differ that cannot patch"
            }
        }

        context("a tracker's report can be applied to its baseline") {
            val ada = Contact("Ada", "ada@example.com")
            val grace = Contact("Grace", "grace@example.com")

            test("an unrestricted tracker's report reproduces the target") {
                val tracker = tracker(ContactDiffer, ada)

                val diff = tracker.update(grace)
                val patched = ContactDiffer.apply(ada, diff.changes)

                patched.value shouldBe grace
                patched.isClean shouldBe true
            }

            test("a narrowed tracker's report propagates only the tracked property") {
                val tracker = tracker(ContactDiffer, ada) { field(Contact::name) }

                val diff = tracker.update(grace)
                val patched = ContactDiffer.apply(ada, diff.changes)

                patched.value shouldBe ada.copy(name = grace.name)
                patched.value.email shouldBe ada.email
                patched.isClean shouldBe true
            }

            test("an empty report applies cleanly") {
                val tracker = tracker(ContactDiffer, ada) { field(Contact::name) }

                val diff = tracker.update(ada.copy(email = "other@example.com"))
                val patched = ContactDiffer.apply(ada, diff.changes)

                diff.changes shouldBe emptyList()
                patched.value shouldBe ada
                patched.isClean shouldBe true
            }
        }
    })
