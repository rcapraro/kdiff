package io.github.kdiff.runtime

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A node whose link is a `var`, which is the only way to build a genuine cycle out of this library's
 * kind of model — and exactly the shape `architecture.md` used to list as an unguarded sharp edge.
 */
private class Node(val name: String, var next: Node? = null)

private object NodeDiffer : Differ<Node>, Patcher<Node> {
    override fun diff(before: Node, after: Node): Diff = Diff(
        buildList {
            compareValue("name", before.name, after.name)
            compareNestedNullable("next", before.next, after.next, NodeDiffer)
        },
    )

    override fun apply(before: Node, changes: List<Change>): PatchResult<Node> {
        val grouped = groupByProperty(changes, setOf("name", "next"))
        val name = patchValue(before.name, grouped.forProperty("name"))
        val next = patchNestedNullable(before.next, grouped.forProperty("next"), NodeDiffer)
        return PatchResult(Node(name.value, next.value), name.failures + next.failures)
    }
}

/** A chain of [length] distinct nodes — deep, but with no instance reachable twice. */
private fun chain(length: Int, leaf: String): Node {
    var head = Node(leaf)
    repeat(length - 1) { head = Node("n", head) }
    return head
}

private fun cycle(name: String): Node {
    val head = Node(name)
    head.next = head
    return head
}

class CyclicStructureSpec :
    FunSpec({

        test("a cycle is refused rather than overflowing the stack") {
            val failure = shouldThrow<CyclicStructureException> { NodeDiffer.diff(cycle("a"), cycle("b")) }

            failure.repeated shouldBe true
            failure.message.shouldContain("contains a cycle")
        }

        // Only the steps inside the watch window are kept, so the path is the deepest stretch of the
        // descent rather than all 512 of them — enough to say where it stopped, at a bounded cost.
        test("the refusal names the path the descent stopped at") {
            val failure = shouldThrow<CyclicStructureException> { NodeDiffer.diff(cycle("a"), cycle("b")) }

            failure.path.segments.map { (it as Segment.Field).name }.distinct() shouldContainExactly listOf("next")
            failure.path.segments.size shouldBe Descent.WATCH_WINDOW + 1
            failure.message.shouldContain("next.next")
        }

        // A bound that refuses honest data would be a bug of its own, so the two cases must be
        // distinguishable: this is a model kdiff cannot reach, not a model that is wrong.
        test("a structure deeper than the bound says it saw no repeat") {
            val deep = chain(MAX_DESCENT + 5, "leaf")
            val other = chain(MAX_DESCENT + 5, "different")

            val failure = shouldThrow<CyclicStructureException> { NodeDiffer.diff(deep, other) }

            failure.repeated shouldBe false
            failure.message.shouldContain("No instance was seen twice")
            failure.message.shouldContain("rather than a cycle")
        }

        // Applying descends along the change paths and stops where the source runs out, so neither a
        // cyclic source nor a deep change list is unbounded on its own. Together they are: the source
        // never ends and the changes keep asking for more.
        test("applying refuses a cyclic source once the changes reach into it") {
            val deep = (0..MAX_DESCENT + 5).fold(FieldPath.of("name")) { path, _ ->
                path.prefixedWith(Segment.Field("next"))
            }

            val failure = shouldThrow<CyclicStructureException> {
                NodeDiffer.apply(cycle("a"), listOf(ValueChanged(deep, "a", "b")))
            }

            failure.repeated shouldBe true
        }

        test("a deep change list stops where the source ends, not at the bound") {
            val deep = (0..MAX_DESCENT + 5).fold(FieldPath.of("name")) { path, _ ->
                path.prefixedWith(Segment.Field("next"))
            }

            val patched = NodeDiffer.apply(chain(3, "leaf"), listOf(ValueChanged(deep, "a", "b")))

            patched.failures.single().reason shouldBe PatchFailure.Reason.NothingBeneathNull
        }

        test("applying a change list within the bound to a cyclic source is unaffected") {
            val changes = NodeDiffer.diff(Node("a"), Node("b")).changes

            NodeDiffer.apply(cycle("a"), changes).value.name shouldBe "b"
        }

        test("a self-referencing structure within the bound compares normally") {
            val before = chain(3, "leaf")
            val after = chain(3, "changed")

            val diff = NodeDiffer.diff(before, after)

            diff.changes.map { it.path.toString() } shouldContainExactly listOf("next.next.name")
        }

        test("a structure within the bound round-trips") {
            val before = chain(3, "leaf")
            val after = chain(3, "changed")

            val patched = NodeDiffer.apply(before, NodeDiffer.diff(before, after).changes)

            patched.failures shouldContainExactly emptyList()
            NodeDiffer.diff(patched.value, after).changes shouldContainExactly emptyList()
        }

        context("the descent leaves no state behind") {
            test("the counter returns to zero after a comparison that succeeded") {
                NodeDiffer.diff(chain(3, "leaf"), chain(3, "changed"))

                Descent.current().depth shouldBe 0
                Descent.current().trail shouldContainExactly emptyList()
            }

            test("the counter returns to zero after a comparison that threw") {
                shouldThrow<CyclicStructureException> { NodeDiffer.diff(cycle("a"), cycle("b")) }

                Descent.current().depth shouldBe 0
                Descent.current().trail shouldContainExactly emptyList()
            }

            test("a second comparison after a refusal is unaffected") {
                shouldThrow<CyclicStructureException> { NodeDiffer.diff(cycle("a"), cycle("b")) }

                NodeDiffer.diff(chain(3, "leaf"), chain(3, "changed")).changes
                    .map { it.path.toString() } shouldContainExactly listOf("next.next.name")
            }
        }
    })
