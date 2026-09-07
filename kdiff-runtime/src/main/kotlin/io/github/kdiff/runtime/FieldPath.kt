package io.github.kdiff.runtime

/**
 * One step in a [FieldPath].
 */
public sealed interface Segment {
    /** Navigation into the property called [name]. */
    public data class Field(public val name: String) : Segment

    /** Navigation to position [index] of a collection compared by position. */
    public data class Index(public val index: Int) : Segment

    /**
     * Navigation to the element or entry identified by [value], where [property] names what the
     * value identifies it by — a key property for a list, or `key` for a map entry.
     *
     * The key is retained as itself rather than as text: an entry added to a `Map<Int, V>` can only
     * be reconstructed from an `Int`, and a rendered `"1"` cannot be turned back into one.
     */
    public data class Key(public val property: String, public val value: Any?) : Segment
}

/**
 * Where in an object graph a [Change] was found, as a path from the root being diffed.
 *
 * An empty path denotes the root object itself.
 *
 * [segments] is taken as given rather than copied — a `FieldPath` is a value over that list — and a
 * path derived from this one may share its storage. So a caller constructing a `FieldPath` hands the
 * list over: mutating it afterwards changes this path and any path derived from it. Every path the
 * library builds is over a list it allocated for the purpose.
 */
@JvmInline
public value class FieldPath(public val segments: List<Segment>) {

    /** This path with [segment] inserted at the front, used when a nested differ's result is lifted into its parent. */
    // Built rather than `listOf(segment) + segments`, which allocates a singleton list and then copies
    // it into a second one. Lifting happens once per change per level of nesting, so it is the runtime's
    // most repeated allocation.
    public fun prefixedWith(segment: Segment): FieldPath = FieldPath(
        ArrayList<Segment>(segments.size + 1).also {
            it += segment
            it += segments
        },
    )

    /**
     * This path with [outer] and then [inner] inserted at the front, in one copy rather than two.
     *
     * A collection helper lifts a nested change twice — under the element and then under the property
     * — and doing it in one step halves both the copying and the intermediate `Change` instances.
     * Internal because [prefixedWith] is the lifting contract a `Change` states; this is the shortcut
     * its only two-segment callers take.
     */
    internal fun prefixedWith(outer: Segment, inner: Segment): FieldPath = FieldPath(
        ArrayList<Segment>(segments.size + 2).also {
            it += outer
            it += inner
            it += segments
        },
    )

    /**
     * This path with its first segment dropped, the descent [prefixedWith] is the ascent of.
     *
     * Used when a routing frame re-roots the changes under a property so they can be dispatched
     * against that property's own type. Internal because routing is its only caller: a differ lifts
     * its nested results, which is a contract, while descending is how one routing reads another's
     * paths.
     */
    // A view rather than `drop(1)`'s copy: applying a diff descends once per level, so copying here
    // costs a change `d` levels deep `O(d²)` segment copies. Sharing the parent's storage is the
    // constructor's stated contract, and every path the library builds is over a list it allocated.
    //
    // The root path is returned as itself: `drop` tolerates having nothing to drop, `subList` does not,
    // and descending from the root is reachable — a routing frame re-roots a change reported at the
    // property it frames.
    internal fun withoutFirst(): FieldPath =
        if (segments.isEmpty()) this else FieldPath(segments.subList(1, segments.size))

    /**
     * The property this path begins with, or null when it begins with no property.
     *
     * Null for the root path, which belongs to no property — the type change a sealed type reports for
     * a subclass swap sits there. Null too if the first segment identifies a collection element rather
     * than a property, which no differ produces at the top of a path but is the honest answer if one
     * ever does.
     *
     * Matching on this name is workable but fragile: a typo is a valid `String`. Prefer [Diff.route],
     * which names each property by reference and dispatches on this for you.
     */
    public fun rootName(): String? = (segments.firstOrNull() as? Segment.Field)?.name

    /**
     * The path rendered so a reader can follow it back to the source: properties separated by
     * dots, an index in square brackets, a key as its property and value in square brackets.
     */
    override fun toString(): String = buildString {
        segments.forEach { segment ->
            when (segment) {
                is Segment.Field -> {
                    if (isNotEmpty()) append('.')
                    append(segment.name)
                }

                is Segment.Index -> append("[${segment.index}]")

                is Segment.Key -> append("[${segment.property}=${segment.value}]")
            }
        }
    }

    public companion object {
        public val ROOT: FieldPath = FieldPath(emptyList())

        public fun of(name: String): FieldPath = FieldPath(listOf(Segment.Field(name)))
    }
}
