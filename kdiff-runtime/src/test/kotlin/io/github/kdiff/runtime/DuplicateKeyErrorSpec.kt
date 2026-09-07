package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private data class Element(val id: String, val label: String)

private object ElementDiffer :
    Differ<Element> by differ({
        field(Element::id)
        field(Element::label)
    }),
    Patcher<Element> {
    override fun apply(before: Element, changes: List<Change>): PatchResult<Element> = PatchResult(before)
}

private val first = Element("E1", "one")
private val shadowing = Element("E1", "another")

private fun compareKeyed(before: List<Element>, after: List<Element>): List<Change> = buildList {
    compareKeyedList("elements", "id", before, after, ElementDiffer) { it.id }
}

class DuplicateKeyErrorSpec :
    FunSpec({

        test("a duplicate key raises the declared type rather than a bare illegal argument") {
            val failure = shouldThrow<DuplicateDiffKeyException> {
                compareKeyed(listOf(first, shadowing), listOf(first))
            }

            failure.property shouldBe "elements"
            failure.keyProperty shouldBe "id"
            failure.key shouldBe "E1"
        }

        // The type is a refinement, not a replacement: a caller written before it existed still works.
        test("a duplicate key is still caught as an illegal argument") {
            val failure = shouldThrow<IllegalArgumentException> {
                compareKeyed(listOf(first, shadowing), listOf(first))
            }

            failure.message.shouldContain("two elements share the key E1")
        }

        test("applying refuses the same list with the same declared type") {
            val failure = shouldThrow<DuplicateDiffKeyException> {
                patchKeyedList(listOf(first, shadowing), emptyList(), ElementDiffer, "elements", "id") { it.id }
            }

            failure.property shouldBe "elements"
            failure.keyProperty shouldBe "id"
            failure.key shouldBe "E1"
        }

        // A helper called directly by a hand-written patcher has no names to report, and the message
        // omits them rather than being built round placeholders.
        test("a refusal with no names to give still carries the key") {
            val failure = shouldThrow<DuplicateDiffKeyException> {
                patchKeyedList(listOf(first, shadowing), emptyList(), ElementDiffer) { it.id }
            }

            failure.property shouldBe null
            failure.keyProperty shouldBe null
            failure.key shouldBe "E1"
            failure.message.shouldContain("two elements of a keyed list share the key E1")
        }
    })
