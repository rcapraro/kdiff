package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private val TAGS = Segment.Field("tags")

private fun at(index: Int) = FieldPath(listOf(TAGS, Segment.Index(index)))

private fun positional(before: List<String>, after: List<String>): List<Change> = buildList {
    comparePositionalList("tags", before, after, differ = null)
}

/** An element whose `equals` reads less than its differ does: the standard entity, equal by identity. */
private class Entity(val id: String, val label: String) {
    override fun equals(other: Any?): Boolean = other is Entity && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Entity($id, $label)"
}

private object EntityDiffer : Differ<Entity> by differ({
    field(Entity::id)
    field(Entity::label)
})

/** A compared property the compiler-generated `equals` does not cover, as `getDeclaredProperties` sees. */
private data class Boxed(val id: String) {
    var label: String = ""
}

private object BoxedDiffer : Differ<Boxed> by differ({
    field(Boxed::id)
    field(Boxed::label)
})

private fun entities(before: List<Entity>, after: List<Entity>): List<Change> = buildList {
    comparePositionalList("tags", before, after, EntityDiffer)
}

/**
 * The comparison as it stood before an agreeing tail was excluded, kept as an oracle.
 *
 * `G5` says the two agree for every pair of equal-length lists. That is a claim about a whole domain,
 * so it is checked over one rather than sampled — the shape `ResolvedScopeEquivalenceSpec` uses, and
 * the reason this spec needs no property-testing dependency.
 */
private fun indexByIndex(before: List<String>, after: List<String>): List<Change> = buildList {
    val shared = minOf(before.size, after.size)
    for (index in 0 until shared) {
        if (before[index] != after[index]) add(ValueChanged(at(index), before[index], after[index]))
    }
    for (index in shared until after.size) add(Added(at(index), after[index]))
    for (index in shared until before.size) add(Removed(at(index), before[index]))
}

private fun listsUpTo(length: Int): List<List<String>> {
    var built = listOf(emptyList<String>())
    val all = built.toMutableList()
    repeat(length) {
        built = built.flatMap { prefix -> listOf("a", "b", "c").map { prefix + it } }
        all += built
    }
    return all
}

