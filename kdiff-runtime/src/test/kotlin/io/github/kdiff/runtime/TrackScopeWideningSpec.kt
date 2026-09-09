package io.github.kdiff.runtime

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * States the failure direction directly, without an oracle.
 *
 * A tracker reporting too little is noticed the first time an expected callback does not arrive. One
 * reporting a property the caller scoped out looks like a change they asked for, so widening is the
 * direction that goes unnoticed. `ResolvedScopeEquivalenceSpec` proves the rule did not change; this
 * proves the rule is the right shape, and would still catch a widening if the oracle itself were
 * wrong.
 */
class TrackScopeWideningSpec :
    FunSpec({

        test("a scope that names properties never selects a change rooted at another property") {
            val leaks = scopesNamingProperties().flatMap { scope ->
                pathsRootedOutside(scope.named).filter { path ->
                    scope.resolved.selects(ValueChanged(path, "before", "after"))
                }.map { "scope=${scope.named} selected [$it]" }
            }

            leaks shouldBe emptyList()
        }

        test("an excluded property is never selected, at any depth and under any scope") {
            val leaks = scopesExcluding(EXCLUDED).flatMap { resolved ->
                pathsRootedAt(EXCLUDED).filter { path ->
                    resolved.selects(ValueChanged(path, "before", "after"))
                }.map { "excluded scope selected [$it]" }
            }

            leaks shouldBe emptyList()
        }

        // The one change that belongs to no property, so no selector could name it: withholding it would
        // hide an object being replaced wholesale.
        test("a change at the tracked object itself is selected however narrow the scope") {
            val missed = scopesNamingProperties()
                .filterNot { it.resolved.selects(ValueChanged(FieldPath.ROOT, "before", "after")) }

            missed.size shouldBe 0
        }
    })

private const val TRACKED = "tracked"
private const val OTHER = "other"
private const val EXCLUDED = "excluded"

private val untracked = Differ<Any> { _, _ -> Diff.EMPTY }

private class NamedScope(val named: Set<String>, val resolved: ResolvedScope)

private val allDepths = listOf(1, 2, 3, UNLIMITED_DEPTH)

/** Every scope that names an explicit set of properties, including the set that names none. */
private fun scopesNamingProperties(): List<NamedScope> = buildList {
    val namings = listOf(
        emptyList<TrackedField>() to emptySet<String>(),
    ) + allDepths.flatMap { depth ->
        listOf(
            listOf(TrackedField(TRACKED, depth)) to setOf(TRACKED),
            listOf(TrackedField(TRACKED, depth), TrackedField(OTHER, depth)) to setOf(TRACKED, OTHER),
        )
    }

    namings.forEach { (fields, named) ->
        listOf(null, 1, 3, UNLIMITED_DEPTH).forEach { depth ->
            add(NamedScope(named, TrackScope<Any>(fields, depth, emptySet()).resolveAgainst(untracked)))
        }
    }
}

/** Every scope that excludes [property], across both scope shapes and every depth. */
private fun scopesExcluding(property: String): List<ResolvedScope> = buildList {
    listOf(null, 1, 3, UNLIMITED_DEPTH).forEach { depth ->
        add(TrackScope<Any>(null, depth, setOf(property)).resolveAgainst(untracked))
        allDepths.forEach { fieldDepth ->
            val fields = listOf(TrackedField(property, fieldDepth), TrackedField(TRACKED, fieldDepth))
            add(TrackScope<Any>(fields, depth, setOf(property)).resolveAgainst(untracked))
        }
    }
}

private val tails = listOf(
    emptyList(),
    listOf(Segment.Field(TRACKED)),
    listOf(Segment.Index(0)),
    listOf(Segment.Key("k", "v")),
    listOf(Segment.Field(TRACKED), Segment.Field(OTHER)),
    listOf(Segment.Index(0), Segment.Field(TRACKED)),
)

private fun pathsRootedAt(property: String): List<FieldPath> =
    tails.map { FieldPath(listOf(Segment.Field(property)) + it) }

private fun pathsRootedOutside(named: Set<String>): List<FieldPath> = listOf(TRACKED, OTHER, "neverNamed")
    .filterNot { it in named }
    .flatMap(::pathsRootedAt)
