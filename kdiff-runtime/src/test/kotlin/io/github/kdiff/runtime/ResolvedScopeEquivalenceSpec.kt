package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Proves the selection rule by exhaustion rather than by sampling.
 *
 * `ResolvedScope.selects` is the function with the worst recorded failure mode in this repository —
 * a bug here once silently reported the whole object — and the dangerous direction is widening, which
 * a caller notices only as a callback they did not ask for. So the rule as it stood before the scope
 * rewrite is kept here as an oracle, and the live implementation is checked against it over the whole
 * interesting domain: every scope shape crossed with every short path.
 *
 * Scopes are built through `resolveAgainst`, the production construction path, so this spec does not
 * name `ResolvedScope`'s constructors and does not have to change when they do.
 */
class ResolvedScopeEquivalenceSpec : FunSpec({

    test("the live rule agrees with the reference rule over the whole enumerated domain") {
        val disagreements = enumerate { scope, change ->
            live(scope).selects(change) != reference(scope, change)
        }

        disagreements.take(5) shouldBe emptyList()
        disagreements.size shouldBe 0
    }

    // Without this the spec above could pass by comparing two copies of the same mistake, or by
    // enumerating a domain that never reaches the interesting branches.
    test("a one-character mutation of the rule is caught, and the case is named") {
        val disagreements = enumerate { scope, change ->
            live(scope).selects(change) != mutatedReference(scope, change)
        }

        disagreements.shouldNotBeEmptyAnd { first ->
            first shouldContainAll listOf("scope=", "path=")
        }
    }
})

/** A scope's three inputs, carried together so a disagreement can name the case that produced it. */
private data class Case(
    val fields: List<TrackedField>?,
    val depth: Int?,
    val excluded: Set<String>,
)

private val untracked = object : Differ<Any> {
    override fun diff(before: Any, after: Any): Diff = Diff(emptyList())
}

private fun live(case: Case): ResolvedScope =
    TrackScope<Any>(case.fields, case.depth, case.excluded).resolveAgainst(untracked)

/**
 * The selection rule exactly as it stood before this change, transcribed unaltered.
 *
 * `resolveAgainst` substitutes `UNLIMITED_DEPTH` for an absent depth, so the oracle does the same.
 */
private fun reference(case: Case, change: Change): Boolean {
    val fields = case.fields
    val depth = case.depth ?: UNLIMITED_DEPTH
    val segments = change.path.segments

    if (segments.isEmpty()) return true
    if (change.path.rootName() in case.excluded) return false

    val steps = segments.count { it is Segment.Field }
    if (fields == null) return within(steps, depth)

    val root = segments.first() as? Segment.Field ?: return false

    val depths = fields.filter { it.name == root.name }.map { it.depth }
    if (depths.isEmpty()) return false
    if (UNLIMITED_DEPTH in depths) return true
    return within(steps, depths.max())
}

private fun within(steps: Int, limit: Int): Boolean = limit == UNLIMITED_DEPTH || steps <= limit

/** The reference rule with `<=` weakened to `<`, to prove the enumeration reaches the depth branch. */
private fun mutatedReference(case: Case, change: Change): Boolean {
    val fields = case.fields
    val depth = case.depth ?: UNLIMITED_DEPTH
    val segments = change.path.segments

    if (segments.isEmpty()) return true
    if (change.path.rootName() in case.excluded) return false

    val steps = segments.count { it is Segment.Field }
    fun mutatedWithin(limit: Int) = limit == UNLIMITED_DEPTH || steps < limit

    if (fields == null) return mutatedWithin(depth)
    val root = segments.first() as? Segment.Field ?: return false
    val depths = fields.filter { it.name == root.name }.map { it.depth }
    if (depths.isEmpty()) return false
    if (UNLIMITED_DEPTH in depths) return true
    return mutatedWithin(depths.max())
}

private const val A = "a"
private const val B = "b"

private val depths = listOf(1, 2, 3, UNLIMITED_DEPTH)

/** A name declared zero, one or two times — two so that "widest depth wins" is exercised both ways. */
private fun namings(name: String): List<List<TrackedField>> = buildList {
    add(emptyList())
    depths.forEach { add(listOf(TrackedField(name, it))) }
    depths.forEach { first -> depths.forEach { second -> add(listOf(TrackedField(name, first), TrackedField(name, second))) } }
}

private val fieldLists: List<List<TrackedField>?> = buildList {
    // Null is the scope that names nothing because its caller wants everything; the empty list is the
    // scope that names nothing because there is nothing to name. Keeping both in the domain is the
    // point — conflating them is the bug this spec exists to prevent.
    add(null)
    namings(A).forEach { forA -> namings(B).forEach { forB -> add(forA + forB) } }
}

private val exclusions = listOf(emptySet(), setOf(A), setOf(B), setOf(A, B))

private val segments = listOf(
    Segment.Field(A),
    Segment.Field(B),
    Segment.Index(0),
    Segment.Key("k", "v"),
)

/** Every path of length zero to three over the four segment shapes. */
private val paths: List<FieldPath> = buildList {
    add(FieldPath.ROOT)
    segments.forEach { one ->
        add(FieldPath(listOf(one)))
        segments.forEach { two ->
            add(FieldPath(listOf(one, two)))
            segments.forEach { three -> add(FieldPath(listOf(one, two, three))) }
        }
    }
}

private val cases: List<Case> = buildList {
    exclusions.forEach { excluded ->
        // A depth is inert once properties are named, so two values prove it stays inert; the scope
        // that names none reads its depth directly, so that one takes the full range.
        fieldLists.forEach { fields ->
            val depthsForShape = if (fields == null) listOf(null, 1, 2, 3, UNLIMITED_DEPTH) else listOf(null, 2)
            depthsForShape.forEach { depth -> add(Case(fields, depth, excluded)) }
        }
    }
}

private fun enumerate(disagrees: (Case, Change) -> Boolean): List<String> = buildList {
    cases.forEach { case ->
        paths.forEach { path ->
            val change = ValueChanged(path, "before", "after")
            if (disagrees(case, change)) add(describe(case, path))
        }
    }
}

private fun describe(case: Case, path: FieldPath): String {
    val fields = case.fields?.joinToString { "${it.name}@${it.depth}" } ?: "<all>"
    return "scope=[$fields] depth=${case.depth} excluded=${case.excluded} path=[$path]"
}

private infix fun List<String>.shouldContainAll(fragments: List<String>) {
    fragments.forEach { fragment -> any { it.contains(fragment) } shouldBe true }
}

private fun List<String>.shouldNotBeEmptyAnd(assertion: (List<String>) -> Unit) {
    isNotEmpty() shouldBe true
    assertion(this)
}
