package io.github.kdiff.runtime

/**
 * Rebuilds an instance of [T] with a list of changes applied.
 *
 * Implemented by generated code, which is the only thing that can construct the type it serves. A
 * hand-written differ built with the `differ { }` DSL can read properties but not construct their
 * owner, so [Differ] deliberately does not require this.
 *
 * Implementations must be pure: [apply] never modifies its argument, and applying the same changes
 * twice returns equal results.
 */
public interface Patcher<T> {
    public fun apply(before: T, changes: List<Change>): PatchResult<T>
}

/**
 * The outcome of applying changes: the rebuilt [value], and every change that could not be applied.
 *
 * Changes that could be applied are applied even when others fail, so [value] is always usable and
 * [failures] says exactly what it is missing. A caller wanting strictness checks [isClean].
 */
public data class PatchResult<T>(
    public val value: T,
    public val failures: List<PatchFailure> = emptyList(),
) {
    /** True when every change was applied. */
    public val isClean: Boolean
        get() = failures.isEmpty()
}

/** A change that could not be applied, and why. */
public data class PatchFailure(public val change: Change, public val reason: String) {
    override fun toString(): String = "${change.path}: $reason"
}

/**
 * Changes sorted into the property each belongs to, plus those belonging to no known property.
 *
 * Grouping first is what lets a generated `apply` rebuild through a single `copy`: reconstructing a
 * type needs the final value of every constructor property at once, and a property with several
 * changes beneath it is rebuilt once rather than once per change.
 */
public class GroupedChanges internal constructor(
    private val byProperty: Map<String, List<Change>>,
    public val unmatched: List<Change>,
) {
    /** The changes under [property], each with that property's segment stripped from its path. */
    public fun forProperty(property: String): List<Change> = byProperty[property].orEmpty()
}

/**
 * Sorts [changes] by the property their path starts with, keeping only [properties] the type
 * actually compares. Anything else lands in [GroupedChanges.unmatched] and is reported.
 */
public fun groupByProperty(changes: List<Change>, properties: Set<String>): GroupedChanges {
    val byProperty = mutableMapOf<String, MutableList<Change>>()
    val unmatched = mutableListOf<Change>()

    changes.forEach { change ->
        val first = change.path.segments.firstOrNull()
        if (first !is Segment.Field || first.name !in properties) {
            unmatched += change
            return@forEach
        }
        byProperty.getOrPut(first.name) { mutableListOf() } += change.withoutFirstSegment()
    }

    return GroupedChanges(byProperty, unmatched)
}

/** Reports every change that matched no compared property of [type]. */
public fun GroupedChanges.unmatchedFailures(type: String): List<PatchFailure> =
    unmatched.map { PatchFailure(it, "$type has no compared property at this path") }

internal fun Change.withoutFirstSegment(): Change =
    withPath(FieldPath(path.segments.drop(1)))

private fun Change.withPath(path: FieldPath): Change = when (this) {
    is ValueChanged -> copy(path = path)
    is Added -> copy(path = path)
    is Removed -> copy(path = path)
    is TypeChanged -> copy(path = path)
    is Moved -> copy(path = path)
}