class PositionalListAlignmentSpec :
    FunSpec({

        context("one contiguous edit") {
            test("an element inserted at the head is reported as one addition") {
                positional(listOf("a", "b", "c"), listOf("x", "a", "b", "c")) shouldContainExactly
                    listOf(Added(at(0), "x"))
            }

            test("an element removed from the head is reported as one removal") {
                positional(listOf("x", "a", "b", "c"), listOf("a", "b", "c")) shouldContainExactly
                    listOf(Removed(at(0), "x"))
            }

            test("a run inserted in the middle is reported as additions at its new positions") {
                positional(listOf("a", "b", "c"), listOf("a", "x", "y", "b", "c")) shouldContainExactly
                    listOf(Added(at(1), "x"), Added(at(2), "y"))
            }

            test("a run removed from the middle is reported as removals at its old positions") {
                positional(listOf("a", "x", "y", "b", "c"), listOf("a", "b", "c")) shouldContainExactly
                    listOf(Removed(at(1), "x"), Removed(at(2), "y"))
            }

            test("a longer new list still reports additions at the trailing indices") {
                positional(listOf("a", "b"), listOf("a", "b", "c")) shouldContainExactly listOf(Added(at(2), "c"))
            }

            test("a shorter new list still reports removals at the trailing indices") {
                positional(listOf("a", "b", "c"), listOf("a", "b")) shouldContainExactly listOf(Removed(at(2), "c"))
            }

            test("a list of elements reached through a differ gains one element at the head") {
                val kept = Entity("1", "one")
                val added = Entity("9", "nine")

                entities(listOf(kept), listOf(added, kept)) shouldContainExactly listOf(Added(at(0), added))
            }

            test("an unchanged list produces no changes") {
                positional(listOf("a", "b", "c"), listOf("a", "b", "c")).shouldBeEmpty()
            }
        }

        context("the index rule") {
            test("a comparison reports additions or removals, never both") {
                val offenders = listsUpTo(3).flatMap { before ->
                    listsUpTo(3).map { after -> Triple(before, after, positional(before, after)) }
                }.filter { (_, _, changes) ->
                    changes.any { it is Added } && changes.any { it is Removed }
                }

                offenders.map { (before, after, _) -> "$before -> $after" } shouldBe emptyList()
            }

            test("an element change names a position both lists hold, and precedes every addition") {
                val offenders = listsUpTo(3).flatMap { before ->
                    listsUpTo(3).map { after -> Triple(before, after, positional(before, after)) }
                }.filter { (before, after, changes) ->
                    val changed = changes.filterIsInstance<ValueChanged>().map { it.path.indexOf() }
                    val outliers = changes.filter { it !is ValueChanged }.map { it.path.indexOf() }
                    changed.any { it !in before.indices || it !in after.indices } ||
                        changed.any { change -> outliers.any { it < change } }
                }

                offenders.map { (before, after, _) -> "$before -> $after" } shouldBe emptyList()
            }

            test("an insertion earlier than a changed element is not aligned") {
                positional(listOf("a", "b", "c"), listOf("x", "a", "b", "c2")) shouldContainExactly
                    listOf(
                        ValueChanged(at(0), "a", "x"),
                        ValueChanged(at(1), "b", "a"),
                        ValueChanged(at(2), "c", "b"),
                        Added(at(3), "c2"),
                    )
            }
        }

        context("equal lengths are compared index by index") {
            test("a reordered ranking reports a change at each position, and no addition or removal") {
                positional(listOf("alice", "bob", "carol"), listOf("bob", "alice", "carol")) shouldContainExactly
                    listOf(
                        ValueChanged(at(0), "alice", "bob"),
                        ValueChanged(at(1), "bob", "alice"),
                    )
            }

            test("the live comparison agrees with an index-by-index one over every equal-length pair") {
                val disagreements = listsUpTo(4).flatMap { before ->
                    listsUpTo(4).filter { it.size == before.size }.mapNotNull { after ->
                        "$before -> $after".takeIf { positional(before, after) != indexByIndex(before, after) }
                    }
                }

                disagreements.take(5) shouldBe emptyList()
                disagreements.size shouldBe 0
            }

            // Without this the test above could pass over a domain that never reaches an agreeing tail.
            test("the enumerated domain reaches pairs that agree at the end and differ before it") {
                val interesting = listsUpTo(4).flatMap { before ->
                    listsUpTo(4).filter { after ->
                        after.size == before.size &&
                            before.isNotEmpty() &&
                            before.last() == after.last() &&
                            before != after
                    }
                }

                (interesting.size > 100) shouldBe true
            }
        }

        context("boundaries") {
            test("an element added to a run of identical elements is reported at the run's first index") {
                positional(listOf("a"), listOf("a", "a")) shouldContainExactly listOf(Added(at(0), "a"))
                positional(listOf("a", "a"), listOf("a")) shouldContainExactly listOf(Removed(at(0), "a"))
            }

            test("two lists agreeing at neither end compare as they did before") {
                positional(listOf("a", "b"), listOf("c", "d")) shouldContainExactly
                    indexByIndex(listOf("a", "b"), listOf("c", "d"))
            }

            test("an empty list on either side reports only additions or only removals") {
                positional(emptyList(), listOf("a", "b")) shouldContainExactly
                    listOf(Added(at(0), "a"), Added(at(1), "b"))
                positional(listOf("a", "b"), emptyList()) shouldContainExactly
                    listOf(Removed(at(0), "a"), Removed(at(1), "b"))
            }
        }

        // A position is excluded using the comparison the caller would otherwise have performed, never
        // equality standing in for a differ. Both tests below pass under `==` trimming only by luck of
        // the element landing outside the tail; they place it at the tail deliberately.
        context("exclusion never stands in for the differ") {
            test("an element type whose equality is looser than its differ is still fully compared") {
                val head = Entity("1", "one")
                val before = listOf(head, Entity("2", "two"))
                val after = listOf(head, Entity("2", "TWO"))

                entities(before, after).map { it.path.toString() } shouldContainExactly listOf("tags[1].label")
            }

            test("a property the generated equality does not cover is still compared") {
                fun boxed(id: String, label: String) = Boxed(id).apply { this.label = label }

                val before = listOf(boxed("1", "one"))
                val after = listOf(boxed("1", "ONE"))

                before shouldBe after

                val changes = buildList { comparePositionalList("tags", before, after, BoxedDiffer) }

                changes.map { it.path.toString() } shouldContainExactly listOf("tags[0].label")
            }
        }
    })

private fun FieldPath.indexOf(): Int = (segments[1] as Segment.Index).index
